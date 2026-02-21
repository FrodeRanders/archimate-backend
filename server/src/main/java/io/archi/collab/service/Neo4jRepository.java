package io.archi.collab.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.archi.collab.model.RevisionRange;

public interface Neo4jRepository {
    void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch);

    void applyToMaterializedState(String modelId, JsonNode opBatch);

    void updateHeadRevision(String modelId, long headRevision);

    long readHeadRevision(String modelId);

    JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive);
}
