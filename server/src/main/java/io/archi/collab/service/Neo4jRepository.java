package io.archi.collab.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.archi.collab.model.AdminCompactionStatus;
import io.archi.collab.model.ModelAccessControl;
import io.archi.collab.model.ModelCatalogEntry;
import io.archi.collab.model.ModelTagEntry;
import io.archi.collab.model.RevisionRange;

import java.util.Optional;
import java.util.List;
import java.util.Set;

public interface Neo4jRepository {
    void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch);

    void applyToMaterializedState(String modelId, JsonNode opBatch);

    void updateHeadRevision(String modelId, long headRevision);

    long readHeadRevision(String modelId);

    long readLatestCommitRevision(String modelId);

    JsonNode loadSnapshot(String modelId);

    JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive);

    AdminCompactionStatus compactMetadata(String modelId, long retainRevisions);

    void clearMaterializedState(String modelId);

    void deleteModel(String modelId);

    boolean isMaterializedStateConsistent(String modelId, long expectedHeadRevision);

    boolean elementExists(String modelId, String elementId);

    boolean relationshipExists(String modelId, String relationshipId);

    boolean viewExists(String modelId, String viewId);

    boolean folderExists(String modelId, String folderId);

    boolean folderEmpty(String modelId, String folderId);

    boolean folderMoveCreatesCycle(String modelId, String folderId, String parentFolderId);

    boolean viewObjectExists(String modelId, String viewObjectId);

    boolean connectionExists(String modelId, String connectionId);

    List<String> findRelationshipIdsByElement(String modelId, String elementId);

    List<String> findViewObjectIdsByRepresents(String modelId, String representsId);

    List<String> findConnectionIdsByViewObject(String modelId, String viewObjectId);

    List<String> findConnectionIdsByRelationship(String modelId, String relationshipId);

    ModelCatalogEntry registerModel(String modelId, String modelName);

    ModelCatalogEntry renameModel(String modelId, String modelName);

    String readModelName(String modelId);

    boolean modelRegistered(String modelId);

    Optional<ModelAccessControl> readModelAccessControl(String modelId);

    ModelAccessControl updateModelAccessControl(String modelId, Set<String> adminUsers, Set<String> writerUsers, Set<String> readerUsers);

    ModelTagEntry createModelTag(String modelId, String tagName, String description, long revision, JsonNode snapshot);

    ModelTagEntry restoreModelTag(String modelId, String tagName, String description, long revision, String createdAt, JsonNode snapshot);

    Optional<ModelTagEntry> readModelTag(String modelId, String tagName);

    JsonNode loadTaggedSnapshot(String modelId, String tagName);

    List<ModelTagEntry> listModelTags(String modelId);

    void deleteModelTag(String modelId, String tagName);

    List<ModelCatalogEntry> listModelCatalog();
}
