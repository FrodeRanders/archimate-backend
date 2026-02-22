package io.archi.collab.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.archi.collab.model.RevisionRange;
import io.archi.collab.service.Neo4jRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InMemoryRevisionServiceTest {

    @Test
    void headRevisionReSyncsUpWhenPersistedHeadAdvances() {
        InMemoryRevisionService service = new InMemoryRevisionService();
        RecordingNeo4jRepository neo = new RecordingNeo4jRepository();
        service.neo4jRepository = neo;

        neo.headRevision = 0;
        Assertions.assertEquals(0, service.headRevision("demo"));

        neo.headRevision = 50;
        Assertions.assertEquals(50, service.headRevision("demo"));

        RevisionRange assigned = service.assignRange("demo", 1);
        Assertions.assertEquals(51, assigned.from());
        Assertions.assertEquals(51, assigned.to());
    }

    @Test
    void headRevisionNeverMovesBackwardFromPersistedHead() {
        InMemoryRevisionService service = new InMemoryRevisionService();
        RecordingNeo4jRepository neo = new RecordingNeo4jRepository();
        service.neo4jRepository = neo;

        neo.headRevision = 10;
        Assertions.assertEquals(10, service.headRevision("demo"));

        neo.headRevision = 3;
        Assertions.assertEquals(10, service.headRevision("demo"));
    }

    private static class RecordingNeo4jRepository implements Neo4jRepository {
        long headRevision;

        @Override
        public void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
        }

        @Override
        public void applyToMaterializedState(String modelId, JsonNode opBatch) {
        }

        @Override
        public void updateHeadRevision(String modelId, long headRevision) {
            this.headRevision = headRevision;
        }

        @Override
        public long readHeadRevision(String modelId) {
            return headRevision;
        }

        @Override
        public long readLatestCommitRevision(String modelId) {
            return headRevision;
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
            return true;
        }

        @Override
        public boolean elementExists(String modelId, String elementId) {
            return true;
        }

        @Override
        public boolean relationshipExists(String modelId, String relationshipId) {
            return true;
        }

        @Override
        public boolean viewExists(String modelId, String viewId) {
            return true;
        }

        @Override
        public boolean viewObjectExists(String modelId, String viewObjectId) {
            return true;
        }

        @Override
        public boolean connectionExists(String modelId, String connectionId) {
            return true;
        }
    }
}
