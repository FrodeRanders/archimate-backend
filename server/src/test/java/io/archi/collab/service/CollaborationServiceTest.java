package io.archi.collab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archi.collab.model.RevisionRange;
import io.archi.collab.service.impl.InMemoryIdempotencyService;
import io.archi.collab.service.impl.InMemoryLockService;
import io.archi.collab.service.impl.InMemoryRevisionService;
import io.archi.collab.service.impl.InMemoryValidationService;
import io.archi.collab.wire.inbound.AcquireLockMessage;
import io.archi.collab.wire.inbound.PresenceMessage;
import io.archi.collab.wire.inbound.ReleaseLockMessage;
import io.archi.collab.wire.ServerEnvelope;
import io.archi.collab.wire.inbound.JoinMessage;
import io.archi.collab.wire.inbound.SubmitOpsMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CollaborationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void submitOpsAssignsRangeAndBroadcasts() {
        CollaborationService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        SubmitOpsMessage message = new SubmitOpsMessage(0, "11111111-1111-1111-1111-111111111111", null, singleCreateElementOp());
        service.onSubmitOps("demo", message);

        Assertions.assertEquals(1, neo.appendCount);
        Assertions.assertEquals(1, neo.applyCount);
        Assertions.assertEquals(1, neo.updateHeadCount);
        Assertions.assertEquals(0, neo.readLatestCommitRevisionCount);
        Assertions.assertEquals(0, neo.isMaterializedStateConsistentCount);
        Assertions.assertEquals(1, sessions.broadcasts.size());
    }

    @Test
    void duplicateBatchIsDedupedByOpBatchId() {
        CollaborationService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        SubmitOpsMessage message = new SubmitOpsMessage(0, "22222222-2222-2222-2222-222222222222", null, singleCreateElementOp());
        service.onSubmitOps("demo", message);
        service.onSubmitOps("demo", message);

        Assertions.assertEquals(1, neo.appendCount, "dedupe should skip second persistence");
        Assertions.assertEquals(1, neo.applyCount, "dedupe should skip second materialization");
        Assertions.assertEquals(1, neo.updateHeadCount, "dedupe should skip second head update");

        long acceptedMessages = sessions.broadcasts.stream().filter(m -> "OpsAccepted".equals(m.type())).count();
        long broadcastMessages = sessions.broadcasts.stream().filter(m -> "OpsBroadcast".equals(m.type())).count();
        Assertions.assertEquals(2, acceptedMessages, "duplicate still gets acceptance echo");
        Assertions.assertEquals(0, broadcastMessages, "ops broadcast is produced by Kafka consumer fan-out");
    }

    @Test
    void lockAndPresenceArePublishedToKafkaWithoutDirectBroadcast() {
        CollaborationService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        RecordingKafkaPublisher kafka = (RecordingKafkaPublisher) service.kafkaPublisher;

        service.onAcquireLock("demo", new AcquireLockMessage(null, List.of("vo:o1"), 10_000L));
        service.onReleaseLock("demo", new ReleaseLockMessage(null, List.of("vo:o1")));
        service.onPresence("demo", new PresenceMessage(null, "view:v1", List.of("vo:o1"), null));

        Assertions.assertEquals(2, kafka.lockEventsPublished);
        Assertions.assertEquals(1, kafka.presenceEventsPublished);
        Assertions.assertEquals(0, sessions.broadcasts.size(), "fan-out is Kafka consumer responsibility");
    }

    @Test
    void joinWithoutLastSeenSendsSnapshot() {
        CollaborationService service = baseService();
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;

        service.onJoin("demo", null, new JoinMessage(null, null));

        Assertions.assertEquals(1, sessions.sends.size());
        Assertions.assertEquals("CheckoutSnapshot", sessions.sends.get(0).type());
    }

    @Test
    void preconditionFailureBroadcastsErrorAndSkipsPersistence() {
        CollaborationService service = baseService();
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;
        RecordingSessionRegistry sessions = (RecordingSessionRegistry) service.sessionRegistry;
        neo.viewExists = false;

        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode viewObject = objectMapper.createObjectNode();
        viewObject.put("id", "vo:1");
        viewObject.put("viewId", "view:missing");
        viewObject.put("representsId", "elem:e1");
        viewObject.set("notationJson", objectMapper.createObjectNode());
        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "CreateViewObject");
        op.set("viewObject", viewObject);
        ops.add(op);

        service.onSubmitOps("demo", new SubmitOpsMessage(0, "33333333-3333-3333-3333-333333333333", null, ops));

        Assertions.assertEquals(0, neo.appendCount);
        Assertions.assertFalse(sessions.broadcasts.isEmpty());
        Assertions.assertEquals("Error", sessions.broadcasts.get(0).type());
    }

    @Test
    void consistencyChecksRunWhenEnabled() {
        CollaborationService service = baseService();
        service.consistencyChecksEnabled = true;
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        SubmitOpsMessage message = new SubmitOpsMessage(0, "44444444-4444-4444-4444-444444444444", null, singleCreateElementOp());
        service.onSubmitOps("demo", message);

        Assertions.assertEquals(1, neo.readLatestCommitRevisionCount);
        Assertions.assertEquals(1, neo.isMaterializedStateConsistentCount);
    }

    @Test
    void consistencyStatusReflectsAlignmentAndConsistency() {
        CollaborationService service = baseService();
        service.revisionService = new FixedRevisionService(7);
        RecordingNeo4jRepository neo = (RecordingNeo4jRepository) service.neo4jRepository;

        neo.readHeadRevisionValue = 7;
        neo.readLatestCommitRevisionValue = 7;
        neo.materializedStateConsistent = true;

        var status = service.getConsistencyStatus("demo");
        Assertions.assertEquals("demo", status.modelId());
        Assertions.assertEquals(7, status.inMemoryHeadRevision());
        Assertions.assertEquals(7, status.persistedHeadRevision());
        Assertions.assertEquals(7, status.latestCommitRevision());
        Assertions.assertTrue(status.materializedStateConsistent());
        Assertions.assertTrue(status.headAligned());
        Assertions.assertTrue(status.commitAligned());
        Assertions.assertTrue(status.consistent());
    }

    private CollaborationService baseService() {
        CollaborationService service = new CollaborationService();
        service.validationService = new InMemoryValidationService();
        service.revisionService = new InMemoryRevisionService();
        service.lockService = new InMemoryLockService();
        service.idempotencyService = new InMemoryIdempotencyService();
        service.neo4jRepository = new RecordingNeo4jRepository();
        service.kafkaPublisher = new RecordingKafkaPublisher();
        service.sessionRegistry = new RecordingSessionRegistry();
        return service;
    }

    private JsonNode singleCreateElementOp() {
        ArrayNode ops = objectMapper.createArrayNode();
        ObjectNode element = objectMapper.createObjectNode();
        element.put("id", "elem:e1");
        element.put("archimateType", "BusinessActor");

        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "CreateElement");
        op.set("element", element);

        ops.add(op);
        return ops;
    }

    private static class RecordingNeo4jRepository implements Neo4jRepository {
        int appendCount;
        int applyCount;
        int updateHeadCount;
        int readLatestCommitRevisionCount;
        int isMaterializedStateConsistentCount;
        long readHeadRevisionValue;
        long readLatestCommitRevisionValue = 1;
        boolean materializedStateConsistent = true;
        boolean elementExists = true;
        boolean relationshipExists = true;
        boolean viewExists = true;
        boolean viewObjectExists = true;
        boolean connectionExists = true;

        @Override
        public void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
            appendCount++;
        }

        @Override
        public void applyToMaterializedState(String modelId, JsonNode opBatch) {
            applyCount++;
        }

        @Override
        public void updateHeadRevision(String modelId, long headRevision) {
            updateHeadCount++;
        }

        @Override
        public long readHeadRevision(String modelId) {
            return readHeadRevisionValue;
        }

        @Override
        public long readLatestCommitRevision(String modelId) {
            readLatestCommitRevisionCount++;
            return readLatestCommitRevisionValue;
        }

        @Override
        public JsonNode loadSnapshot(String modelId) {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive) {
            return JsonNodeFactory.instance.arrayNode();
        }

        @Override
        public boolean isMaterializedStateConsistent(String modelId, long expectedHeadRevision) {
            isMaterializedStateConsistentCount++;
            return materializedStateConsistent;
        }

        @Override
        public boolean elementExists(String modelId, String elementId) {
            return elementExists;
        }

        @Override
        public boolean relationshipExists(String modelId, String relationshipId) {
            return relationshipExists;
        }

        @Override
        public boolean viewExists(String modelId, String viewId) {
            return viewExists;
        }

        @Override
        public boolean viewObjectExists(String modelId, String viewObjectId) {
            return viewObjectExists;
        }

        @Override
        public boolean connectionExists(String modelId, String connectionId) {
            return connectionExists;
        }
    }

    private static class RecordingKafkaPublisher implements KafkaPublisher {
        int opsPublished;
        int lockEventsPublished;
        int presenceEventsPublished;

        @Override
        public void publishOps(String modelId, JsonNode opBatch) {
            opsPublished++;
        }

        @Override
        public void publishLockEvent(String modelId, Object lockEvent) {
            lockEventsPublished++;
        }

        @Override
        public void publishPresence(String modelId, Object presenceEvent) {
            presenceEventsPublished++;
        }
    }

    private static class RecordingSessionRegistry implements SessionRegistry {
        final List<ServerEnvelope> broadcasts = new ArrayList<>();
        final List<ServerEnvelope> sends = new ArrayList<>();

        @Override
        public void register(String modelId, jakarta.websocket.Session session) {
        }

        @Override
        public void unregister(String modelId, jakarta.websocket.Session session) {
        }

        @Override
        public void send(jakarta.websocket.Session session, ServerEnvelope message) {
            sends.add(message);
        }

        @Override
        public void broadcast(String modelId, ServerEnvelope message) {
            broadcasts.add(message);
        }
    }

    private static class FixedRevisionService implements RevisionService {
        private final long headRevision;

        private FixedRevisionService(long headRevision) {
            this.headRevision = headRevision;
        }

        @Override
        public RevisionRange assignRange(String modelId, int opCount) {
            long from = headRevision + 1;
            long to = from + Math.max(0, opCount - 1L);
            return new RevisionRange(from, to);
        }

        @Override
        public long headRevision(String modelId) {
            return headRevision;
        }
    }
}
