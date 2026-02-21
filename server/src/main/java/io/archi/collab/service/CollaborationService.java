package io.archi.collab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archi.collab.model.Actor;
import io.archi.collab.model.RevisionRange;
import io.archi.collab.wire.ServerEnvelope;
import io.archi.collab.wire.inbound.AcquireLockMessage;
import io.archi.collab.wire.inbound.JoinMessage;
import io.archi.collab.wire.inbound.PresenceMessage;
import io.archi.collab.wire.inbound.ReleaseLockMessage;
import io.archi.collab.wire.inbound.SubmitOpsMessage;
import io.archi.collab.wire.outbound.CheckoutDeltaMessage;
import io.archi.collab.wire.outbound.ErrorMessage;
import io.archi.collab.wire.outbound.OpsAcceptedMessage;
import io.archi.collab.wire.outbound.PresenceBroadcastMessage;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.Session;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class CollaborationService {
    private static final Logger LOG = LoggerFactory.getLogger(CollaborationService.class);
    private static final long DEFAULT_LOCK_TTL_MS = 10_000;

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

    @PostConstruct
    void init() {
        kafkaConsumer.start();
        LOG.info("Collaboration service initialized; kafka consumer started");
    }

    public void onJoin(String modelId, Session session, JoinMessage join) {
        sessionRegistry.register(modelId, session);
        long head = revisionService.headRevision(modelId);
        Long lastSeen = join != null ? join.lastSeenRevision() : null;
        LOG.info("Join: modelId={} sessionId={} lastSeenRevision={} headRevision={}",
                modelId, session == null ? "n/a" : session.getId(), lastSeen, head);

        long safeLastSeen = lastSeen != null ? lastSeen : 0L;
        if(safeLastSeen > head) {
            LOG.warn("Join ahead-of-head detected: modelId={} sessionId={} lastSeenRevision={} headRevision={} - forcing full delta replay",
                    modelId, session == null ? "n/a" : session.getId(), safeLastSeen, head);
            JsonNode opBatches = head > 0
                    ? neo4jRepository.loadOpBatches(modelId, 1, head)
                    : JsonNodeFactory.instance.arrayNode();
            sessionRegistry.send(session, new ServerEnvelope("CheckoutDelta",
                    new CheckoutDeltaMessage(head > 0 ? 1 : 0, head, opBatches)));
            return;
        }

        if(safeLastSeen < head) {
            JsonNode opBatches = neo4jRepository.loadOpBatches(modelId, safeLastSeen + 1, head);
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
        sessionRegistry.unregister(modelId, session);
    }

    public void onSubmitOps(String modelId, SubmitOpsMessage submitOps) {
        validationService.validateSubmitOps(modelId, submitOps);
        int opCount = submitOps.ops() == null ? 0 : submitOps.ops().size();
        String opBatchId = submitOps.opBatchId();
        long baseRevision = submitOps.baseRevision();
        long headRevision = revisionService.headRevision(modelId);
        LOG.info("SubmitOps received: modelId={} opBatchId={} baseRevision={} opCount={} ops={}",
                modelId, opBatchId, baseRevision, opCount, summarizeOps(submitOps.ops()));

        if(baseRevision > headRevision) {
            LOG.warn("SubmitOps rejected: modelId={} opBatchId={} baseRevision={} exceeds headRevision={}",
                    modelId, opBatchId, baseRevision, headRevision);
            sessionRegistry.broadcast(modelId,
                    new ServerEnvelope("Error", new ErrorMessage("REVISION_AHEAD",
                            "baseRevision " + baseRevision + " exceeds server headRevision " + headRevision + "; rejoin required")));
            return;
        }

        Optional<RevisionRange> known = idempotencyService.findRange(modelId, opBatchId);
        if(known.isPresent()) {
            LOG.info("SubmitOps idempotency hit: modelId={} opBatchId={} assigned={}..{}",
                    modelId, opBatchId, known.get().from(), known.get().to());
            sessionRegistry.broadcast(modelId,
                    new ServerEnvelope("OpsAccepted", new OpsAcceptedMessage(opBatchId, baseRevision, known.get())));
            return;
        }

        Actor actor = submitOps.actor() != null ? submitOps.actor() : Actor.anonymous();
        List<String> lockTargets = lockRequiredTargets(submitOps.ops());
        if(!lockTargets.isEmpty()) {
            Optional<String> conflict = lockService.checkLockConflicts(modelId, actor, lockTargets);
            if(conflict.isPresent()) {
                LOG.warn("SubmitOps lock conflict: modelId={} opBatchId={} details={}",
                        modelId, opBatchId, conflict.get());
                sessionRegistry.broadcast(modelId,
                        new ServerEnvelope("Error", new ErrorMessage("LOCK_CONFLICT", conflict.get())));
                return;
            }
        }

        RevisionRange range = revisionService.assignRange(modelId, opCount);
        idempotencyService.remember(modelId, opBatchId, range);
        LOG.info("SubmitOps accepted: modelId={} opBatchId={} assigned={}..{}",
                modelId, opBatchId, range.from(), range.to());

        ObjectNode opBatch = JsonNodeFactory.instance.objectNode();
        opBatch.put("modelId", modelId);
        opBatch.put("opBatchId", opBatchId);
        opBatch.put("baseRevision", baseRevision);
        opBatch.set("assignedRevisionRange", toJsonRange(range));
        opBatch.put("timestamp", Instant.now().toString());
        opBatch.set("ops", submitOps.ops());

        neo4jRepository.appendOpLog(modelId, opBatchId, range, opBatch);
        neo4jRepository.applyToMaterializedState(modelId, opBatch);
        neo4jRepository.updateHeadRevision(modelId, range.to());

        kafkaPublisher.publishOps(modelId, opBatch);

        sessionRegistry.broadcast(modelId,
                new ServerEnvelope("OpsAccepted", new OpsAcceptedMessage(opBatchId, baseRevision, range)));
    }

    public void onAcquireLock(String modelId, AcquireLockMessage message) {
        Actor actor = message.actor() != null ? message.actor() : Actor.anonymous();
        List<String> targets = message.targets() != null ? message.targets() : List.of();
        long ttlMs = message.ttlMs() != null ? message.ttlMs() : DEFAULT_LOCK_TTL_MS;
        LOG.debug("AcquireLock: modelId={} actor={}/{} targetCount={} ttlMs={}",
                modelId, actor.userId(), actor.sessionId(), targets.size(), ttlMs);

        var event = lockService.acquire(modelId, actor, targets, ttlMs);
        kafkaPublisher.publishLockEvent(modelId, event);
    }

    public void onReleaseLock(String modelId, ReleaseLockMessage message) {
        Actor actor = message.actor() != null ? message.actor() : Actor.anonymous();
        List<String> targets = message.targets() != null ? message.targets() : List.of();
        LOG.debug("ReleaseLock: modelId={} actor={}/{} targetCount={}",
                modelId, actor.userId(), actor.sessionId(), targets.size());

        var event = lockService.release(modelId, actor, targets);
        kafkaPublisher.publishLockEvent(modelId, event);
    }

    public void onPresence(String modelId, PresenceMessage message) {
        Actor actor = message.actor() != null ? message.actor() : Actor.anonymous();
        LOG.debug("Presence: modelId={} actor={}/{} viewId={}",
                modelId, actor.userId(), actor.sessionId(), message.viewId());
        var event = new PresenceBroadcastMessage(actor, Instant.now(), message.viewId(), message.selection(), message.cursor());
        kafkaPublisher.publishPresence(modelId, event);
    }

    private JsonNode toJsonRange(RevisionRange range) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("from", range.from());
        node.put("to", range.to());
        return node;
    }

    private List<String> lockRequiredTargets(JsonNode ops) {
        List<String> targets = new ArrayList<>();
        if(ops == null || !ops.isArray()) {
            return targets;
        }

        for(JsonNode op : ops) {
            String type = op.path("type").asText("");
            if("UpdateViewObjectOpaque".equals(type)) {
                String target = op.path("viewObjectId").asText(null);
                if(target != null) {
                    targets.add(target);
                }
            }
            else if("UpdateConnectionOpaque".equals(type)) {
                String target = op.path("connectionId").asText(null);
                if(target != null) {
                    targets.add(target);
                }
            }
        }

        return targets;
    }

    private String summarizeOps(JsonNode ops) {
        if(ops == null || !ops.isArray() || ops.isEmpty()) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for(JsonNode op : ops) {
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
            if(id != null) {
                part.append("(").append(id);
                if(name != null) {
                    part.append(", name=").append(name);
                }
                part.append(")");
            }
            else if(name != null) {
                part.append("(name=").append(name).append(")");
            }
            parts.add(part.toString());
        }
        return parts.toString();
    }

    private String extractName(JsonNode op) {
        if(op == null || op.isMissingNode()) {
            return null;
        }
        JsonNode element = op.path("element");
        if(element.isObject()) {
            String name = element.path("name").asText(null);
            if(name != null && !name.isBlank()) {
                return name;
            }
        }
        JsonNode relationship = op.path("relationship");
        if(relationship.isObject()) {
            String name = relationship.path("name").asText(null);
            if(name != null && !name.isBlank()) {
                return name;
            }
        }
        JsonNode view = op.path("view");
        if(view.isObject()) {
            String name = view.path("name").asText(null);
            if(name != null && !name.isBlank()) {
                return name;
            }
        }
        JsonNode patch = op.path("patch");
        if(patch.isObject()) {
            String name = patch.path("name").asText(null);
            if(name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for(String value : values) {
            if(value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
