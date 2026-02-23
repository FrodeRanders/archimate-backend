package io.archi.collab.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.archi.collab.model.AdminCompactionStatus;
import io.archi.collab.model.ModelCatalogEntry;
import io.archi.collab.model.RevisionRange;
import io.archi.collab.service.Neo4jRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        public AdminCompactionStatus compactMetadata(String modelId, long retainRevisions) {
            return new AdminCompactionStatus(modelId, headRevision, headRevision, headRevision, retainRevisions,
                    0L, 0L, 0L, 0L, 0L, 0L, true, "noop");
        }

        @Override
        public void clearMaterializedState(String modelId) {
        }

        @Override
        public void deleteModel(String modelId) {
            headRevision = 0L;
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

        @Override
        public List<String> findRelationshipIdsByElement(String modelId, String elementId) {
            return List.of();
        }

        @Override
        public List<String> findViewObjectIdsByRepresents(String modelId, String representsId) {
            return List.of();
        }

        @Override
        public List<String> findConnectionIdsByViewObject(String modelId, String viewObjectId) {
            return List.of();
        }

        @Override
        public List<String> findConnectionIdsByRelationship(String modelId, String relationshipId) {
            return List.of();
        }

        @Override
        public ModelCatalogEntry registerModel(String modelId, String modelName) {
            return new ModelCatalogEntry(modelId, modelName, headRevision);
        }

        @Override
        public ModelCatalogEntry renameModel(String modelId, String modelName) {
            return new ModelCatalogEntry(modelId, modelName, headRevision);
        }

        @Override
        public String readModelName(String modelId) {
            return null;
        }

        @Override
        public List<ModelCatalogEntry> listModelCatalog() {
            return List.of();
        }
    }
}
