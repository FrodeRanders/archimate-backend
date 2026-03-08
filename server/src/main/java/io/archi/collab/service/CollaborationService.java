package io.archi.collab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archi.collab.model.*;
import io.archi.collab.wire.ServerEnvelope;
import io.archi.collab.wire.inbound.*;
import io.archi.collab.wire.outbound.*;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class CollaborationService {
    private static final Logger LOG = LoggerFactory.getLogger(CollaborationService.class);
    private static final long DEFAULT_LOCK_TTL_MS = 10_000;
    private static final int MAX_ACTIVITY_EVENTS_PER_MODEL = 300;
    private static final int DEFAULT_WINDOW_LIMIT = 25;
    private static final String HEAD_REF = "HEAD";
    private static final String MODEL_EXPORT_FORMAT = "archi-model-export-v1";

    @Inject
    ValidationService validationService;

    @Inject
    RevisionService revisionService;

    @Inject
    LockService lockService;

    @Inject
    Neo4jRepository neo4jRepository;

    @Inject
    KafkaPublisher kafkaPublisher;

    @Inject
    KafkaConsumer kafkaConsumer;

    @Inject
    SessionRegistry sessionRegistry;

    @Inject
    IdempotencyService idempotencyService;

    @ConfigProperty(name = "app.consistency.check-enabled", defaultValue = "false")
    boolean consistencyChecksEnabled;

    @ConfigProperty(name = "app.compaction.retain-revisions", defaultValue = "1000")
    long defaultCompactionRetainRevisions;

    @ConfigProperty(name = "app.tags.allow-delete", defaultValue = "false")
    boolean allowTagDelete;

    private final Set<String> registeredModelCache = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ModelAccessControl> modelAccessControlCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JoinedModelRef> joinedRefsBySessionKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> actorSessionIdByWebsocketSessionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<AdminActivityEvent>> recentActivityByModel = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MutableStyleCounters> styleCountersByModel = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        kafkaConsumer.start();
        LOG.info("Collaboration service initialized; kafka consumer started");
    }

    public void onJoin(String modelId, Session session, JoinMessage join) {
        if (!ensureRegisteredModelForJoin(modelId, session)) {
            return;
        }
        ResolvedModelRef resolvedRef = resolveModelRef(modelId, join != null ? join.ref() : null);
        if (resolvedRef == null) {
            sendError(session, modelId, "TAG_NOT_FOUND",
                    "Requested model reference was not found for modelId " + modelId);
            return;
        }
        rememberJoinedRef(session, join != null ? join.actor() : null, resolvedRef);

        if (!resolvedRef.writable()) {
            sessionRegistry.send(session, new ServerEnvelope("CheckoutSnapshot",
                    new CheckoutSnapshotMessage(resolvedRef.revision(), resolvedRef.snapshot())));
            return;
        }

        sessionRegistry.register(modelId, session);
        long head = revisionService.headRevision(modelId);
        Long lastSeen = join != null ? join.lastSeenRevision() : null;
        LOG.info("Join: modelId={} sessionId={} lastSeenRevision={} headRevision={}",
                modelId, session == null ? "n/a" : session.getId(), lastSeen, head);
        recordActivity(modelId, "Join", "sessionId=" + safeSessionId(session) + " lastSeen=" + lastSeen + " head=" + head);

        long safeLastSeen = lastSeen != null ? lastSeen : 0L;
        if (lastSeen == null) {
            // Cold join always gets a full snapshot
            JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
            sessionRegistry.send(session, new ServerEnvelope("CheckoutSnapshot",
                    new CheckoutSnapshotMessage(head, snapshot)));
            return;
        }

        if (safeLastSeen > head) {
            LOG.warn("Join ahead-of-head detected: modelId={} sessionId={} lastSeenRevision={} headRevision={} - sending snapshot",
                    modelId, session == null ? "n/a" : session.getId(), safeLastSeen, head);
            JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
            sessionRegistry.send(session, new ServerEnvelope("CheckoutSnapshot",
                    new CheckoutSnapshotMessage(head, snapshot)));
            return;
        }

        if (safeLastSeen < head) {
            // Prefer delta replay; fallback to snapshot when history window is unavailable
            JsonNode opBatches = neo4jRepository.loadOpBatches(modelId, safeLastSeen + 1, head);
            if (!opBatches.isArray() || opBatches.isEmpty()) {
                LOG.warn("CheckoutDelta requested but no op batches found: modelId={} from={} to={} - sending snapshot fallback",
                        modelId, safeLastSeen + 1, head);
                JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
                sessionRegistry.send(session, new ServerEnvelope("CheckoutSnapshot",
                        new CheckoutSnapshotMessage(head, snapshot)));
                return;
            }
            sessionRegistry.send(session, new ServerEnvelope("CheckoutDelta",
                    new CheckoutDeltaMessage(safeLastSeen + 1, head, opBatches)));
            return;
        }

        JsonNode empty = JsonNodeFactory.instance.arrayNode();
        sessionRegistry.send(session, new ServerEnvelope("CheckoutDelta",
                new CheckoutDeltaMessage(head + 1, head, empty)));
    }

    public void onDisconnect(String modelId, Session session) {
        LOG.info("Disconnect: modelId={} sessionId={}",
                modelId, session == null ? "n/a" : session.getId());
        forgetJoinedRef(session);
        sessionRegistry.unregister(modelId, session);
        recordActivity(modelId, "Disconnect", "sessionId=" + safeSessionId(session));
    }

    public void onSubmitOps(String modelId, SubmitOpsMessage submitOps) {
        onSubmitOps(modelId, null, submitOps);
    }

    public void onSubmitOps(String modelId, Session session, SubmitOpsMessage submitOps) {
        if (!ensureRegisteredModel(modelId, session, "SubmitOps")) {
            return;
        }
        if (!ensureWritableRef(modelId, session, submitOps.actor(), "SubmitOps")) {
            return;
        }
        validationService.validateSubmitOps(modelId, submitOps);
        // Submission pipeline: normalize/expand -> validate -> idempotency/locks -> assign revisions -> persist/apply/broadcast
        JsonNode preparedOps = prepareOpsForSubmission(modelId, submitOps.ops());
        int submittedOpCount = submitOps.ops() != null && submitOps.ops().isArray() ? submitOps.ops().size() : 0;
        int styleOpCount = countStyleOps(preparedOps);
        if (styleOpCount > 0) {
            styleCounters(modelId).received.addAndGet(styleOpCount);
            recordStyleActivity(modelId, "Received", styleOpCount, submitOps.opBatchId());
        }
        int opCount = preparedOps == null ? 0 : preparedOps.size();
        int cascadeDeleteGenerated = Math.max(0, opCount - submittedOpCount);
        String opBatchId = submitOps.opBatchId();
        long baseRevision = submitOps.baseRevision();
        long headRevision = revisionService.headRevision(modelId);
        LOG.info("SubmitOps received: modelId={} opBatchId={} baseRevision={} opCount={} ops={}",
                modelId, opBatchId, baseRevision, opCount, summarizeOps(preparedOps));
        recordActivity(modelId, "SubmitOpsReceived",
                "opBatchId=" + opBatchId + " baseRevision=" + baseRevision + " opCount=" + opCount
                        + " cascadeDeleteGenerated=" + cascadeDeleteGenerated);
        if (cascadeDeleteGenerated > 0) {
            recordActivity(modelId, "CascadeDeleteExpanded",
                    "opBatchId=" + opBatchId + " generatedDeleteOps=" + cascadeDeleteGenerated);
        }

        if (baseRevision > headRevision) {
            LOG.warn("SubmitOps rejected: modelId={} opBatchId={} baseRevision={} exceeds headRevision={}",
                    modelId, opBatchId, baseRevision, headRevision);
            recordActivity(modelId, "SubmitOpsRejectedAhead",
                    "opBatchId=" + opBatchId + " baseRevision=" + baseRevision + " headRevision=" + headRevision);
            if (styleOpCount > 0) {
                styleCounters(modelId).rejected.addAndGet(styleOpCount);
                recordStyleActivity(modelId, "RejectedAhead", styleOpCount, opBatchId);
            }
            sessionRegistry.broadcast(modelId,
                    new ServerEnvelope("Error", new ErrorMessage("REVISION_AHEAD",
                            "baseRevision " + baseRevision + " exceeds server headRevision " + headRevision + "; rejoin required")));
            return;
        }

        Optional<String> preconditionFailure = validatePreconditions(modelId, preparedOps);
        if (preconditionFailure.isPresent()) {
            LOG.warn("SubmitOps precondition failed: modelId={} opBatchId={} details={}",
                    modelId, opBatchId, preconditionFailure.get());
            recordActivity(modelId, "SubmitOpsRejectedPrecondition",
                    "opBatchId=" + opBatchId + " details=" + preconditionFailure.get());
            if (styleOpCount > 0) {
                styleCounters(modelId).rejected.addAndGet(styleOpCount);
                recordStyleActivity(modelId, "RejectedPrecondition", styleOpCount, opBatchId);
            }
            sessionRegistry.broadcast(modelId,
                    new ServerEnvelope("Error", new ErrorMessage("PRECONDITION_FAILED", preconditionFailure.get())));
            return;
        }

        Optional<RevisionRange> known = idempotencyService.findRange(modelId, opBatchId);
        if (known.isPresent()) {
            LOG.info("SubmitOps idempotency hit: modelId={} opBatchId={} assigned={}..{}",
                    modelId, opBatchId, known.get().from(), known.get().to());
            recordActivity(modelId, "SubmitOpsIdempotent",
                    "opBatchId=" + opBatchId + " assigned=" + known.get().from() + ".." + known.get().to());
            if (styleOpCount > 0) {
                styleCounters(modelId).accepted.addAndGet(styleOpCount);
                recordStyleActivity(modelId, "AcceptedIdempotent", styleOpCount, opBatchId);
            }
            sessionRegistry.broadcast(modelId,
                    new ServerEnvelope("OpsAccepted", new OpsAcceptedMessage(opBatchId, baseRevision, known.get())));
            return;
        }

        Actor actor = submitOps.actor() != null ? submitOps.actor() : Actor.anonymous();
        List<String> lockTargets = lockRequiredTargets(preparedOps);
        if (!lockTargets.isEmpty()) {
            Optional<String> conflict = lockService.checkLockConflicts(modelId, actor, lockTargets);
            if (conflict.isPresent()) {
                LOG.warn("SubmitOps lock conflict: modelId={} opBatchId={} details={}",
                        modelId, opBatchId, conflict.get());
                recordActivity(modelId, "SubmitOpsRejectedLockConflict",
                        "opBatchId=" + opBatchId + " details=" + conflict.get());
                if (styleOpCount > 0) {
                    styleCounters(modelId).rejected.addAndGet(styleOpCount);
                    recordStyleActivity(modelId, "RejectedLockConflict", styleOpCount, opBatchId);
                }
                sessionRegistry.broadcast(modelId,
                        new ServerEnvelope("Error", new ErrorMessage("LOCK_CONFLICT", conflict.get())));
                return;
            }
        }

        RevisionRange range = revisionService.assignRange(modelId, opCount);
        JsonNode normalizedOps = normalizeOpsWithCausal(preparedOps, actor, opBatchId, range.from());
        idempotencyService.remember(modelId, opBatchId, range);
        LOG.info("SubmitOps accepted: modelId={} opBatchId={} assigned={}..{}",
                modelId, opBatchId, range.from(), range.to());
        recordActivity(modelId, "SubmitOpsAccepted",
                "opBatchId=" + opBatchId + " assigned=" + range.from() + ".." + range.to() + " opCount=" + opCount);
        if (styleOpCount > 0) {
            styleCounters(modelId).accepted.addAndGet(styleOpCount);
            recordStyleActivity(modelId, "Accepted", styleOpCount, opBatchId);
        }

        ObjectNode opBatch = JsonNodeFactory.instance.objectNode();
        opBatch.put("modelId", modelId);
        opBatch.put("opBatchId", opBatchId);
        opBatch.put("baseRevision", baseRevision);
        opBatch.set("assignedRevisionRange", toJsonRange(range));
        opBatch.put("timestamp", Instant.now().toString());
        opBatch.set("ops", normalizedOps);

        neo4jRepository.appendOpLog(modelId, opBatchId, range, opBatch);
        neo4jRepository.applyToMaterializedState(modelId, opBatch);
        if (styleOpCount > 0) {
            styleCounters(modelId).applied.addAndGet(styleOpCount);
            recordStyleActivity(modelId, "Applied", styleOpCount, opBatchId);
        }
        neo4jRepository.updateHeadRevision(modelId, range.to());
        runConsistencyChecksIfEnabled(modelId, opBatchId, range.to());

        kafkaPublisher.publishOps(modelId, opBatch);

        sessionRegistry.broadcast(modelId,
                new ServerEnvelope("OpsAccepted", new OpsAcceptedMessage(opBatchId, baseRevision, range)));
    }

    public void onAcquireLock(String modelId, AcquireLockMessage message) {
        onAcquireLock(modelId, null, message);
    }

    public void onAcquireLock(String modelId, Session session, AcquireLockMessage message) {
        if (!ensureRegisteredModel(modelId, session, "AcquireLock")) {
            return;
        }
        if (!ensureWritableRef(modelId, session, message.actor(), "AcquireLock")) {
            return;
        }
        Actor actor = message.actor() != null ? message.actor() : Actor.anonymous();
        List<String> targets = message.targets() != null ? message.targets() : List.of();
        long ttlMs = message.ttlMs() != null ? message.ttlMs() : DEFAULT_LOCK_TTL_MS;
        LOG.debug("AcquireLock: modelId={} actor={}/{} targetCount={} ttlMs={}",
                modelId, actor.userId(), actor.sessionId(), targets.size(), ttlMs);
        recordActivity(modelId, "AcquireLock",
                "actor=" + actor.userId() + "/" + actor.sessionId() + " targetCount=" + targets.size() + " ttlMs=" + ttlMs);

        var event = lockService.acquire(modelId, actor, targets, ttlMs);
        kafkaPublisher.publishLockEvent(modelId, event);
    }

    public void onReleaseLock(String modelId, ReleaseLockMessage message) {
        onReleaseLock(modelId, null, message);
    }

    public void onReleaseLock(String modelId, Session session, ReleaseLockMessage message) {
        if (!ensureRegisteredModel(modelId, session, "ReleaseLock")) {
            return;
        }
        if (!ensureWritableRef(modelId, session, message.actor(), "ReleaseLock")) {
            return;
        }
        Actor actor = message.actor() != null ? message.actor() : Actor.anonymous();
        List<String> targets = message.targets() != null ? message.targets() : List.of();
        LOG.debug("ReleaseLock: modelId={} actor={}/{} targetCount={}",
                modelId, actor.userId(), actor.sessionId(), targets.size());
        recordActivity(modelId, "ReleaseLock",
                "actor=" + actor.userId() + "/" + actor.sessionId() + " targetCount=" + targets.size());

        var event = lockService.release(modelId, actor, targets);
        kafkaPublisher.publishLockEvent(modelId, event);
    }

    public void onPresence(String modelId, PresenceMessage message) {
        onPresence(modelId, null, message);
    }

    public void onPresence(String modelId, Session session, PresenceMessage message) {
        if (!ensureRegisteredModel(modelId, session, "Presence")) {
            return;
        }
        if (!ensureWritableRef(modelId, session, message.actor(), "Presence")) {
            return;
        }
        Actor actor = message.actor() != null ? message.actor() : Actor.anonymous();
        LOG.debug("Presence: modelId={} actor={}/{} viewId={}",
                modelId, actor.userId(), actor.sessionId(), message.viewId());
        recordActivity(modelId, "Presence",
                "actor=" + actor.userId() + "/" + actor.sessionId() + " viewId=" + message.viewId());
        var event = new PresenceBroadcastMessage(actor, Instant.now(), message.viewId(), message.selection(), message.cursor());
        kafkaPublisher.publishPresence(modelId, event);
    }

    public ConsistencyStatus getConsistencyStatus(String modelId) {
        long inMemoryHead = revisionService.headRevision(modelId);
        long persistedHead = neo4jRepository.readHeadRevision(modelId);
        long latestCommitRevision = neo4jRepository.readLatestCommitRevision(modelId);
        boolean materializedStateConsistent = neo4jRepository.isMaterializedStateConsistent(modelId, inMemoryHead);
        boolean headAligned = inMemoryHead == persistedHead;
        boolean commitAligned = inMemoryHead == latestCommitRevision;
        boolean consistent = headAligned && commitAligned && materializedStateConsistent;
        return new ConsistencyStatus(
                modelId,
                inMemoryHead,
                persistedHead,
                latestCommitRevision,
                materializedStateConsistent,
                headAligned,
                commitAligned,
                consistent);
    }

    public JsonNode getSnapshot(String modelId) {
        return getSnapshot(modelId, null);
    }

    public JsonNode getSnapshot(String modelId, String ref) {
        ResolvedModelRef resolvedRef = resolveModelRef(modelId, ref);
        if (resolvedRef == null) {
            throw new IllegalArgumentException("Unknown model reference for modelId " + modelId + ": " + ref);
        }
        return resolvedRef.snapshot();
    }

    public AdminStatus getAdminStatus(String modelId) {
        JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
        ConsistencyStatus consistency = getConsistencyStatus(modelId);
        return new AdminStatus(
                modelId,
                snapshot.path("headRevision").asLong(0),
                countArray(snapshot, "elements"),
                countArray(snapshot, "relationships"),
                countArray(snapshot, "views"),
                countArray(snapshot, "viewObjects"),
                countArray(snapshot, "connections"),
                consistency);
    }

    public AdminRebuildStatus rebuildAndGetAdminStatus(String modelId) {
        RebuildStatus rebuild = rebuildMaterializedState(modelId);
        AdminStatus status = getAdminStatus(modelId);
        return new AdminRebuildStatus(rebuild, status);
    }

    public AdminModelWindow getAdminModelWindow(String modelId, Integer limit) {
        int safeLimit = sanitizeLimit(limit);
        JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
        List<ModelTagEntry> tags = neo4jRepository.listModelTags(modelId);
        return new AdminModelWindow(
                modelId,
                neo4jRepository.readModelName(modelId),
                sessionRegistry.sessionCount(modelId),
                summarizeTags(tags),
                getAdminStatus(modelId),
                styleCountersSnapshot(modelId),
                computeIntegrityFromSnapshot(modelId, snapshot),
                getRecentActivity(modelId, safeLimit),
                getRecentOpBatches(modelId, safeLimit));
    }

    public AdminIntegrityReport getAdminIntegrity(String modelId) {
        JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
        return computeIntegrityFromSnapshot(modelId, snapshot);
    }

    public AdminDeleteResult deleteModel(String modelId, boolean force) {
        int activeSessions = sessionRegistry.sessionCount(modelId);
        if (activeSessions > 0 && !force) {
            String message = "Model has active sessions; close clients first or use force=true.";
            recordActivity(modelId, "DeleteModelRejected", message + " activeSessions=" + activeSessions);
            return new AdminDeleteResult(modelId, false, activeSessions, message);
        }

        neo4jRepository.deleteModel(modelId);
        revisionService.clearModel(modelId);
        idempotencyService.clearModel(modelId);
        lockService.clearModel(modelId);
        registeredModelCache.remove(modelId);
        modelAccessControlCache.remove(modelId);
        recentActivityByModel.remove(modelId);
        styleCountersByModel.remove(modelId);
        recordActivity(modelId, "ModelDeleted", "force=" + force + " activeSessionsAtDelete=" + activeSessions);
        return new AdminDeleteResult(modelId, true, activeSessions, "Model deleted from server state.");
    }

    public List<AdminModelWindow> getAdminOverview(Integer limit) {
        int safeLimit = sanitizeLimit(limit);
        Set<String> modelIds = new LinkedHashSet<>(sessionRegistry.activeModelIds());
        modelIds.addAll(recentActivityByModel.keySet());
        for (ModelCatalogEntry entry : neo4jRepository.listModelCatalog()) {
            modelIds.add(entry.modelId());
        }
        List<AdminModelWindow> windows = new ArrayList<>();
        for (String modelId : modelIds) {
            windows.add(getAdminModelWindow(modelId, safeLimit));
        }
        windows.sort(Comparator.comparing(AdminModelWindow::modelId));
        return windows;
    }

    public List<ModelCatalogEntry> getModelCatalog() {
        return neo4jRepository.listModelCatalog();
    }

    public Optional<ModelAccessControl> findModelAccessControl(String modelId) {
        String normalizedModelId = normalizeModelId(modelId);
        ModelAccessControl cached = modelAccessControlCache.get(normalizedModelId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<ModelAccessControl> accessControl = neo4jRepository.readModelAccessControl(normalizedModelId);
        accessControl.ifPresent(value -> {
            registeredModelCache.add(normalizedModelId);
            modelAccessControlCache.put(normalizedModelId, value);
        });
        return accessControl;
    }

    public ModelAccessControl getModelAccessControl(String modelId) {
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        return findModelAccessControl(normalizedModelId)
                .orElse(new ModelAccessControl(normalizedModelId, Set.of(), Set.of(), Set.of()));
    }

    public ModelAccessControl updateModelAccessControl(String modelId, Set<String> adminUsers, Set<String> writerUsers, Set<String> readerUsers) {
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        ModelAccessControl accessControl = neo4jRepository.updateModelAccessControl(
                normalizedModelId,
                adminUsers,
                writerUsers,
                readerUsers);
        registeredModelCache.add(normalizedModelId);
        modelAccessControlCache.put(normalizedModelId, accessControl);
        return accessControl;
    }

    public List<ModelTagEntry> getModelTags(String modelId) {
        ensureRegisteredModelForAdmin(modelId);
        return neo4jRepository.listModelTags(modelId);
    }

    private AdminTagSummary summarizeTags(List<ModelTagEntry> tags) {
        if (tags == null || tags.isEmpty()) {
            return new AdminTagSummary(0, null, 0L, null);
        }
        ModelTagEntry latest = tags.get(0);
        return new AdminTagSummary(tags.size(), latest.tagName(), latest.revision(), latest.createdAt());
    }

    public ModelCatalogEntry registerModel(String modelId, String modelName) {
        return registerModel(modelId, modelName, null);
    }

    public ModelCatalogEntry registerModel(String modelId, String modelName, String creatorUserId) {
        String normalizedModelId = normalizeModelId(modelId);
        ModelCatalogEntry entry = neo4jRepository.registerModel(normalizedModelId, modelName);
        registeredModelCache.add(normalizedModelId);
        if (creatorUserId != null && !creatorUserId.isBlank()) {
            ModelAccessControl accessControl = neo4jRepository.updateModelAccessControl(
                    normalizedModelId,
                    Set.of(creatorUserId),
                    Set.of(creatorUserId),
                    Set.of(creatorUserId));
            modelAccessControlCache.put(normalizedModelId, accessControl);
        } else {
            modelAccessControlCache.remove(normalizedModelId);
        }
        return entry;
    }

    public ModelCatalogEntry renameModel(String modelId, String modelName) {
        String normalizedModelId = normalizeModelId(modelId);
        ModelCatalogEntry entry = neo4jRepository.renameModel(normalizedModelId, modelName);
        registeredModelCache.add(normalizedModelId);
        return entry;
    }

    public ModelTagEntry createModelTag(String modelId, String tagName, String description) {
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        String normalizedTagName = normalizeTagName(tagName);
        JsonNode snapshot = neo4jRepository.loadSnapshot(normalizedModelId);
        long revision = resolvedHeadRevision(normalizedModelId, snapshot);
        return neo4jRepository.createModelTag(normalizedModelId, normalizedTagName, description, revision, snapshot);
    }

    public void deleteModelTag(String modelId, String tagName) {
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        if (!allowTagDelete) {
            throw new IllegalStateException("Tag deletion is disabled; set app.tags.allow-delete=true to allow it.");
        }
        neo4jRepository.deleteModelTag(normalizedModelId, normalizeTagName(tagName));
    }

    public AdminModelExport exportModel(String modelId) {
        String normalizedModelId = normalizeModelId(modelId);
        ensureRegisteredModelForAdmin(normalizedModelId);
        JsonNode snapshot = neo4jRepository.loadSnapshot(normalizedModelId);
        long headRevision = resolvedHeadRevision(normalizedModelId, snapshot);
        JsonNode opBatches = headRevision > 0L
                ? neo4jRepository.loadOpBatches(normalizedModelId, 1L, headRevision)
                : JsonNodeFactory.instance.arrayNode();
        List<ModelTagExportEntry> tags = neo4jRepository.listModelTags(normalizedModelId).stream()
                .map(tag -> new ModelTagExportEntry(
                        tag.modelId(),
                        tag.tagName(),
                        tag.description(),
                        tag.revision(),
                        tag.createdAt(),
                        neo4jRepository.loadTaggedSnapshot(normalizedModelId, tag.tagName())))
                .toList();
        return new AdminModelExport(
                MODEL_EXPORT_FORMAT,
                Instant.now().toString(),
                new ModelCatalogEntry(normalizedModelId, neo4jRepository.readModelName(normalizedModelId), headRevision),
                getModelAccessControl(normalizedModelId),
                snapshot,
                opBatches,
                tags);
    }

    public AdminModelImportResult importModel(AdminModelExport exportPackage, boolean overwrite) {
        if (exportPackage == null) {
            throw new IllegalArgumentException("Import payload is required.");
        }
        if (!MODEL_EXPORT_FORMAT.equals(exportPackage.format())) {
            throw new IllegalArgumentException("Unsupported import format: " + exportPackage.format());
        }
        if (exportPackage.model() == null) {
            throw new IllegalArgumentException("Import payload must include model metadata.");
        }

        String modelId = normalizeModelId(exportPackage.model().modelId());
        validateImportPayload(exportPackage);
        boolean exists = neo4jRepository.modelRegistered(modelId);
        if (exists && !overwrite) {
            throw new IllegalStateException("Model already exists; re-run import with overwrite=true to replace it.");
        }
        if (exists && sessionRegistry.sessionCount(modelId) > 0) {
            throw new IllegalStateException("Model has active sessions; close clients before overwriting.");
        }

        if (exists) {
            deleteModel(modelId, true);
        }
        registerModel(modelId, exportPackage.model().modelName());
        if (exportPackage.accessControl() != null) {
            updateModelAccessControl(modelId,
                    exportPackage.accessControl().adminUsers(),
                    exportPackage.accessControl().writerUsers(),
                    exportPackage.accessControl().readerUsers());
        }

        int importedOpBatchCount = 0;
        long importedHeadRevision = 0L;
        JsonNode opBatches = exportPackage.opBatches();
        if (opBatches != null && opBatches.isArray()) {
            for (JsonNode opBatch : opBatches) {
                String opBatchId = requireText(opBatch, "opBatchId");
                JsonNode assignedRange = opBatch.path("assignedRevisionRange");
                long from = assignedRange.path("from").asLong(Long.MIN_VALUE);
                long to = assignedRange.path("to").asLong(Long.MIN_VALUE);
                if (from <= 0L || to < from) {
                    throw new IllegalArgumentException("Import op batch " + opBatchId + " has invalid assignedRevisionRange.");
                }
                RevisionRange range = new RevisionRange(from, to);
                neo4jRepository.appendOpLog(modelId, opBatchId, range, opBatch);
                neo4jRepository.applyToMaterializedState(modelId, opBatch);
                neo4jRepository.updateHeadRevision(modelId, to);
                idempotencyService.remember(modelId, opBatchId, range);
                importedHeadRevision = Math.max(importedHeadRevision, to);
                importedOpBatchCount++;
            }
        }

        if (importedOpBatchCount == 0) {
            importedHeadRevision = Math.max(0L, exportPackage.model().headRevision());
            neo4jRepository.updateHeadRevision(modelId, importedHeadRevision);
        }

        int importedTagCount = 0;
        if (exportPackage.tags() != null) {
            for (ModelTagExportEntry tag : exportPackage.tags()) {
                if (tag == null) {
                    continue;
                }
                neo4jRepository.restoreModelTag(
                        modelId,
                        normalizeTagName(tag.tagName()),
                        tag.description(),
                        tag.revision(),
                        tag.createdAt(),
                        tag.snapshot());
                importedTagCount++;
            }
        }

        revisionService.clearModel(modelId);
        registeredModelCache.add(modelId);
        recordActivity(modelId, "ImportModel",
                "overwrite=" + overwrite + " importedOpBatches=" + importedOpBatchCount + " importedTags=" + importedTagCount);
        return new AdminModelImportResult(
                modelId,
                exists,
                importedHeadRevision,
                importedOpBatchCount,
                importedTagCount,
                exists ? "Model overwritten from import package." : "Model imported from package.");
    }

    public AdminCompactionStatus compactModelMetadata(String modelId, Long retainRevisionsOverride) {
        long retainRevisions = retainRevisionsOverride == null
                ? defaultCompactionRetainRevisions
                : Math.max(0L, retainRevisionsOverride);
        AdminCompactionStatus status = neo4jRepository.compactMetadata(modelId, retainRevisions);
        recordActivity(modelId, "CompactMetadata",
                "retainRevisions=" + retainRevisions
                        + " committedHorizon=" + status.committedHorizonRevision()
                        + " watermark=" + status.watermarkRevision()
                        + " deletedCommits=" + status.deletedCommitCount()
                        + " deletedOps=" + status.deletedOpCount()
                        + " deletedPropertyClocks=" + status.deletedPropertyClockCount()
                        + " eligibleFieldClocks=" + status.eligibleFieldClockCount()
                        + " retainedTombstones=" + status.retainedTombstoneCount()
                        + " eligibleTombstones=" + status.eligibleTombstoneCount()
                        + " executed=" + status.executed());
        return status;
    }

    public RebuildStatus rebuildMaterializedState(String modelId) {
        long latestCommitRevision = neo4jRepository.readLatestCommitRevision(modelId);
        neo4jRepository.clearMaterializedState(modelId);

        int appliedBatchCount = 0;
        int appliedOpCount = 0;
        long rebuiltHeadRevision = 0L;

        if (latestCommitRevision > 0) {
            JsonNode opBatches = neo4jRepository.loadOpBatches(modelId, 1, latestCommitRevision);
            if (opBatches != null && opBatches.isArray()) {
                for (JsonNode opBatch : opBatches) {
                    neo4jRepository.applyToMaterializedState(modelId, opBatch);
                    appliedBatchCount++;
                    if (opBatch.path("ops").isArray()) {
                        appliedOpCount += opBatch.path("ops").size();
                    }
                    JsonNode assignedRange = opBatch.path("assignedRevisionRange");
                    long toRevision = assignedRange.path("to").asLong(-1L);
                    if (toRevision >= 0L) {
                        rebuiltHeadRevision = Math.max(rebuiltHeadRevision, toRevision);
                    }
                }
            }
        }

        if (rebuiltHeadRevision == 0L && latestCommitRevision > 0L) {
            rebuiltHeadRevision = latestCommitRevision;
        }
        neo4jRepository.updateHeadRevision(modelId, rebuiltHeadRevision);
        boolean consistent = neo4jRepository.isMaterializedStateConsistent(modelId, rebuiltHeadRevision);
        LOG.info("Rebuild materialized state: modelId={} requestedToRevision={} rebuiltHeadRevision={} appliedBatchCount={} appliedOpCount={} consistent={}",
                modelId, latestCommitRevision, rebuiltHeadRevision, appliedBatchCount, appliedOpCount, consistent);
        recordActivity(modelId, "RebuildMaterializedState",
                "requestedToRevision=" + latestCommitRevision + " rebuiltHeadRevision=" + rebuiltHeadRevision
                        + " appliedBatchCount=" + appliedBatchCount + " appliedOpCount=" + appliedOpCount
                        + " consistent=" + consistent);
        return new RebuildStatus(modelId, latestCommitRevision, rebuiltHeadRevision, appliedBatchCount, appliedOpCount, consistent);
    }

    private int countArray(JsonNode node, String field) {
        JsonNode array = node.path(field);
        return array.isArray() ? array.size() : 0;
    }

    private AdminIntegrityReport computeIntegrityFromSnapshot(String modelId, JsonNode snapshot) {
        List<AdminIntegrityIssue> issues = new ArrayList<>();

        Set<String> elementIds = collectIds(snapshot.path("elements"), "id");
        Set<String> relationshipIds = collectIds(snapshot.path("relationships"), "id");
        Set<String> viewIds = collectIds(snapshot.path("views"), "id");
        Set<String> viewObjectIds = collectIds(snapshot.path("viewObjects"), "id");

        int missingRelationshipEndpointCount = 0;
        JsonNode relationships = snapshot.path("relationships");
        if (relationships.isArray()) {
            for (JsonNode relationship : relationships) {
                String relationshipId = relationship.path("id").asText("");
                String sourceId = relationship.path("sourceId").asText("");
                String targetId = relationship.path("targetId").asText("");
                if (sourceId.isBlank() || !elementIds.contains(sourceId)) {
                    missingRelationshipEndpointCount++;
                    issues.add(new AdminIntegrityIssue(
                            "REL_MISSING_SOURCE",
                            "error",
                            "relationship",
                            relationshipId,
                            "Relationship sourceId is missing or unknown: " + sourceId,
                            "Delete/fix this relationship, or recreate the missing source element."));
                }
                if (targetId.isBlank() || !elementIds.contains(targetId)) {
                    missingRelationshipEndpointCount++;
                    issues.add(new AdminIntegrityIssue(
                            "REL_MISSING_TARGET",
                            "error",
                            "relationship",
                            relationshipId,
                            "Relationship targetId is missing or unknown: " + targetId,
                            "Delete/fix this relationship, or recreate the missing target element."));
                }
            }
        }

        int missingConnectionEndpointCount = 0;
        JsonNode connections = snapshot.path("connections");
        if (connections.isArray()) {
            for (JsonNode connection : connections) {
                String connectionId = connection.path("id").asText("");
                String sourceId = connection.path("sourceId").asText("");
                String targetId = connection.path("targetId").asText("");
                if (sourceId.isBlank() || !viewObjectIds.contains(sourceId)) {
                    missingConnectionEndpointCount++;
                    issues.add(new AdminIntegrityIssue(
                            "CONN_MISSING_SOURCE",
                            "error",
                            "connection",
                            connectionId,
                            "Connection sourceId is missing or unknown: " + sourceId,
                            "Delete this connection or recreate the missing source view object."));
                }
                if (targetId.isBlank() || !viewObjectIds.contains(targetId)) {
                    missingConnectionEndpointCount++;
                    issues.add(new AdminIntegrityIssue(
                            "CONN_MISSING_TARGET",
                            "error",
                            "connection",
                            connectionId,
                            "Connection targetId is missing or unknown: " + targetId,
                            "Delete this connection or recreate the missing target view object."));
                }
            }
        }

        int missingViewObjectReferenceCount = 0;
        int missingViewContainerCount = 0;
        JsonNode viewObjects = snapshot.path("viewObjects");
        if (viewObjects.isArray()) {
            for (JsonNode viewObject : viewObjects) {
                String viewObjectId = viewObject.path("id").asText("");
                String representsId = viewObject.path("representsId").asText("");
                String viewId = viewObject.path("viewId").asText("");
                if (!representsId.isBlank()
                        && !elementIds.contains(representsId)
                        && !relationshipIds.contains(representsId)) {
                    missingViewObjectReferenceCount++;
                    issues.add(new AdminIntegrityIssue(
                            "VIEWOBJ_MISSING_REPRESENTS",
                            "error",
                            "viewObject",
                            viewObjectId,
                            "ViewObject representsId is unknown: " + representsId,
                            "Delete/recreate this view object, or recreate its represented element/relationship."));
                }
                if (viewId.isBlank() || !viewIds.contains(viewId)) {
                    missingViewContainerCount++;
                    issues.add(new AdminIntegrityIssue(
                            "VIEWOBJ_MISSING_VIEW",
                            "error",
                            "viewObject",
                            viewObjectId,
                            "ViewObject viewId is missing or unknown: " + viewId,
                            "Delete this orphan view object, or recreate the missing view."));
                }
            }
        }

        return new AdminIntegrityReport(
                modelId,
                issues.isEmpty(),
                issues.size(),
                missingRelationshipEndpointCount,
                missingConnectionEndpointCount,
                missingViewObjectReferenceCount,
                missingViewContainerCount,
                issues);
    }

    private Set<String> collectIds(JsonNode arrayNode, String idField) {
        Set<String> ids = new HashSet<>();
        if (arrayNode == null || !arrayNode.isArray()) {
            return ids;
        }
        for (JsonNode node : arrayNode) {
            String id = node.path(idField).asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<AdminActivityEvent> getRecentActivity(String modelId, int limit) {
        Deque<AdminActivityEvent> queue = recentActivityByModel.get(modelId);
        if (queue == null || queue.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<AdminActivityEvent> events = new ArrayList<>(Math.min(limit, queue.size()));
        int skipped = Math.max(0, queue.size() - limit);
        int index = 0;
        for (AdminActivityEvent event : queue) {
            if (index++ < skipped) {
                continue;
            }
            events.add(event);
        }
        return events;
    }

    private JsonNode getRecentOpBatches(String modelId, int limit) {
        if (limit <= 0) {
            return JsonNodeFactory.instance.arrayNode();
        }
        long latestCommitRevision = neo4jRepository.readLatestCommitRevision(modelId);
        if (latestCommitRevision <= 0) {
            return JsonNodeFactory.instance.arrayNode();
        }
        long fromRevision = Math.max(1L, latestCommitRevision - limit + 1L);
        return neo4jRepository.loadOpBatches(modelId, fromRevision, latestCommitRevision);
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_WINDOW_LIMIT;
        }
        return Math.max(1, Math.min(limit, 200));
    }

    private String normalizeModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required");
        }
        return modelId.trim();
    }

    private String normalizeTagName(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            throw new IllegalArgumentException("tagName is required");
        }
        String normalized = tagName.trim();
        if (HEAD_REF.equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("tagName HEAD is reserved");
        }
        return normalized;
    }

    private String requireText(JsonNode node, String fieldName) {
        String value = node == null ? null : node.path(fieldName).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Import payload is missing required field: " + fieldName);
        }
        return value;
    }

    private void validateImportPayload(AdminModelExport exportPackage) {
        JsonNode opBatches = exportPackage.opBatches();
        boolean hasOpBatches = opBatches != null && opBatches.isArray() && !opBatches.isEmpty();
        if (hasOpBatches) {
            return;
        }
        boolean hasSnapshotContent = snapshotHasMaterializedContent(exportPackage.snapshot());
        boolean hasHistoricalTags = exportPackage.tags() != null && exportPackage.tags().stream()
                .filter(Objects::nonNull)
                .anyMatch(tag -> tag.revision() > 0L);
        if (exportPackage.model().headRevision() > 0L || hasSnapshotContent || hasHistoricalTags) {
            throw new IllegalArgumentException("Import payload is missing op-log history for a non-empty model.");
        }
    }

    private boolean snapshotHasMaterializedContent(JsonNode snapshot) {
        if (snapshot == null || snapshot.isMissingNode() || snapshot.isNull()) {
            return false;
        }
        return countArray(snapshot, "elements") > 0
                || countArray(snapshot, "relationships") > 0
                || countArray(snapshot, "views") > 0
                || countArray(snapshot, "viewObjects") > 0
                || countArray(snapshot, "viewObjectChildMembers") > 0
                || countArray(snapshot, "connections") > 0;
    }

    private String normalizeRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return HEAD_REF;
        }
        String normalized = ref.trim();
        return normalized.isBlank() ? HEAD_REF : normalized;
    }

    private long resolvedHeadRevision(String modelId, JsonNode snapshot) {
        long snapshotHead = snapshot == null ? 0L : snapshot.path("headRevision").asLong(0L);
        long inMemoryHead = revisionService.headRevision(modelId);
        long persistedHead = neo4jRepository.readHeadRevision(modelId);
        long latestCommitHead = neo4jRepository.readLatestCommitRevision(modelId);
        return Math.max(Math.max(snapshotHead, inMemoryHead), Math.max(persistedHead, latestCommitHead));
    }

    private boolean ensureRegisteredModelForJoin(String modelId, Session session) {
        if (isRegisteredModel(modelId)) {
            return true;
        }
        LOG.warn("Join rejected for unknown modelId={}", modelId);
        sendError(session, modelId, "MODEL_NOT_FOUND",
                "modelId " + modelId + " is not registered; create it via admin first");
        return false;
    }

    private boolean ensureRegisteredModel(String modelId, Session session, String operation) {
        if (isRegisteredModel(modelId)) {
            return true;
        }
        LOG.warn("{} rejected for unknown modelId={}", operation, modelId);
        sendError(session, modelId, "MODEL_NOT_FOUND",
                "modelId " + modelId + " is not registered; create it via admin first");
        return false;
    }

    private void ensureRegisteredModelForAdmin(String modelId) {
        if (!isRegisteredModel(modelId)) {
            throw new IllegalArgumentException("modelId " + modelId + " is not registered");
        }
    }

    private boolean isRegisteredModel(String modelId) {
        if (registeredModelCache.contains(modelId)) {
            return true;
        }
        if (findModelAccessControl(modelId).isPresent()) {
            return true;
        }
        boolean registered = neo4jRepository.modelRegistered(modelId);
        if (registered) {
            registeredModelCache.add(modelId);
        }
        return registered;
    }

    private boolean ensureWritableRef(String modelId, Session session, Actor actor, String operation) {
        JoinedModelRef joinedRef = joinedRef(session, actor);
        if (joinedRef == null || joinedRef.writable()) {
            return true;
        }
        LOG.warn("{} rejected for read-only ref: modelId={} ref={}", operation, modelId, joinedRef.ref());
        sendError(session, modelId, "MODEL_REFERENCE_READ_ONLY",
                "modelId " + modelId + " reference " + joinedRef.ref() + " is read-only");
        return false;
    }

    private ResolvedModelRef resolveModelRef(String modelId, String ref) {
        String normalizedRef = normalizeRef(ref);
        if (HEAD_REF.equalsIgnoreCase(normalizedRef)) {
            JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
            long revision = resolvedHeadRevision(modelId, snapshot);
            return new ResolvedModelRef(HEAD_REF, revision, true, snapshot);
        }
        Optional<ModelTagEntry> tag = neo4jRepository.readModelTag(modelId, normalizedRef);
        if (tag.isEmpty()) {
            return null;
        }
        JsonNode snapshot = neo4jRepository.loadTaggedSnapshot(modelId, normalizedRef);
        return new ResolvedModelRef(tag.get().tagName(), tag.get().revision(), false, snapshot);
    }

    private void rememberJoinedRef(Session session, Actor actor, ResolvedModelRef resolvedRef) {
        String websocketSessionId = sessionId(session);
        if (websocketSessionId != null) {
            joinedRefsBySessionKey.put(websocketSessionId, new JoinedModelRef(resolvedRef.ref(), resolvedRef.writable()));
        }
        String actorSessionId = actor != null ? actor.sessionId() : null;
        if (actorSessionId != null && !actorSessionId.isBlank()) {
            joinedRefsBySessionKey.put(actorSessionId, new JoinedModelRef(resolvedRef.ref(), resolvedRef.writable()));
            if (websocketSessionId != null) {
                actorSessionIdByWebsocketSessionId.put(websocketSessionId, actorSessionId);
            }
        }
    }

    private void forgetJoinedRef(Session session) {
        String websocketSessionId = sessionId(session);
        if (websocketSessionId == null) {
            return;
        }
        joinedRefsBySessionKey.remove(websocketSessionId);
        String actorSessionId = actorSessionIdByWebsocketSessionId.remove(websocketSessionId);
        if (actorSessionId != null) {
            joinedRefsBySessionKey.remove(actorSessionId);
        }
    }

    private JoinedModelRef joinedRef(Session session, Actor actor) {
        String websocketSessionId = sessionId(session);
        if (websocketSessionId != null) {
            JoinedModelRef ref = joinedRefsBySessionKey.get(websocketSessionId);
            if (ref != null) {
                return ref;
            }
        }
        String actorSessionId = actor != null ? actor.sessionId() : null;
        if (actorSessionId == null || actorSessionId.isBlank()) {
            return null;
        }
        return joinedRefsBySessionKey.get(actorSessionId);
    }

    private void sendError(Session session, String modelId, String code, String message) {
        ServerEnvelope envelope = new ServerEnvelope("Error", new ErrorMessage(code, message));
        if (session != null) {
            sessionRegistry.send(session, envelope);
            return;
        }
        sessionRegistry.broadcast(modelId, envelope);
    }

    private MutableStyleCounters styleCounters(String modelId) {
        return styleCountersByModel.computeIfAbsent(modelId, key -> new MutableStyleCounters());
    }

    private AdminStyleCounters styleCountersSnapshot(String modelId) {
        MutableStyleCounters counters = styleCountersByModel.get(modelId);
        if (counters == null) {
            return new AdminStyleCounters(0, 0, 0, 0);
        }
        return new AdminStyleCounters(
                counters.received.get(),
                counters.accepted.get(),
                counters.rejected.get(),
                counters.applied.get());
    }

    private int countStyleOps(JsonNode ops) {
        if (ops == null || !ops.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            if (!"UpdateViewObjectOpaque".equals(type) && !"UpdateConnectionOpaque".equals(type)) {
                continue;
            }
            JsonNode notation = op.path("notationJson");
            if (NotationMetadata.containsStyleActivityField(notation)) {
                count++;
            }
        }
        return count;
    }

    private String safeSessionId(Session session) {
        return session == null ? "n/a" : session.getId();
    }

    private String sessionId(Session session) {
        return session == null ? null : session.getId();
    }

    private void recordActivity(String modelId, String type, String details) {
        Deque<AdminActivityEvent> queue = recentActivityByModel.computeIfAbsent(modelId, key -> new ConcurrentLinkedDeque<>());
        queue.addLast(new AdminActivityEvent(Instant.now().toString(), type, modelId, details));
        while (queue.size() > MAX_ACTIVITY_EVENTS_PER_MODEL) {
            queue.pollFirst();
        }
    }

    private void recordStyleActivity(String modelId, String stage, int count, String opBatchId) {
        recordActivity(modelId, "StyleOps" + stage, "count=" + count + " opBatchId=" + opBatchId);
    }

    private static final class MutableStyleCounters {
        private final AtomicLong received = new AtomicLong();
        private final AtomicLong accepted = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong applied = new AtomicLong();
    }

    private record ResolvedModelRef(String ref, long revision, boolean writable, JsonNode snapshot) {
    }

    private record JoinedModelRef(String ref, boolean writable) {
    }

    private JsonNode toJsonRange(RevisionRange range) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("from", range.from());
        node.put("to", range.to());
        return node;
    }

    private List<String> lockRequiredTargets(JsonNode ops) {
        List<String> targets = new ArrayList<>();
        if (ops == null || !ops.isArray()) {
            return targets;
        }

        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            if ("UpdateViewObjectOpaque".equals(type)) {
                String target = op.path("viewObjectId").asText(null);
                if (target != null) {
                    targets.add(target);
                }
            } else if ("UpdateConnectionOpaque".equals(type)) {
                String target = op.path("connectionId").asText(null);
                if (target != null) {
                    targets.add(target);
                }
            }
        }

        return targets;
    }

    private JsonNode prepareOpsForSubmission(String modelId, JsonNode ops) {
        // Bootstrap normalization runs before cascade expansion so generated ops inherit corrected references
        JsonNode normalized = normalizeViewRefsForBootstrap(modelId, ops);
        return expandDeleteCascades(modelId, normalized);
    }

    private JsonNode normalizeViewRefsForBootstrap(String modelId, JsonNode ops) {
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            return ops;
        }

        ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
        Set<String> explicitCreateViews = new LinkedHashSet<>();
        Set<String> ensureCreateViews = new LinkedHashSet<>();
        boolean changed = false;
        int remapCount = 0;

        String soleViewId = null;
        Integer viewCount = null;

        for (JsonNode opNode : ops) {
            ObjectNode op = opNode.deepCopy();
            String type = op.path("type").asText("");

            if ("CreateView".equals(type)) {
                String createViewId = op.path("view").path("id").asText(null);
                if (createViewId != null && !createViewId.isBlank()) {
                    explicitCreateViews.add(createViewId);
                }
            }

            String currentViewId = extractViewId(op);
            if (currentViewId != null && !currentViewId.isBlank() && !neo4jRepository.viewExists(modelId, currentViewId)) {
                if (viewCount == null) {
                    // Snapshot probe is lazy to avoid repeated repository reads per op
                    JsonNode snapshot = neo4jRepository.loadSnapshot(modelId);
                    JsonNode views = snapshot.path("views");
                    viewCount = views.isArray() ? views.size() : 0;
                    if (viewCount == 1) {
                        soleViewId = views.get(0).path("id").asText(null);
                    }
                }

                if (viewCount != null && viewCount == 1 && soleViewId != null && !soleViewId.isBlank()) {
                    // Single-view bootstrap convenience: remap dangling references to the only known view
                    setViewId(op, soleViewId);
                    changed = true;
                    remapCount++;
                    recordActivity(modelId, "ViewIdRemapped",
                            "type=" + type + " from=" + currentViewId + " to=" + soleViewId);
                } else if (viewCount != null && viewCount == 0) {
                    // Empty model bootstrap: seed referenced views so create ops can pass preconditions
                    ensureCreateViews.add(currentViewId);
                }
            }

            normalized.add(op);
        }

        ensureCreateViews.removeAll(explicitCreateViews);
        if (!ensureCreateViews.isEmpty()) {
            ArrayNode withSeededViews = JsonNodeFactory.instance.arrayNode();
            for (String viewId : ensureCreateViews) {
                ObjectNode createView = JsonNodeFactory.instance.objectNode();
                createView.put("type", "CreateView");
                ObjectNode view = JsonNodeFactory.instance.objectNode();
                view.put("id", viewId);
                view.put("name", "Default View");
                view.set("notationJson", JsonNodeFactory.instance.objectNode());
                createView.set("view", view);
                withSeededViews.add(createView);
                recordActivity(modelId, "SeedCreateView", "viewId=" + viewId + " reason=missing-view-reference");
            }
            LOG.info("[BOOTSTRAP_VIEW_FIX] modelId={} action=seed-create-view seededCount={} remappedCount={}",
                    modelId, ensureCreateViews.size(), remapCount);
            withSeededViews.addAll(normalized);
            return withSeededViews;
        }

        if (remapCount > 0) {
            LOG.info("[BOOTSTRAP_VIEW_FIX] modelId={} action=remap-view-id remappedCount={} targetViewId={}",
                    modelId, remapCount, soleViewId);
        }

        return changed ? normalized : ops;
    }

    private String extractViewId(ObjectNode op) {
        String type = op.path("type").asText("");
        return switch (type) {
            case "CreateViewObject" -> op.path("viewObject").path("viewId").asText(null);
            case "CreateConnection" -> op.path("connection").path("viewId").asText(null);
            case "UpdateView", "DeleteView", "UpdateViewObjectOpaque", "DeleteViewObject", "UpdateConnectionOpaque",
                 "DeleteConnection" -> op.path("viewId").asText(null);
            default -> op.path("viewId").asText(null);
        };
    }

    private void setViewId(ObjectNode op, String viewId) {
        String type = op.path("type").asText("");
        switch (type) {
            case "CreateViewObject" -> {
                ObjectNode viewObject = op.with("viewObject");
                viewObject.put("viewId", viewId);
            }
            case "CreateConnection" -> {
                ObjectNode connection = op.with("connection");
                connection.put("viewId", viewId);
            }
            default -> op.put("viewId", viewId);
        }
    }

    private JsonNode expandDeleteCascades(String modelId, JsonNode ops) {
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            return ops;
        }

        Set<String> deletedElements = new HashSet<>();
        Set<String> deletedRelationships = new HashSet<>();
        Set<String> deletedViewObjects = new HashSet<>();
        Set<String> deletedConnections = new HashSet<>();
        for (JsonNode op : ops) {
            collectExplicitDeleteIds(op, deletedElements, deletedRelationships, deletedViewObjects, deletedConnections);
        }

        ArrayNode expanded = JsonNodeFactory.instance.arrayNode();
        boolean changed = false;
        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            switch (type) {
                case "DeleteElement" -> {
                    String elementId = op.path("elementId").asText(null);
                    if (elementId != null && !elementId.isBlank()) {
                        changed |= enqueueDeleteElementCascade(
                                modelId, elementId, expanded,
                                deletedRelationships, deletedViewObjects, deletedConnections);
                    }
                }
                case "DeleteRelationship" -> {
                    String relationshipId = op.path("relationshipId").asText(null);
                    if (relationshipId != null && !relationshipId.isBlank()) {
                        changed |= enqueueDeleteRelationshipCascade(
                                modelId, relationshipId, expanded, deletedViewObjects, deletedConnections);
                    }
                }
                case "DeleteViewObject" -> {
                    String viewObjectId = op.path("viewObjectId").asText(null);
                    if (viewObjectId != null && !viewObjectId.isBlank()) {
                        for (String connectionId : neo4jRepository.findConnectionIdsByViewObject(modelId, viewObjectId)) {
                            if (connectionId != null && !connectionId.isBlank() && deletedConnections.add(connectionId)) {
                                expanded.add(newDeleteConnectionOp(connectionId, "CascadeDelete"));
                                changed = true;
                            }
                        }
                    }
                }
                default -> {
                    // no-op
                }
            }
            expanded.add(op);
        }

        return changed ? expanded : ops;
    }

    private boolean enqueueDeleteElementCascade(String modelId,
                                                String elementId,
                                                ArrayNode expanded,
                                                Set<String> deletedRelationships,
                                                Set<String> deletedViewObjects,
                                                Set<String> deletedConnections) {
        boolean changed = false;

        for (String relationshipId : neo4jRepository.findRelationshipIdsByElement(modelId, elementId)) {
            if (relationshipId == null || relationshipId.isBlank()) {
                continue;
            }
            changed |= enqueueDeleteRelationshipCascade(
                    modelId, relationshipId, expanded, deletedViewObjects, deletedConnections);
            if (deletedRelationships.add(relationshipId)) {
                expanded.add(newDeleteRelationshipOp(relationshipId, "CascadeDelete"));
                changed = true;
            }
        }

        for (String viewObjectId : neo4jRepository.findViewObjectIdsByRepresents(modelId, elementId)) {
            if (viewObjectId == null || viewObjectId.isBlank()) {
                continue;
            }
            for (String connectionId : neo4jRepository.findConnectionIdsByViewObject(modelId, viewObjectId)) {
                if (connectionId != null && !connectionId.isBlank() && deletedConnections.add(connectionId)) {
                    expanded.add(newDeleteConnectionOp(connectionId, "CascadeDelete"));
                    changed = true;
                }
            }
            if (deletedViewObjects.add(viewObjectId)) {
                expanded.add(newDeleteViewObjectOp(viewObjectId, "CascadeDelete"));
                changed = true;
            }
        }

        return changed;
    }

    private boolean enqueueDeleteRelationshipCascade(String modelId,
                                                     String relationshipId,
                                                     ArrayNode expanded,
                                                     Set<String> deletedViewObjects,
                                                     Set<String> deletedConnections) {
        boolean changed = false;
        for (String connectionId : neo4jRepository.findConnectionIdsByRelationship(modelId, relationshipId)) {
            if (connectionId != null && !connectionId.isBlank() && deletedConnections.add(connectionId)) {
                expanded.add(newDeleteConnectionOp(connectionId, "CascadeDelete"));
                changed = true;
            }
        }

        for (String viewObjectId : neo4jRepository.findViewObjectIdsByRepresents(modelId, relationshipId)) {
            if (viewObjectId == null || viewObjectId.isBlank()) {
                continue;
            }
            for (String connectionId : neo4jRepository.findConnectionIdsByViewObject(modelId, viewObjectId)) {
                if (connectionId != null && !connectionId.isBlank() && deletedConnections.add(connectionId)) {
                    expanded.add(newDeleteConnectionOp(connectionId, "CascadeDelete"));
                    changed = true;
                }
            }
            if (deletedViewObjects.add(viewObjectId)) {
                expanded.add(newDeleteViewObjectOp(viewObjectId, "CascadeDelete"));
                changed = true;
            }
        }
        return changed;
    }

    private void collectExplicitDeleteIds(JsonNode op,
                                          Set<String> deletedElements,
                                          Set<String> deletedRelationships,
                                          Set<String> deletedViewObjects,
                                          Set<String> deletedConnections) {
        if (op == null || !op.isObject()) {
            return;
        }
        String type = op.path("type").asText("");
        switch (type) {
            case "DeleteElement" -> addIfPresent(deletedElements, op.path("elementId").asText(null));
            case "DeleteRelationship" -> addIfPresent(deletedRelationships, op.path("relationshipId").asText(null));
            case "DeleteViewObject" -> addIfPresent(deletedViewObjects, op.path("viewObjectId").asText(null));
            case "DeleteConnection" -> addIfPresent(deletedConnections, op.path("connectionId").asText(null));
            default -> {
                // no-op
            }
        }
    }

    private ObjectNode newDeleteRelationshipOp(String relationshipId, String generatedBy) {
        ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("type", "DeleteRelationship");
        op.put("relationshipId", relationshipId);
        if (generatedBy != null && !generatedBy.isBlank()) {
            op.put("generatedBy", generatedBy);
        }
        return op;
    }

    private ObjectNode newDeleteViewObjectOp(String viewObjectId, String generatedBy) {
        ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("type", "DeleteViewObject");
        op.put("viewObjectId", viewObjectId);
        if (generatedBy != null && !generatedBy.isBlank()) {
            op.put("generatedBy", generatedBy);
        }
        return op;
    }

    private ObjectNode newDeleteConnectionOp(String connectionId, String generatedBy) {
        ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("type", "DeleteConnection");
        op.put("connectionId", connectionId);
        if (generatedBy != null && !generatedBy.isBlank()) {
            op.put("generatedBy", generatedBy);
        }
        return op;
    }

    private String summarizeOps(JsonNode ops) {
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode op : ops) {
            String type = op.path("type").asText("?");
            String id = firstNonBlank(
                    op.path("elementId").asText(null),
                    op.path("relationshipId").asText(null),
                    op.path("viewId").asText(null),
                    op.path("viewObjectId").asText(null),
                    op.path("connectionId").asText(null),
                    op.path("targetId").asText(null));
            String name = extractName(op);
            StringBuilder part = new StringBuilder(type);
            if (id != null) {
                part.append("(").append(id);
                if (name != null) {
                    part.append(", name=").append(name);
                }
                part.append(")");
            } else if (name != null) {
                part.append("(name=").append(name).append(")");
            }
            parts.add(part.toString());
        }
        return parts.toString();
    }

    private String extractName(JsonNode op) {
        if (op == null || op.isMissingNode()) {
            return null;
        }
        JsonNode element = op.path("element");
        if (element.isObject()) {
            String name = element.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        JsonNode relationship = op.path("relationship");
        if (relationship.isObject()) {
            String name = relationship.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        JsonNode view = op.path("view");
        if (view.isObject()) {
            String name = view.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        JsonNode patch = op.path("patch");
        if (patch.isObject()) {
            String name = patch.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Optional<String> validatePreconditions(String modelId, JsonNode ops) {
        if (ops == null || !ops.isArray()) {
            return Optional.empty();
        }

        Set<String> createdElementIds = new HashSet<>();
        Set<String> createdRelationshipIds = new HashSet<>();
        Set<String> createdViewIds = new HashSet<>();
        Set<String> createdViewObjectIds = new HashSet<>();
        Set<String> createdConnectionIds = new HashSet<>();

        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            switch (type) {
                case "CreateElement" -> addIfPresent(createdElementIds, op.path("element").path("id").asText(null));
                case "CreateRelationship" ->
                        addIfPresent(createdRelationshipIds, op.path("relationship").path("id").asText(null));
                case "CreateView" -> addIfPresent(createdViewIds, op.path("view").path("id").asText(null));
                case "CreateViewObject" ->
                        addIfPresent(createdViewObjectIds, op.path("viewObject").path("id").asText(null));
                case "CreateConnection" ->
                        addIfPresent(createdConnectionIds, op.path("connection").path("id").asText(null));
                default -> {
                    // no-op
                }
            }
        }

        // Second pass validates references against persisted state plus entities created in this same batch
        for (JsonNode op : ops) {
            String type = op.path("type").asText("");
            switch (type) {
                case "UpdateElement", "DeleteElement" -> {
                    String elementId = op.path("elementId").asText(null);
                    if (elementId == null || elementId.isBlank()
                            || !(createdElementIds.contains(elementId) || neo4jRepository.elementExists(modelId, elementId))) {
                        return Optional.of(type + " requires existing elementId: " + elementId);
                    }
                }
                case "CreateRelationship" -> {
                    JsonNode relationship = op.path("relationship");
                    String sourceId = relationship.path("sourceId").asText(null);
                    String targetId = relationship.path("targetId").asText(null);
                    if (sourceId == null || sourceId.isBlank()
                            || !(createdElementIds.contains(sourceId) || neo4jRepository.elementExists(modelId, sourceId))) {
                        return Optional.of("CreateRelationship requires existing sourceId: " + sourceId);
                    }
                    if (targetId == null || targetId.isBlank()
                            || !(createdElementIds.contains(targetId) || neo4jRepository.elementExists(modelId, targetId))) {
                        return Optional.of("CreateRelationship requires existing targetId: " + targetId);
                    }
                }
                case "UpdateRelationship", "DeleteRelationship" -> {
                    String relationshipId = op.path("relationshipId").asText(null);
                    if (relationshipId == null || relationshipId.isBlank()
                            || !(createdRelationshipIds.contains(relationshipId) || neo4jRepository.relationshipExists(modelId, relationshipId))) {
                        return Optional.of(type + " requires existing relationshipId: " + relationshipId);
                    }
                    if ("UpdateRelationship".equals(type)) {
                        JsonNode patch = op.path("patch");
                        if (patch.has("sourceId")) {
                            String sourceId = patch.path("sourceId").asText(null);
                            if (sourceId == null || sourceId.isBlank()
                                    || !(createdElementIds.contains(sourceId) || neo4jRepository.elementExists(modelId, sourceId))) {
                                return Optional.of("UpdateRelationship sourceId does not exist: " + sourceId);
                            }
                        }
                        if (patch.has("targetId")) {
                            String targetId = patch.path("targetId").asText(null);
                            if (targetId == null || targetId.isBlank()
                                    || !(createdElementIds.contains(targetId) || neo4jRepository.elementExists(modelId, targetId))) {
                                return Optional.of("UpdateRelationship targetId does not exist: " + targetId);
                            }
                        }
                    }
                }
                case "UpdateView", "DeleteView" -> {
                    String viewId = op.path("viewId").asText(null);
                    if (viewId == null || viewId.isBlank()
                            || !(createdViewIds.contains(viewId) || neo4jRepository.viewExists(modelId, viewId))) {
                        return Optional.of(type + " requires existing viewId: " + viewId);
                    }
                }
                case "CreateViewObject" -> {
                    JsonNode viewObject = op.path("viewObject");
                    String viewId = viewObject.path("viewId").asText(null);
                    String representsId = viewObject.path("representsId").asText(null);
                    if (viewId == null || viewId.isBlank()
                            || !(createdViewIds.contains(viewId) || neo4jRepository.viewExists(modelId, viewId))) {
                        return Optional.of("CreateViewObject requires existing viewId: " + viewId);
                    }
                    if (representsId == null || representsId.isBlank()
                            || !(createdElementIds.contains(representsId) || neo4jRepository.elementExists(modelId, representsId))) {
                        return Optional.of("CreateViewObject requires existing representsId: " + representsId);
                    }
                    Optional<String> invalidNotation = validateNotationKeys(
                            "CreateViewObject",
                            viewObject.path("notationJson"),
                            NotationMetadata.VIEW_OBJECT_FIELDS);
                    if (invalidNotation.isPresent()) {
                        return invalidNotation;
                    }
                }
                case "UpdateViewObjectOpaque", "DeleteViewObject" -> {
                    String viewObjectId = op.path("viewObjectId").asText(null);
                    if (viewObjectId == null || viewObjectId.isBlank()
                            || !(createdViewObjectIds.contains(viewObjectId) || neo4jRepository.viewObjectExists(modelId, viewObjectId))) {
                        return Optional.of(type + " requires existing viewObjectId: " + viewObjectId);
                    }
                    if ("UpdateViewObjectOpaque".equals(type)) {
                        Optional<String> invalidNotation = validateNotationKeys(
                                type,
                                op.path("notationJson"),
                                NotationMetadata.VIEW_OBJECT_FIELDS);
                        if (invalidNotation.isPresent()) {
                            return invalidNotation;
                        }
                    }
                }
                case "CreateConnection" -> {
                    JsonNode connection = op.path("connection");
                    String viewId = connection.path("viewId").asText(null);
                    String representsId = connection.path("representsId").asText(null);
                    String sourceId = connection.path("sourceViewObjectId").asText(null);
                    String targetId = connection.path("targetViewObjectId").asText(null);
                    if (viewId == null || viewId.isBlank()
                            || !(createdViewIds.contains(viewId) || neo4jRepository.viewExists(modelId, viewId))) {
                        return Optional.of("CreateConnection requires existing viewId: " + viewId);
                    }
                    if (representsId == null || representsId.isBlank()
                            || !(createdRelationshipIds.contains(representsId) || neo4jRepository.relationshipExists(modelId, representsId))) {
                        return Optional.of("CreateConnection requires existing representsId: " + representsId);
                    }
                    if (sourceId == null || sourceId.isBlank()
                            || !(createdViewObjectIds.contains(sourceId) || neo4jRepository.viewObjectExists(modelId, sourceId))) {
                        return Optional.of("CreateConnection requires existing sourceViewObjectId: " + sourceId);
                    }
                    if (targetId == null || targetId.isBlank()
                            || !(createdViewObjectIds.contains(targetId) || neo4jRepository.viewObjectExists(modelId, targetId))) {
                        return Optional.of("CreateConnection requires existing targetViewObjectId: " + targetId);
                    }
                    Optional<String> invalidNotation = validateNotationKeys(
                            "CreateConnection",
                            connection.path("notationJson"),
                            NotationMetadata.CONNECTION_FIELDS);
                    if (invalidNotation.isPresent()) {
                        return invalidNotation;
                    }
                }
                case "UpdateConnectionOpaque", "DeleteConnection" -> {
                    String connectionId = op.path("connectionId").asText(null);
                    if (connectionId == null || connectionId.isBlank()
                            || !(createdConnectionIds.contains(connectionId) || neo4jRepository.connectionExists(modelId, connectionId))) {
                        return Optional.of(type + " requires existing connectionId: " + connectionId);
                    }
                    if ("UpdateConnectionOpaque".equals(type)) {
                        Optional<String> invalidNotation = validateNotationKeys(
                                type,
                                op.path("notationJson"),
                                NotationMetadata.CONNECTION_FIELDS);
                        if (invalidNotation.isPresent()) {
                            return invalidNotation;
                        }
                    }
                }
                default -> {
                    // no-op
                }
            }
        }

        return Optional.empty();
    }

    private Optional<String> validateNotationKeys(String opType, JsonNode notationJson, Set<String> allowedKeys) {
        if (notationJson == null || notationJson.isMissingNode() || notationJson.isNull()) {
            return Optional.empty();
        }
        if (!notationJson.isObject()) {
            return Optional.of(opType + " notationJson must be an object");
        }

        Set<String> unknownKeys = new TreeSet<>();
        notationJson.fieldNames().forEachRemaining(field -> {
            if (!allowedKeys.contains(field)) {
                unknownKeys.add(field);
            }
        });
        if (unknownKeys.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(opType + " notationJson has unsupported field(s): " + String.join(",", unknownKeys));
    }

    private void addIfPresent(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value);
        }
    }

    private void runConsistencyChecksIfEnabled(String modelId, String opBatchId, long assignedHeadRevision) {
        if (!consistencyChecksEnabled) {
            return;
        }
        long latestCommitRevision = neo4jRepository.readLatestCommitRevision(modelId);
        boolean consistent = neo4jRepository.isMaterializedStateConsistent(modelId, assignedHeadRevision);
        if (latestCommitRevision != assignedHeadRevision || !consistent) {
            LOG.error("Consistency check failed: modelId={} opBatchId={} expectedHead={} latestCommitRevision={} materializedConsistent={}",
                    modelId, opBatchId, assignedHeadRevision, latestCommitRevision, consistent);
        } else {
            LOG.debug("Consistency check passed: modelId={} opBatchId={} headRevision={}",
                    modelId, opBatchId, assignedHeadRevision);
        }
    }

    private JsonNode normalizeOpsWithCausal(JsonNode ops, Actor actor, String opBatchId, long assignedFromRevision) {
        if (ops == null || !ops.isArray()) {
            return JsonNodeFactory.instance.arrayNode();
        }

        String actorClientId = firstNonBlank(actor.sessionId(), actor.userId(), "anonymous-session");
        ArrayNode normalized = JsonNodeFactory.instance.arrayNode();
        int index = 0;
        for (JsonNode op : ops) {
            ObjectNode opNode = op != null && op.isObject() ? ((ObjectNode) op).deepCopy() : JsonNodeFactory.instance.objectNode();

            ObjectNode causalNode = opNode.path("causal").isObject()
                    ? ((ObjectNode) opNode.path("causal")).deepCopy()
                    : JsonNodeFactory.instance.objectNode();

            String incomingClientId = causalNode.path("clientId").asText(null);
            String clientId = firstNonBlank(incomingClientId, actorClientId, "anonymous-session");
            long lamport = causalNode.path("lamport").asLong(-1L);
            if (lamport < 0) {
                // Deterministic fallback when clients omit causal metadata
                lamport = assignedFromRevision + index;
            }
            String incomingOpId = causalNode.path("opId").asText(null);
            String opId = firstNonBlank(incomingOpId, opBatchId + ":" + index);

            causalNode.put("clientId", clientId);
            causalNode.put("lamport", lamport);
            causalNode.put("opId", opId);
            opNode.set("causal", causalNode);

            normalized.add(opNode);
            index++;
        }

        return normalized;
    }
}
