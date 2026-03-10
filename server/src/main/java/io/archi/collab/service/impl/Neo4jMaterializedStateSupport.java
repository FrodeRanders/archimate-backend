package io.archi.collab.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archi.collab.service.NotationMetadata;
import io.archi.collab.service.NotationMetadata.PersistedNotationField;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Neo4jMaterializedStateSupport {
    private static final Logger LOG = LoggerFactory.getLogger(Neo4jMaterializedStateSupport.class);
    private static final String ORSET_CLOCK_PREFIX = "orset:";
    private static final String VIEWOBJECT_CHILD_CLOCK_PREFIX = ORSET_CLOCK_PREFIX + "vo-child:";
    // These clauses are generated once from the shared notation metadata so view object / connection
    // persistence cannot silently drift away from validation rules.
    private static final String VIEW_OBJECT_NOTATION_RETURN = buildNotationReturnClause("vo", NotationMetadata.VIEW_OBJECT_PERSISTED_FIELDS);
    private static final String VIEW_OBJECT_NOTATION_SET = buildNotationSetClause("vo", NotationMetadata.VIEW_OBJECT_PERSISTED_FIELDS);
    private static final String CONNECTION_NOTATION_RETURN = buildNotationReturnClause("c", NotationMetadata.CONNECTION_PERSISTED_FIELDS);
    private static final String CONNECTION_NOTATION_SET = buildNotationSetClause("c", NotationMetadata.CONNECTION_PERSISTED_FIELDS);

    private final ObjectMapper objectMapper;

    Neo4jMaterializedStateSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void applyBatch(TransactionContext tx, String modelId, JsonNode opBatch) {
        long firstRevision = opBatch.path("assignedRevisionRange").path("from").asLong(-1L);
        int opIndex = 0;
        for (JsonNode op : opBatch.path("ops")) {
            // Materialized-state writes use the per-op revision so tombstones and property clocks can compare
            // the exact op that won, not just the batch that carried it.
            long opRevision = firstRevision < 0 ? -1L : firstRevision + opIndex;
            applyOp(tx, modelId, op, opRevision);
            opIndex++;
        }
    }
    private void applyOp(TransactionContext tx, String modelId, JsonNode op, long opRevision) {
        String type = op.path("type").asText("");
        switch (type) {
            case "CreateElement" -> createElement(tx, modelId, op.path("element"), op.path("causal"));
            case "UpdateElement" ->
                    updateElementWithLww(tx, modelId, op.path("elementId").asText(), op.path("patch"), op.path("causal"));
            case "DeleteElement" ->
                    deleteElementWithTombstone(tx, modelId, op.path("elementId").asText(), op.path("causal"), opRevision);
            case "CreateFolder" -> createFolder(tx, modelId, op.path("folder"), op.path("causal"));
            case "UpdateFolder" ->
                    updateFolderWithLww(tx, modelId, op.path("folderId").asText(), op.path("patch"), op.path("causal"));
            case "DeleteFolder" -> deleteFolder(tx, modelId, op.path("folderId").asText());
            case "MoveFolder" ->
                    moveFolder(tx, modelId, op.path("folderId").asText(null), op.path("parentFolderId").asText(null), op.path("causal"));
            case "MoveElementToFolder" ->
                    moveToFolder(tx, modelId, op.path("folderId").asText(null), op.path("elementId").asText(null), "Element", "CONTAINS_ELEMENT");
            case "MoveRelationshipToFolder" ->
                    moveToFolder(tx, modelId, op.path("folderId").asText(null), op.path("relationshipId").asText(null), "Relationship", "CONTAINS_REL");
            case "MoveViewToFolder" ->
                    moveToFolder(tx, modelId, op.path("folderId").asText(null), op.path("viewId").asText(null), "View", "CONTAINS_VIEW");
            case "CreateRelationship" -> createRelationship(tx, modelId, op.path("relationship"), op.path("causal"));
            case "UpdateRelationship" ->
                    updateRelationshipWithLww(tx, modelId, op.path("relationshipId").asText(), op.path("patch"), op.path("causal"));
            case "DeleteRelationship" ->
                    deleteRelationshipWithTombstone(tx, modelId, op.path("relationshipId").asText(), op.path("causal"), opRevision);
            case "SetProperty" ->
                    setPropertyWithLww(tx, modelId, op.path("targetId").asText(), op.path("key").asText(), op.path("value"), op.path("causal"));
            case "UnsetProperty" ->
                    unsetPropertyWithLww(tx, modelId, op.path("targetId").asText(), op.path("key").asText(), op.path("causal"));
            case "AddPropertySetMember" ->
                    addPropertySetMember(tx, modelId, op.path("targetId").asText(), op.path("key").asText(), op.path("member").asText(null), op.path("causal"));
            case "RemovePropertySetMember" ->
                    removePropertySetMember(tx, modelId, op.path("targetId").asText(), op.path("key").asText(), op.path("member").asText(null), op.path("causal"));
            case "AddViewObjectChildMember" ->
                    addViewObjectChildMember(tx, modelId, op.path("parentViewObjectId").asText(null), op.path("childViewObjectId").asText(null), op.path("causal"));
            case "RemoveViewObjectChildMember" ->
                    removeViewObjectChildMember(tx, modelId, op.path("parentViewObjectId").asText(null), op.path("childViewObjectId").asText(null), op.path("causal"));
            case "CreateView" -> createView(tx, modelId, op.path("view"), op.path("causal"));
            case "UpdateView" ->
                    updateViewWithLww(tx, modelId, op.path("viewId").asText(), op.path("patch"), op.path("causal"));
            case "DeleteView" ->
                    deleteViewWithTombstone(tx, modelId, op.path("viewId").asText(), op.path("causal"), opRevision);
            case "CreateViewObject" -> createViewObject(tx, modelId, op);
            case "UpdateViewObjectOpaque" -> updateViewObjectNotation(tx, modelId, op);
            case "DeleteViewObject" ->
                    deleteViewObjectWithTombstone(tx, modelId, op.path("viewObjectId").asText(), op.path("causal"), opRevision);
            case "CreateConnection" -> createConnection(tx, modelId, op.path("connection"), op.path("causal"));
            case "UpdateConnectionOpaque" -> updateConnectionNotationWithLww(tx, modelId, op);
            case "DeleteConnection" ->
                    deleteConnectionWithTombstone(tx, modelId, op.path("connectionId").asText(), op.path("causal"), opRevision);
            default -> {
                // Forward-compatibility: preserve unknown ops in commit history, skip materialized mutation
            }
        }
    }

    private void createElement(TransactionContext tx, String modelId, JsonNode element, JsonNode causalNode) {
        String elementId = element.path("id").asText(null);
        if (elementId == null || elementId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple tombstone = readElementTombstone(tx, modelId, elementId);
        if (tombstone != null && !wins(incoming, tombstone)) {
            LOG.trace("Ignored stale CreateElement due to tombstone: modelId={} elementId={} incoming=({}, {}) tombstone=({}, {})",
                    modelId, elementId, incoming.lamport(), incoming.clientId(), tombstone.lamport(), tombstone.clientId());
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", elementId);
        params.put("archimateType", element.path("archimateType").asText());
        params.put("name", nullableText(element, "name"));
        params.put("documentation", nullableText(element, "documentation"));
        params.put("lamport", incoming.lamport());
        params.put("clientId", incoming.clientId());
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (e:Element {modelId: $modelId, id: $id})
                SET e.archimateType = $archimateType,
                    e.modelId = $modelId,
                    e.name = $name,
                    e.documentation = $documentation,
                    e.name_lamport = $lamport,
                    e.name_clientId = $clientId,
                    e.documentation_lamport = $lamport,
                    e.documentation_clientId = $clientId
                MERGE (m)-[:HAS_ELEMENT]->(e)
                """, params);
        tx.run("""
                MATCH (t:ElementTombstone {modelId: $modelId, id: $id})
                DETACH DELETE t
                """, Map.of("modelId", modelId, "id", elementId));
    }

    private void createFolder(TransactionContext tx, String modelId, JsonNode folder, JsonNode causalNode) {
        String folderId = folder.path("id").asText(null);
        if (folderId == null || folderId.isBlank()) {
            return;
        }
        String folderType = folder.path("folderType").asText("USER");
        String parentFolderId = nullableText(folder, "parentFolderId");
        CausalTuple incoming = parseCausal(causalNode);
        // Folder identity is immutable. Renames and reparents mutate fields/edges, not the folder id itself.
        ensureFolderExists(tx, modelId, folderId, folderType);

        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", folderId);
        params.put("folderType", folderType);
        params.put("name", nullableText(folder, "name"));
        params.put("documentation", nullableText(folder, "documentation"));
        params.put("lamport", incoming.lamport());
        params.put("clientId", incoming.clientId());
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (f:Folder {modelId: $modelId, id: $id})
                SET f.folderType = $folderType,
                    f.modelId = $modelId,
                    f.name = $name,
                    f.documentation = $documentation,
                    f.name_lamport = $lamport,
                    f.name_clientId = $clientId,
                    f.documentation_lamport = $lamport,
                    f.documentation_clientId = $clientId,
                    f.parent_lamport = coalesce(f.parent_lamport, $lamport),
                    f.parent_clientId = coalesce(f.parent_clientId, $clientId)
                MERGE (m)-[:HAS_FOLDER]->(f)
                """, params);
        if (parentFolderId != null && !parentFolderId.isBlank()) {
            ensureFolderExists(tx, modelId, parentFolderId, rootFolderType(parentFolderId));
            tx.run("""
                    MATCH (parent:Folder {modelId: $modelId, id: $parentId})
                    MATCH (child:Folder {modelId: $modelId, id: $id})
                    OPTIONAL MATCH (:Folder {modelId: $modelId})-[old:HAS_FOLDER]->(child)
                    DELETE old
                    MERGE (parent)-[:HAS_FOLDER]->(child)
                    """, Map.of("modelId", modelId, "parentId", parentFolderId, "id", folderId));
        }
    }

    private void updateFolderWithLww(TransactionContext tx, String modelId, String folderId, JsonNode patch, JsonNode causalNode) {
        if (folderId == null || folderId.isBlank() || patch == null || !patch.isObject()) {
            return;
        }
        ensureFolderExists(tx, modelId, folderId, rootFolderType(folderId));
        CausalTuple incoming = parseCausal(causalNode);
        var result = tx.run("""
                MATCH (f:Folder {modelId: $modelId, id: $id})
                RETURN f.name AS name,
                       f.documentation AS documentation,
                       f.name_lamport AS nameLamport,
                       f.name_clientId AS nameClientId,
                       f.documentation_lamport AS documentationLamport,
                       f.documentation_clientId AS documentationClientId
                """, Map.of("modelId", modelId, "id", folderId));
        if (!result.hasNext()) {
            return;
        }
        Record record = result.single();
        String mergedName = record.get("name").isNull() ? null : record.get("name").asString(null);
        String mergedDocumentation = record.get("documentation").isNull() ? null : record.get("documentation").asString(null);
        CausalTuple nameMeta = readCausal(record, "nameLamport", "nameClientId");
        CausalTuple documentationMeta = readCausal(record, "documentationLamport", "documentationClientId");
        // Folder fields follow the same Lamport LWW rule as other entities so rename races converge predictably.
        boolean changed = false;
        if (patch.has("name") && wins(incoming, nameMeta)) {
            mergedName = nullableText(patch, "name");
            nameMeta = incoming;
            changed = true;
        }
        if (patch.has("documentation") && wins(incoming, documentationMeta)) {
            mergedDocumentation = nullableText(patch, "documentation");
            documentationMeta = incoming;
            changed = true;
        }
        if (!changed) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", folderId);
        params.put("name", mergedName);
        params.put("documentation", mergedDocumentation);
        params.put("nameLamport", nameMeta.lamport());
        params.put("nameClientId", nameMeta.clientId());
        params.put("documentationLamport", documentationMeta.lamport());
        params.put("documentationClientId", documentationMeta.clientId());
        tx.run("""
                MATCH (f:Folder {modelId: $modelId, id: $id})
                SET f.name = $name,
                    f.documentation = $documentation,
                    f.name_lamport = $nameLamport,
                    f.name_clientId = $nameClientId,
                    f.documentation_lamport = $documentationLamport,
                    f.documentation_clientId = $documentationClientId
                """, params);
    }

    private void deleteFolder(TransactionContext tx, String modelId, String folderId) {
        if (folderId == null || folderId.isBlank() || isRootFolder(folderId)) {
            return;
        }
        // Delete is explicit and conservative: nothing is implicitly reparented during replay.
        var result = tx.run("""
                MATCH (f:Folder {modelId: $modelId, id: $id})
                RETURN size((f)-[:HAS_FOLDER]->()) = 0
                   AND size((f)-[:CONTAINS_ELEMENT]->()) = 0
                   AND size((f)-[:CONTAINS_REL]->()) = 0
                   AND size((f)-[:CONTAINS_VIEW]->()) = 0 AS empty
                """, Map.of("modelId", modelId, "id", folderId));
        if (!result.hasNext()) {
            return;
        }
        boolean empty = result.single().get("empty").asBoolean(false);
        if (!empty) {
            return;
        }
        tx.run("""
                MATCH (f:Folder {modelId: $modelId, id: $id})
                DETACH DELETE f
                """, Map.of("modelId", modelId, "id", folderId));
    }

    private void moveFolder(TransactionContext tx, String modelId, String folderId, String parentFolderId, JsonNode causalNode) {
        if (folderId == null || folderId.isBlank() || parentFolderId == null || parentFolderId.isBlank() || isRootFolder(folderId)) {
            return;
        }
        ensureFolderExists(tx, modelId, folderId, rootFolderType(folderId));
        ensureFolderExists(tx, modelId, parentFolderId, rootFolderType(parentFolderId));
        CausalTuple incoming = parseCausal(causalNode);
        var result = tx.run("""
                MATCH (f:Folder {modelId: $modelId, id: $id})
                RETURN f.parent_lamport AS parentLamport,
                       f.parent_clientId AS parentClientId
                """, Map.of("modelId", modelId, "id", folderId));
        if (!result.hasNext()) {
            return;
        }
        Record record = result.single();
        CausalTuple parentMeta = readCausal(record, "parentLamport", "parentClientId");
        // Parent edges are LWW-governed after service-layer validation has already ruled out illegal cross-root moves.
        if (!wins(incoming, parentMeta)) {
            return;
        }
        tx.run("""
                MATCH (parent:Folder {modelId: $modelId, id: $parentId})
                MATCH (child:Folder {modelId: $modelId, id: $id})
                OPTIONAL MATCH (:Folder {modelId: $modelId})-[old:HAS_FOLDER]->(child)
                DELETE old
                MERGE (parent)-[:HAS_FOLDER]->(child)
                SET child.parent_lamport = $lamport,
                    child.parent_clientId = $clientId
                """, Map.of(
                "modelId", modelId,
                "parentId", parentFolderId,
                "id", folderId,
                "lamport", incoming.lamport(),
                "clientId", incoming.clientId()));
    }

    private void moveToFolder(TransactionContext tx, String modelId, String folderId, String targetId, String label, String relType) {
        if (folderId == null || folderId.isBlank() || targetId == null || targetId.isBlank()) {
            return;
        }
        ensureFolderExists(tx, modelId, folderId, rootFolderType(folderId));
        // Model-tree membership is single-parent. A move replaces any previous folder edge for that target.
        String query = switch (relType) {
            case "CONTAINS_ELEMENT" -> """
                    MATCH (f:Folder {modelId: $modelId, id: $folderId})
                    MATCH (target:Element {modelId: $modelId, id: $targetId})
                    OPTIONAL MATCH (:Folder {modelId: $modelId})-[old:CONTAINS_ELEMENT]->(target)
                    DELETE old
                    MERGE (f)-[:CONTAINS_ELEMENT]->(target)
                    """;
            case "CONTAINS_REL" -> """
                    MATCH (f:Folder {modelId: $modelId, id: $folderId})
                    MATCH (target:Relationship {modelId: $modelId, id: $targetId})
                    OPTIONAL MATCH (:Folder {modelId: $modelId})-[old:CONTAINS_REL]->(target)
                    DELETE old
                    MERGE (f)-[:CONTAINS_REL]->(target)
                    """;
            case "CONTAINS_VIEW" -> """
                    MATCH (f:Folder {modelId: $modelId, id: $folderId})
                    MATCH (target:View {modelId: $modelId, id: $targetId})
                    OPTIONAL MATCH (:Folder {modelId: $modelId})-[old:CONTAINS_VIEW]->(target)
                    DELETE old
                    MERGE (f)-[:CONTAINS_VIEW]->(target)
                    """;
            default -> null;
        };
        if (query == null) {
            return;
        }
        tx.run(query, Map.of("modelId", modelId, "folderId", folderId, "targetId", targetId));
    }

    private void createRelationship(TransactionContext tx, String modelId, JsonNode relationship, JsonNode causalNode) {
        String relationshipId = relationship.path("id").asText(null);
        if (relationshipId == null || relationshipId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple tombstone = readRelationshipTombstone(tx, modelId, relationshipId);
        if (tombstone != null && !wins(incoming, tombstone)) {
            LOG.trace("Ignored stale CreateRelationship due to tombstone: modelId={} relationshipId={} incoming=({}, {}) tombstone=({}, {})",
                    modelId, relationshipId, incoming.lamport(), incoming.clientId(), tombstone.lamport(), tombstone.clientId());
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", relationshipId);
        params.put("archimateType", relationship.path("archimateType").asText());
        params.put("name", nullableText(relationship, "name"));
        params.put("documentation", nullableText(relationship, "documentation"));
        params.put("sourceId", relationship.path("sourceId").asText());
        params.put("targetId", relationship.path("targetId").asText());
        params.put("lamport", incoming.lamport());
        params.put("clientId", incoming.clientId());
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (r:Relationship {modelId: $modelId, id: $id})
                SET r.archimateType = $archimateType,
                    r.modelId = $modelId,
                    r.name = $name,
                    r.documentation = $documentation,
                    r.name_lamport = $lamport,
                    r.name_clientId = $clientId,
                    r.documentation_lamport = $lamport,
                    r.documentation_clientId = $clientId,
                    r.source_lamport = $lamport,
                    r.source_clientId = $clientId,
                    r.target_lamport = $lamport,
                    r.target_clientId = $clientId
                MERGE (m)-[:HAS_REL]->(r)
                WITH r
                OPTIONAL MATCH (s:Element {modelId: $modelId, id: $sourceId})
                FOREACH (_ IN CASE WHEN s IS NULL THEN [] ELSE [1] END | MERGE (r)-[:SOURCE]->(s))
                WITH r
                OPTIONAL MATCH (t:Element {modelId: $modelId, id: $targetId})
                FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END | MERGE (r)-[:TARGET]->(t))
                """, params);
        tx.run("""
                MATCH (t:RelationshipTombstone {modelId: $modelId, id: $id})
                DETACH DELETE t
                """, Map.of("modelId", modelId, "id", relationshipId));
    }

    private void updateRelationshipWithLww(TransactionContext tx, String modelId, String relationshipId, JsonNode patch, JsonNode causalNode) {
        if (relationshipId == null || relationshipId.isBlank() || patch == null || !patch.isObject()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple tombstone = readRelationshipTombstone(tx, modelId, relationshipId);
        if (tombstone != null && !wins(incoming, tombstone)) {
            LOG.trace("Ignored stale UpdateRelationship due to tombstone: modelId={} relationshipId={} incoming=({}, {}) tombstone=({}, {})",
                    modelId, relationshipId, incoming.lamport(), incoming.clientId(), tombstone.lamport(), tombstone.clientId());
            return;
        }

        var result = tx.run("""
                MATCH (r:Relationship {modelId: $modelId, id: $id})
                OPTIONAL MATCH (r)-[:SOURCE]->(src:Element)
                OPTIONAL MATCH (r)-[:TARGET]->(dst:Element)
                RETURN r.name AS name,
                       r.documentation AS documentation,
                       src.id AS sourceId,
                       dst.id AS targetId,
                       r.name_lamport AS nameLamport,
                       r.name_clientId AS nameClientId,
                       r.documentation_lamport AS documentationLamport,
                       r.documentation_clientId AS documentationClientId,
                       r.source_lamport AS sourceLamport,
                       r.source_clientId AS sourceClientId,
                       r.target_lamport AS targetLamport,
                       r.target_clientId AS targetClientId
                """, Map.of("modelId", modelId, "id", relationshipId));
        if (!result.hasNext()) {
            return;
        }

        Record record = result.single();
        String mergedName = record.get("name").isNull() ? null : record.get("name").asString(null);
        String mergedDocumentation = record.get("documentation").isNull() ? null : record.get("documentation").asString(null);
        CausalTuple nameMeta = readCausal(record, "nameLamport", "nameClientId");
        CausalTuple documentationMeta = readCausal(record, "documentationLamport", "documentationClientId");
        CausalTuple sourceMeta = readCausal(record, "sourceLamport", "sourceClientId");
        CausalTuple targetMeta = readCausal(record, "targetLamport", "targetClientId");
        // Relationship updates merge field-by-field so a stale write to one field does not roll back newer
        // data on the others.

        boolean changed = false;
        boolean updateSource = false;
        boolean updateTarget = false;
        String newSourceId = patch.has("sourceId") ? nullableText(patch, "sourceId") : null;
        String newTargetId = patch.has("targetId") ? nullableText(patch, "targetId") : null;

        if (patch.has("name") && wins(incoming, nameMeta)) {
            mergedName = nullableText(patch, "name");
            nameMeta = incoming;
            changed = true;
        }
        if (patch.has("documentation") && wins(incoming, documentationMeta)) {
            mergedDocumentation = nullableText(patch, "documentation");
            documentationMeta = incoming;
            changed = true;
        }
        if (patch.has("sourceId") && wins(incoming, sourceMeta)) {
            sourceMeta = incoming;
            updateSource = true;
            changed = true;
        }
        if (patch.has("targetId") && wins(incoming, targetMeta)) {
            targetMeta = incoming;
            updateTarget = true;
            changed = true;
        }
        if (!changed) {
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("id", relationshipId);
        params.put("modelId", modelId);
        params.put("name", mergedName);
        params.put("documentation", mergedDocumentation);
        params.put("nameLamport", nameMeta.lamport());
        params.put("nameClientId", nameMeta.clientId());
        params.put("documentationLamport", documentationMeta.lamport());
        params.put("documentationClientId", documentationMeta.clientId());
        params.put("sourceLamport", sourceMeta.lamport());
        params.put("sourceClientId", sourceMeta.clientId());
        params.put("targetLamport", targetMeta.lamport());
        params.put("targetClientId", targetMeta.clientId());
        tx.run("""
                MATCH (r:Relationship {modelId: $modelId, id: $id})
                SET r.name = $name,
                    r.documentation = $documentation,
                    r.name_lamport = $nameLamport,
                    r.name_clientId = $nameClientId,
                    r.documentation_lamport = $documentationLamport,
                    r.documentation_clientId = $documentationClientId,
                    r.source_lamport = $sourceLamport,
                    r.source_clientId = $sourceClientId,
                    r.target_lamport = $targetLamport,
                    r.target_clientId = $targetClientId
                """, params);

        if (updateSource) {
            tx.run("""
                    MATCH (r:Relationship {modelId: $modelId, id: $id})
                    OPTIONAL MATCH (r)-[old:SOURCE]->()
                    DELETE old
                    WITH r
                    OPTIONAL MATCH (s:Element {modelId: $modelId, id: $sourceId})
                    FOREACH (_ IN CASE WHEN s IS NULL THEN [] ELSE [1] END | MERGE (r)-[:SOURCE]->(s))
                    """, Map.of("modelId", modelId, "id", relationshipId, "sourceId", newSourceId));
        }
        if (updateTarget) {
            tx.run("""
                    MATCH (r:Relationship {modelId: $modelId, id: $id})
                    OPTIONAL MATCH (r)-[old:TARGET]->()
                    DELETE old
                    WITH r
                    OPTIONAL MATCH (t:Element {modelId: $modelId, id: $targetId})
                    FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END | MERGE (r)-[:TARGET]->(t))
                    """, Map.of("modelId", modelId, "id", relationshipId, "targetId", newTargetId));
        }
    }

    private void createView(TransactionContext tx, String modelId, JsonNode view, JsonNode causalNode) {
        String viewId = view.path("id").asText(null);
        if (viewId == null || viewId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple tombstone = readViewTombstone(tx, modelId, viewId);
        if (tombstone != null && !wins(incoming, tombstone)) {
            LOG.trace("Ignored stale CreateView due to tombstone: modelId={} viewId={} incoming=({}, {}) tombstone=({}, {})",
                    modelId, viewId, incoming.lamport(), incoming.clientId(), tombstone.lamport(), tombstone.clientId());
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", viewId);
        params.put("name", view.path("name").asText());
        params.put("documentation", nullableText(view, "documentation"));
        params.put("notationJson", jsonText(view.path("notationJson")));
        params.put("lamport", incoming.lamport());
        params.put("clientId", incoming.clientId());
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (v:View {modelId: $modelId, id: $id})
                SET v.name = $name,
                    v.documentation = $documentation,
                    v.notationJson = $notationJson,
                    v.modelId = $modelId,
                    v.name_lamport = $lamport,
                    v.name_clientId = $clientId,
                    v.documentation_lamport = $lamport,
                    v.documentation_clientId = $clientId,
                    v.notation_lamport = $lamport,
                    v.notation_clientId = $clientId
                MERGE (m)-[:HAS_VIEW]->(v)
                """, params);
        tx.run("""
                MATCH (t:ViewTombstone {modelId: $modelId, id: $id})
                DETACH DELETE t
                """, Map.of("modelId", modelId, "id", viewId));
    }

    private void updateViewWithLww(TransactionContext tx, String modelId, String viewId, JsonNode patch, JsonNode causalNode) {
        if (viewId == null || viewId.isBlank() || patch == null || !patch.isObject()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple tombstone = readViewTombstone(tx, modelId, viewId);
        if (tombstone != null && !wins(incoming, tombstone)) {
            LOG.trace("Ignored stale UpdateView due to tombstone: modelId={} viewId={} incoming=({}, {}) tombstone=({}, {})",
                    modelId, viewId, incoming.lamport(), incoming.clientId(), tombstone.lamport(), tombstone.clientId());
            return;
        }

        var result = tx.run("""
                MATCH (v:View {modelId: $modelId, id: $id})
                RETURN v.name AS name,
                       v.documentation AS documentation,
                       v.notationJson AS notationJson,
                       v.name_lamport AS nameLamport,
                       v.name_clientId AS nameClientId,
                       v.documentation_lamport AS documentationLamport,
                       v.documentation_clientId AS documentationClientId,
                       v.notation_lamport AS notationLamport,
                       v.notation_clientId AS notationClientId
                """, Map.of("modelId", modelId, "id", viewId));
        if (!result.hasNext()) {
            return;
        }

        Record record = result.single();
        String mergedName = record.get("name").isNull() ? null : record.get("name").asString(null);
        String mergedDocumentation = record.get("documentation").isNull() ? null : record.get("documentation").asString(null);
        String mergedNotation = record.get("notationJson").isNull() ? null : record.get("notationJson").asString(null);
        CausalTuple nameMeta = readCausal(record, "nameLamport", "nameClientId");
        CausalTuple documentationMeta = readCausal(record, "documentationLamport", "documentationClientId");
        CausalTuple notationMeta = readCausal(record, "notationLamport", "notationClientId");

        boolean changed = false;
        if (patch.has("name") && wins(incoming, nameMeta)) {
            mergedName = nullableText(patch, "name");
            nameMeta = incoming;
            changed = true;
        }
        if (patch.has("documentation") && wins(incoming, documentationMeta)) {
            mergedDocumentation = nullableText(patch, "documentation");
            documentationMeta = incoming;
            changed = true;
        }
        if (patch.has("notationJson") && wins(incoming, notationMeta)) {
            mergedNotation = jsonText(patch.path("notationJson"));
            notationMeta = incoming;
            changed = true;
        }
        if (!changed) {
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("id", viewId);
        params.put("modelId", modelId);
        params.put("name", mergedName);
        params.put("documentation", mergedDocumentation);
        params.put("notationJson", mergedNotation);
        params.put("nameLamport", nameMeta.lamport());
        params.put("nameClientId", nameMeta.clientId());
        params.put("documentationLamport", documentationMeta.lamport());
        params.put("documentationClientId", documentationMeta.clientId());
        params.put("notationLamport", notationMeta.lamport());
        params.put("notationClientId", notationMeta.clientId());
        tx.run("""
                MATCH (v:View {modelId: $modelId, id: $id})
                SET v.name = $name,
                    v.documentation = $documentation,
                    v.notationJson = $notationJson,
                    v.name_lamport = $nameLamport,
                    v.name_clientId = $nameClientId,
                    v.documentation_lamport = $documentationLamport,
                    v.documentation_clientId = $documentationClientId,
                    v.notation_lamport = $notationLamport,
                    v.notation_clientId = $notationClientId
                """, params);
    }

    private void createViewObject(TransactionContext tx, String modelId, JsonNode op) {
        JsonNode viewObject = op.path("viewObject");
        String viewObjectId = viewObject.path("id").asText(null);
        if (viewObjectId == null || viewObjectId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(op.path("causal"));
        CausalTuple tombstone = readViewObjectTombstone(tx, modelId, viewObjectId);
        if (tombstone != null && !wins(incoming, tombstone)) {
            LOG.trace("Ignored stale CreateViewObject due to tombstone: modelId={} viewObjectId={} incoming=({}, {}) tombstone=({}, {})",
                    modelId, viewObjectId, incoming.lamport(), incoming.clientId(), tombstone.lamport(), tombstone.clientId());
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", viewObjectId);
        params.put("viewId", viewObject.path("viewId").asText());
        params.put("representsId", viewObject.path("representsId").asText());
        tx.run("""
                MATCH (v:View {modelId: $modelId, id: $viewId})
                MERGE (vo:ViewObject {modelId: $modelId, id: $id})
                MERGE (v)-[:CONTAINS]->(vo)
                WITH vo
                OPTIONAL MATCH (e:Element {modelId: $modelId, id: $representsId})
                FOREACH (_ IN CASE WHEN e IS NULL THEN [] ELSE [1] END | MERGE (vo)-[:REPRESENTS]->(e))
                """, params);
        tx.run("""
                MATCH (t:ViewObjectTombstone {modelId: $modelId, id: $id})
                DETACH DELETE t
                """, Map.of("modelId", modelId, "id", viewObjectId));
        applyViewObjectNotationWithLww(tx, modelId, viewObject.path("id").asText(null), viewObject.path("notationJson"), op.path("causal"));
        rematerializeViewObjectChildMembersForParent(tx, modelId, viewObjectId);
        rematerializeViewObjectChildMembersForChild(tx, modelId, viewObjectId);
    }

    private void updateViewObjectNotation(TransactionContext tx, String modelId, JsonNode op) {
        applyViewObjectNotationWithLww(tx, modelId, op.path("viewObjectId").asText(null), op.path("notationJson"), op.path("causal"));
    }

    private void createConnection(TransactionContext tx, String modelId, JsonNode connection, JsonNode causalNode) {
        String connectionId = connection.path("id").asText(null);
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple tombstone = readConnectionTombstone(tx, modelId, connectionId);
        if (tombstone != null && !wins(incoming, tombstone)) {
            LOG.trace("Ignored stale CreateConnection due to tombstone: modelId={} connectionId={} incoming=({}, {}) tombstone=({}, {})",
                    modelId, connectionId, incoming.lamport(), incoming.clientId(), tombstone.lamport(), tombstone.clientId());
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", connectionId);
        params.put("viewId", connection.path("viewId").asText());
        params.put("representsId", connection.path("representsId").asText());
        params.put("sourceViewObjectId", connection.path("sourceViewObjectId").asText());
        params.put("targetViewObjectId", connection.path("targetViewObjectId").asText());
        params.put("notationJson", jsonText(connection.path("notationJson")));
        tx.run("""
                MATCH (v:View {modelId: $modelId, id: $viewId})
                MERGE (c:Connection {modelId: $modelId, id: $id})
                SET c.modelId = $modelId,
                    c.notationJson = $notationJson
                MERGE (v)-[:CONTAINS]->(c)
                WITH c
                OPTIONAL MATCH (r:Relationship {modelId: $modelId, id: $representsId})
                FOREACH (_ IN CASE WHEN r IS NULL THEN [] ELSE [1] END | MERGE (c)-[:REPRESENTS]->(r))
                WITH c
                OPTIONAL MATCH (f:ViewObject {modelId: $modelId, id: $sourceViewObjectId})
                FOREACH (_ IN CASE WHEN f IS NULL THEN [] ELSE [1] END | MERGE (c)-[:FROM]->(f))
                WITH c
                OPTIONAL MATCH (t:ViewObject {modelId: $modelId, id: $targetViewObjectId})
                FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END | MERGE (c)-[:TO]->(t))
                """, params);
        applyConnectionNotationWithLww(tx, modelId, connectionId, connection.path("notationJson"), causalNode);
        tx.run("""
                MATCH (t:ConnectionTombstone {modelId: $modelId, id: $id})
                DETACH DELETE t
                """, Map.of("modelId", modelId, "id", connectionId));
    }

    private void updateNotation(TransactionContext tx, String modelId, String label, String id, JsonNode notationJson) {
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", id);
        params.put("notationJson", jsonText(notationJson));
        tx.run("MATCH (n:" + label + " {modelId: $modelId, id: $id}) SET n.notationJson = $notationJson",
                params);
    }

    private void applyViewObjectNotationWithLww(TransactionContext tx, String modelId, String viewObjectId, JsonNode incomingNotationNode, JsonNode causalNode) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        if (viewObjectId == null || viewObjectId.isBlank()) {
            return;
        }
        ObjectNode incomingNotation = asObjectNode(incomingNotationNode);
        if (incomingNotation == null) {
            return;
        }

        var result = tx.run(
                "MATCH (vo:ViewObject {modelId: $modelId, id: $id}) " +
                        "RETURN vo.notationJson AS notationJson,\n" +
                        VIEW_OBJECT_NOTATION_RETURN,
                Map.of("modelId", modelId, "id", viewObjectId));
        if (!result.hasNext()) {
            return;
        }

        Record record = result.single();
        ObjectNode existingNotation = asObjectNode(parseJsonOrNull(record.get("notationJson").asString(null)));
        ObjectNode mergedNotation = objectMapper.createObjectNode();
        if (existingNotation != null) {
            mergedNotation.setAll(existingNotation);
        }
        mergedNotation.setAll(incomingNotation);

        CausalTuple incomingCausal = parseCausal(causalNode);
        Map<String, CausalTuple> fieldMeta = mergeNotationFields(
                NotationMetadata.VIEW_OBJECT_PERSISTED_FIELDS,
                incomingNotation,
                existingNotation,
                mergedNotation,
                incomingCausal,
                record);

        Map<String, Object> params = new HashMap<>();
        params.put("id", viewObjectId);
        params.put("modelId", modelId);
        params.put("notationJson", jsonText(mergedNotation));
        putNotationMetaParams(params, NotationMetadata.VIEW_OBJECT_PERSISTED_FIELDS, fieldMeta);
        tx.run(
                "MATCH (vo:ViewObject {modelId: $modelId, id: $id}) " +
                        "SET vo.notationJson = $notationJson,\n" +
                        VIEW_OBJECT_NOTATION_SET,
                params);
    }

    private void updateConnectionNotationWithLww(TransactionContext tx, String modelId, JsonNode op) {
        applyConnectionNotationWithLww(tx, modelId, op.path("connectionId").asText(null), op.path("notationJson"), op.path("causal"));
    }

    private void applyConnectionNotationWithLww(TransactionContext tx, String modelId, String connectionId, JsonNode incomingNotationNode, JsonNode causalNode) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        ObjectNode incomingNotation = asObjectNode(incomingNotationNode);
        if (incomingNotation == null) {
            return;
        }

        var result = tx.run(
                "MATCH (c:Connection {modelId: $modelId, id: $id}) " +
                        "RETURN c.notationJson AS notationJson,\n" +
                        CONNECTION_NOTATION_RETURN,
                Map.of("modelId", modelId, "id", connectionId));
        if (!result.hasNext()) {
            return;
        }

        Record record = result.single();
        ObjectNode existingNotation = asObjectNode(parseJsonOrNull(record.get("notationJson").asString(null)));
        ObjectNode mergedNotation = objectMapper.createObjectNode();
        if (existingNotation != null) {
            mergedNotation.setAll(existingNotation);
        }
        mergedNotation.setAll(incomingNotation);

        CausalTuple incomingCausal = parseCausal(causalNode);
        Map<String, CausalTuple> fieldMeta = mergeNotationFields(
                NotationMetadata.CONNECTION_PERSISTED_FIELDS,
                incomingNotation,
                existingNotation,
                mergedNotation,
                incomingCausal,
                record);

        Map<String, Object> params = new HashMap<>();
        params.put("id", connectionId);
        params.put("modelId", modelId);
        params.put("notationJson", jsonText(mergedNotation));
        putNotationMetaParams(params, NotationMetadata.CONNECTION_PERSISTED_FIELDS, fieldMeta);
        tx.run(
                "MATCH (c:Connection {modelId: $modelId, id: $id}) " +
                        "SET c.notationJson = $notationJson,\n" +
                        CONNECTION_NOTATION_SET,
                params);
    }

    private CausalTuple mergeNotationField(String fieldName,
                                           ObjectNode incomingNotation,
                                           ObjectNode existingNotation,
                                           ObjectNode mergedNotation,
                                           CausalTuple incomingCausal,
                                           CausalTuple existingCausal) {
        if (incomingNotation == null || !incomingNotation.has(fieldName)) {
            return existingCausal;
        }
        if (wins(incomingCausal, existingCausal)) {
            mergedNotation.set(fieldName, incomingNotation.get(fieldName));
            return incomingCausal;
        }
        // Keep the prior value when incoming is stale; remove only when no prior value exists
        if (existingNotation != null && existingNotation.has(fieldName)) {
            mergedNotation.set(fieldName, existingNotation.get(fieldName));
        } else {
            mergedNotation.remove(fieldName);
        }
        return existingCausal;
    }

    private CausalTuple mergeGeometryField(String fieldName,
                                           String logFieldName,
                                           ObjectNode incomingNotation,
                                           ObjectNode existingNotation,
                                           ObjectNode mergedNotation,
                                           CausalTuple incomingCausal,
                                           CausalTuple existingCausal) {
        if (incomingNotation == null || !incomingNotation.has(fieldName)) {
            return existingCausal;
        }

        if (wins(incomingCausal, existingCausal)) {
            mergedNotation.set(fieldName, incomingNotation.get(fieldName));
            LOG.trace("LWW geometry update applied: field={} lamport={} clientId={}",
                    logFieldName, incomingCausal.lamport(), incomingCausal.clientId());
            return incomingCausal;
        }

        if (existingNotation != null && existingNotation.has(fieldName)) {
            mergedNotation.set(fieldName, existingNotation.get(fieldName));
        } else {
            mergedNotation.remove(fieldName);
        }
        LOG.trace("LWW geometry update ignored as stale: field={} incoming=({}, {}) existing=({}, {})",
                logFieldName,
                incomingCausal.lamport(), incomingCausal.clientId(),
                existingCausal.lamport(), existingCausal.clientId());
        return existingCausal;
    }

    private Map<String, CausalTuple> mergeNotationFields(List<PersistedNotationField> fields,
                                                         ObjectNode incomingNotation,
                                                         ObjectNode existingNotation,
                                                         ObjectNode mergedNotation,
                                                         CausalTuple incomingCausal,
                                                         Record record) {
        Map<String, CausalTuple> fieldMeta = new LinkedHashMap<>();
        for (PersistedNotationField field : fields) {
            CausalTuple existingCausal = readCausal(record, field.lamportAlias(), field.clientAlias());
            CausalTuple mergedCausal = field.geometry()
                    ? mergeGeometryField(field.fieldName(), field.fieldName(), incomingNotation, existingNotation, mergedNotation, incomingCausal, existingCausal)
                    : mergeNotationField(field.fieldName(), incomingNotation, existingNotation, mergedNotation, incomingCausal, existingCausal);
            fieldMeta.put(field.fieldName(), mergedCausal);
        }
        return fieldMeta;
    }

    private void putNotationMetaParams(Map<String, Object> params,
                                       List<PersistedNotationField> fields,
                                       Map<String, CausalTuple> fieldMeta) {
        for (PersistedNotationField field : fields) {
            CausalTuple meta = fieldMeta.get(field.fieldName());
            params.put(field.lamportAlias(), meta.lamport());
            params.put(field.clientAlias(), meta.clientId());
        }
    }

    private static String buildNotationReturnClause(String variable, List<PersistedNotationField> fields) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            PersistedNotationField field = fields.get(i);
            if (i > 0) {
                builder.append(",\n");
            }
            builder.append("       ")
                    .append(variable).append('.').append(field.propertyPrefix()).append("_lamport AS ").append(field.lamportAlias())
                    .append(",\n")
                    .append("       ")
                    .append(variable).append('.').append(field.propertyPrefix()).append("_clientId AS ").append(field.clientAlias());
        }
        return builder.toString();
    }

    private static String buildNotationSetClause(String variable, List<PersistedNotationField> fields) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            PersistedNotationField field = fields.get(i);
            if (i > 0) {
                builder.append(",\n");
            }
            builder.append("    ")
                    .append(variable).append('.').append(field.propertyPrefix()).append("_lamport = $").append(field.lamportAlias())
                    .append(",\n")
                    .append("    ")
                    .append(variable).append('.').append(field.propertyPrefix()).append("_clientId = $").append(field.clientAlias());
        }
        return builder.toString();
    }

    private ObjectNode asObjectNode(JsonNode node) {
        if (node != null && node.isObject()) {
            return node.deepCopy();
        }
        return null;
    }

    private CausalTuple parseCausal(JsonNode causalNode) {
        if (causalNode != null && causalNode.isObject()) {
            long lamport = causalNode.path("lamport").asLong(0L);
            if (lamport < 0) {
                lamport = 0;
            }
            String clientId = causalNode.path("clientId").asText("");
            if (clientId == null || clientId.isBlank()) {
                clientId = "unknown-client";
            }
            return new CausalTuple(lamport, clientId);
        }
        return new CausalTuple(0L, "unknown-client");
    }

    private CausalTuple readCausal(Record record, String lamportField, String clientField) {
        Value lamportValue = record.get(lamportField);
        Value clientValue = record.get(clientField);
        long lamport = lamportValue == null || lamportValue.isNull() ? -1L : lamportValue.asLong(-1L);
        String clientId = clientValue == null || clientValue.isNull() ? "" : clientValue.asString("");
        return new CausalTuple(lamport, clientId == null ? "" : clientId);
    }

    private boolean wins(CausalTuple incoming, CausalTuple existing) {
        if (incoming.lamport() > existing.lamport()) {
            return true;
        }
        if (incoming.lamport() < existing.lamport()) {
            return false;
        }
        return incoming.clientId().compareTo(existing.clientId()) >= 0;
    }

    private void setPropertyWithLww(TransactionContext tx, String modelId, String targetId, String key, JsonNode value, JsonNode causalNode) {
        if (targetId == null || targetId.isBlank() || key == null || key.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple existing = readPropertyClock(tx, modelId, targetId, key);
        if (existing != null && !wins(incoming, existing)) {
            return;
        }

        Object scalarValue = jsonScalar(value);
        tx.run("MATCH (n {modelId: $modelId, id: $id}) SET n[$key] = $value",
                Map.of("modelId", modelId, "id", targetId, "key", key, "value", scalarValue));
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (p:PropertyClock {modelId: $modelId, targetId: $targetId, `key`: $key})
                SET p.lamport = $lamport,
                    p.clientId = $clientId,
                    p.deleted = false
                MERGE (m)-[:HAS_PROPERTY_CLOCK]->(p)
                """, Map.of(
                "modelId", modelId,
                "targetId", targetId,
                "key", key,
                "lamport", incoming.lamport(),
                "clientId", incoming.clientId()));
    }

    private void unsetPropertyWithLww(TransactionContext tx, String modelId, String targetId, String key, JsonNode causalNode) {
        if (targetId == null || targetId.isBlank() || key == null || key.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple existing = readPropertyClock(tx, modelId, targetId, key);
        if (existing != null && !wins(incoming, existing)) {
            return;
        }

        tx.run("MATCH (n {modelId: $modelId, id: $id}) REMOVE n[$key]",
                Map.of("modelId", modelId, "id", targetId, "key", key));
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (p:PropertyClock {modelId: $modelId, targetId: $targetId, `key`: $key})
                SET p.lamport = $lamport,
                    p.clientId = $clientId,
                    p.deleted = true
                MERGE (m)-[:HAS_PROPERTY_CLOCK]->(p)
                """, Map.of(
                "modelId", modelId,
                "targetId", targetId,
                "key", key,
                "lamport", incoming.lamport(),
                "clientId", incoming.clientId()));
    }

    private void addPropertySetMember(TransactionContext tx, String modelId, String targetId, String key, String member, JsonNode causalNode) {
        if (targetId == null || targetId.isBlank() || key == null || key.isBlank() || member == null || member.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        String clockKey = propertySetClockStorageKey(key, member);
        PropertyClockState existing = readPropertyClockState(tx, modelId, targetId, clockKey);
        CrdtOrSet.Clock incomingClock = CrdtOrSet.clock(incoming.lamport(), incoming.clientId());
        CrdtOrSet.Clock existingAdd = existing != null && !existing.deleted()
                ? CrdtOrSet.clock(existing.clock().lamport(), existing.clock().clientId())
                : null;
        CrdtOrSet.Clock existingRemove = existing != null && existing.deleted()
                ? CrdtOrSet.clock(existing.clock().lamport(), existing.clock().clientId())
                : null;
        // OR-Set semantics: add wins only when causally newer than competing add/remove clocks
        if (!CrdtOrSet.shouldApplyAdd(incomingClock, existingAdd, existingRemove)) {
            return;
        }
        upsertPropertySetClock(tx, modelId, targetId, clockKey, incoming, false);
        rematerializePropertySet(tx, modelId, targetId, key);
    }

    private void removePropertySetMember(TransactionContext tx, String modelId, String targetId, String key, String member, JsonNode causalNode) {
        if (targetId == null || targetId.isBlank() || key == null || key.isBlank() || member == null || member.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        String clockKey = propertySetClockStorageKey(key, member);
        PropertyClockState existing = readPropertyClockState(tx, modelId, targetId, clockKey);
        CrdtOrSet.Clock incomingClock = CrdtOrSet.clock(incoming.lamport(), incoming.clientId());
        CrdtOrSet.Clock existingAdd = existing != null && !existing.deleted()
                ? CrdtOrSet.clock(existing.clock().lamport(), existing.clock().clientId())
                : null;
        CrdtOrSet.Clock existingRemove = existing != null && existing.deleted()
                ? CrdtOrSet.clock(existing.clock().lamport(), existing.clock().clientId())
                : null;
        // OR-Set remove is represented as a tombstoned member clock
        if (!CrdtOrSet.shouldApplyRemove(incomingClock, existingAdd, existingRemove)) {
            return;
        }
        upsertPropertySetClock(tx, modelId, targetId, clockKey, incoming, true);
        rematerializePropertySet(tx, modelId, targetId, key);
    }

    private void addViewObjectChildMember(TransactionContext tx,
                                          String modelId,
                                          String parentViewObjectId,
                                          String childViewObjectId,
                                          JsonNode causalNode) {
        if (parentViewObjectId == null || parentViewObjectId.isBlank()
                || childViewObjectId == null || childViewObjectId.isBlank()
                || parentViewObjectId.equals(childViewObjectId)) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        String clockKey = viewObjectChildClockStorageKey(childViewObjectId);
        PropertyClockState existing = readPropertyClockState(tx, modelId, parentViewObjectId, clockKey);
        CrdtOrSet.Clock incomingClock = CrdtOrSet.clock(incoming.lamport(), incoming.clientId());
        CrdtOrSet.Clock existingAdd = existing != null && !existing.deleted()
                ? CrdtOrSet.clock(existing.clock().lamport(), existing.clock().clientId())
                : null;
        CrdtOrSet.Clock existingRemove = existing != null && existing.deleted()
                ? CrdtOrSet.clock(existing.clock().lamport(), existing.clock().clientId())
                : null;
        if (!CrdtOrSet.shouldApplyAdd(incomingClock, existingAdd, existingRemove)) {
            return;
        }
        upsertPropertySetClock(tx, modelId, parentViewObjectId, clockKey, incoming, false);
        rematerializeViewObjectChildMembersForParent(tx, modelId, parentViewObjectId);
    }

    private void removeViewObjectChildMember(TransactionContext tx,
                                             String modelId,
                                             String parentViewObjectId,
                                             String childViewObjectId,
                                             JsonNode causalNode) {
        if (parentViewObjectId == null || parentViewObjectId.isBlank()
                || childViewObjectId == null || childViewObjectId.isBlank()
                || parentViewObjectId.equals(childViewObjectId)) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        String clockKey = viewObjectChildClockStorageKey(childViewObjectId);
        PropertyClockState existing = readPropertyClockState(tx, modelId, parentViewObjectId, clockKey);
        CrdtOrSet.Clock incomingClock = CrdtOrSet.clock(incoming.lamport(), incoming.clientId());
        CrdtOrSet.Clock existingAdd = existing != null && !existing.deleted()
                ? CrdtOrSet.clock(existing.clock().lamport(), existing.clock().clientId())
                : null;
        CrdtOrSet.Clock existingRemove = existing != null && existing.deleted()
                ? CrdtOrSet.clock(existing.clock().lamport(), existing.clock().clientId())
                : null;
        if (!CrdtOrSet.shouldApplyRemove(incomingClock, existingAdd, existingRemove)) {
            return;
        }
        upsertPropertySetClock(tx, modelId, parentViewObjectId, clockKey, incoming, true);
        rematerializeViewObjectChildMembersForParent(tx, modelId, parentViewObjectId);
    }

    private void upsertPropertySetClock(TransactionContext tx,
                                        String modelId,
                                        String targetId,
                                        String clockKey,
                                        CausalTuple incoming,
                                        boolean deleted) {
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (p:PropertyClock {modelId: $modelId, targetId: $targetId, `key`: $key})
                SET p.lamport = $lamport,
                    p.clientId = $clientId,
                    p.deleted = $deleted
                MERGE (m)-[:HAS_PROPERTY_CLOCK]->(p)
                """, Map.of(
                "modelId", modelId,
                "targetId", targetId,
                "key", clockKey,
                "lamport", incoming.lamport(),
                "clientId", incoming.clientId(),
                "deleted", deleted));
    }

    private void rematerializePropertySet(TransactionContext tx, String modelId, String targetId, String key) {
        String prefix = propertySetClockPrefix(key);
        // Materialized property value is derived from active member clocks and sorted for stable snapshots
        List<String> members = tx.run("""
                        MATCH (p:PropertyClock {modelId: $modelId, targetId: $targetId})
                        WHERE p.`key` STARTS WITH $prefix
                          AND coalesce(p.deleted, false) = false
                        RETURN p.`key` AS clockKey
                        """, Map.of("modelId", modelId, "targetId", targetId, "prefix", prefix))
                .list(record -> decodePropertySetMember(record.get("clockKey").asString(""), prefix))
                .stream()
                .filter(v -> v != null && !v.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();

        if (members.isEmpty()) {
            tx.run("MATCH (n {modelId: $modelId, id: $id}) REMOVE n[$key]",
                    Map.of("modelId", modelId, "id", targetId, "key", key));
            return;
        }

        ArrayNode node = JsonNodeFactory.instance.arrayNode();
        for (String member : members) {
            node.add(member);
        }
        tx.run("MATCH (n {modelId: $modelId, id: $id}) SET n[$key] = $value", Map.of(
                "modelId", modelId,
                "id", targetId,
                "key", key,
                "value", node.toString()));
    }

    private void updateSimpleNode(TransactionContext tx, String modelId, String label, String id, JsonNode patch) {
        if (id == null || id.isBlank() || patch == null || !patch.isObject()) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", id);
        StringBuilder set = new StringBuilder();

        if (patch.has("name")) {
            params.put("name", nullableText(patch, "name"));
            set.append("n.name = $name, ");
        }
        if (patch.has("documentation")) {
            params.put("documentation", nullableText(patch, "documentation"));
            set.append("n.documentation = $documentation, ");
        }

        if (set.isEmpty()) {
            return;
        }
        set.setLength(set.length() - 2);
        tx.run("MATCH (n:" + label + " {modelId: $modelId, id: $id}) SET " + set, params);
    }

    private void deleteNode(TransactionContext tx, String modelId, String label, String id) {
        tx.run("MATCH (n:" + label + " {modelId: $modelId, id: $id}) DETACH DELETE n",
                Map.of("modelId", modelId, "id", id));
    }

    private void deleteViewWithTombstone(TransactionContext tx, String modelId, String viewId, JsonNode causalNode, long opRevision) {
        if (viewId == null || viewId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple existing = readViewTombstone(tx, modelId, viewId);
        if (existing == null || wins(incoming, existing)) {
            tx.run("""
                    MERGE (m:Model {modelId: $modelId})
                    MERGE (t:ViewTombstone {modelId: $modelId, id: $id})
                    SET t.lamport = $lamport,
                        t.clientId = $clientId,
                        t.updatedRevision = CASE WHEN $updatedRevision >= 0 THEN $updatedRevision ELSE t.updatedRevision END
                    MERGE (m)-[:HAS_VIEW_TOMBSTONE]->(t)
                    """, Map.of("modelId", modelId, "id", viewId, "lamport", incoming.lamport(),
                    "clientId", incoming.clientId(), "updatedRevision", opRevision));
        }
        tx.run("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(v:View {id: $id})
                DETACH DELETE v
                """, Map.of("modelId", modelId, "id", viewId));
    }

    private void deleteViewObjectWithTombstone(TransactionContext tx, String modelId, String viewObjectId, JsonNode causalNode, long opRevision) {
        if (viewObjectId == null || viewObjectId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple existing = readViewObjectTombstone(tx, modelId, viewObjectId);
        if (existing == null || wins(incoming, existing)) {
            tx.run("""
                    MERGE (m:Model {modelId: $modelId})
                    MERGE (t:ViewObjectTombstone {modelId: $modelId, id: $id})
                    SET t.lamport = $lamport,
                        t.clientId = $clientId,
                        t.updatedRevision = CASE WHEN $updatedRevision >= 0 THEN $updatedRevision ELSE t.updatedRevision END
                    MERGE (m)-[:HAS_VIEWOBJECT_TOMBSTONE]->(t)
                    """, Map.of("modelId", modelId, "id", viewObjectId, "lamport", incoming.lamport(),
                    "clientId", incoming.clientId(), "updatedRevision", opRevision));
        }
        tx.run("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(n:ViewObject {id: $id})
                DETACH DELETE n
                """, Map.of("modelId", modelId, "id", viewObjectId));
    }

    private void deleteConnectionWithTombstone(TransactionContext tx, String modelId, String connectionId, JsonNode causalNode, long opRevision) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple existing = readConnectionTombstone(tx, modelId, connectionId);
        if (existing == null || wins(incoming, existing)) {
            tx.run("""
                    MERGE (m:Model {modelId: $modelId})
                    MERGE (t:ConnectionTombstone {modelId: $modelId, id: $id})
                    SET t.lamport = $lamport,
                        t.clientId = $clientId,
                        t.updatedRevision = CASE WHEN $updatedRevision >= 0 THEN $updatedRevision ELSE t.updatedRevision END
                    MERGE (m)-[:HAS_CONNECTION_TOMBSTONE]->(t)
                    """, Map.of("modelId", modelId, "id", connectionId, "lamport", incoming.lamport(),
                    "clientId", incoming.clientId(), "updatedRevision", opRevision));
        }
        tx.run("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(n:Connection {id: $id})
                DETACH DELETE n
                """, Map.of("modelId", modelId, "id", connectionId));
    }

    private void deleteRelationshipWithTombstone(TransactionContext tx, String modelId, String relationshipId, JsonNode causalNode, long opRevision) {
        if (relationshipId == null || relationshipId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple existingTombstone = readRelationshipTombstone(tx, modelId, relationshipId);
        if (existingTombstone == null || wins(incoming, existingTombstone)) {
            tx.run("""
                    MERGE (m:Model {modelId: $modelId})
                    MERGE (t:RelationshipTombstone {modelId: $modelId, id: $id})
                    SET t.lamport = $lamport,
                        t.clientId = $clientId,
                        t.updatedRevision = CASE WHEN $updatedRevision >= 0 THEN $updatedRevision ELSE t.updatedRevision END
                    MERGE (m)-[:HAS_RELATIONSHIP_TOMBSTONE]->(t)
                    """, Map.of(
                    "modelId", modelId,
                    "id", relationshipId,
                    "lamport", incoming.lamport(),
                    "clientId", incoming.clientId(),
                    "updatedRevision", opRevision));
        }
        tx.run("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_REL]->(r:Relationship {id: $id})
                DETACH DELETE r
                """, Map.of("modelId", modelId, "id", relationshipId));
    }

    private void updateElementWithLww(TransactionContext tx, String modelId, String elementId, JsonNode patch, JsonNode causalNode) {
        if (elementId == null || elementId.isBlank() || patch == null || !patch.isObject()) {
            return;
        }

        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple tombstone = readElementTombstone(tx, modelId, elementId);
        if (tombstone != null && !wins(incoming, tombstone)) {
            LOG.trace("Ignored stale UpdateElement due to tombstone: modelId={} elementId={} incoming=({}, {}) tombstone=({}, {})",
                    modelId, elementId, incoming.lamport(), incoming.clientId(), tombstone.lamport(), tombstone.clientId());
            return;
        }

        var result = tx.run("""
                MATCH (n:Element {modelId: $modelId, id: $id})
                RETURN n.name AS name,
                       n.documentation AS documentation,
                       n.name_lamport AS nameLamport,
                       n.name_clientId AS nameClientId,
                       n.documentation_lamport AS documentationLamport,
                       n.documentation_clientId AS documentationClientId
                """, Map.of("modelId", modelId, "id", elementId));
        if (!result.hasNext()) {
            return;
        }

        Record record = result.single();
        String mergedName = record.get("name").isNull() ? null : record.get("name").asString(null);
        String mergedDocumentation = record.get("documentation").isNull() ? null : record.get("documentation").asString(null);
        CausalTuple nameMeta = readCausal(record, "nameLamport", "nameClientId");
        CausalTuple documentationMeta = readCausal(record, "documentationLamport", "documentationClientId");

        boolean changed = false;
        if (patch.has("name") && wins(incoming, nameMeta)) {
            mergedName = nullableText(patch, "name");
            nameMeta = incoming;
            changed = true;
        }
        if (patch.has("documentation") && wins(incoming, documentationMeta)) {
            mergedDocumentation = nullableText(patch, "documentation");
            documentationMeta = incoming;
            changed = true;
        }
        if (!changed) {
            return;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("id", elementId);
        params.put("modelId", modelId);
        params.put("name", mergedName);
        params.put("documentation", mergedDocumentation);
        params.put("nameLamport", nameMeta.lamport());
        params.put("nameClientId", nameMeta.clientId());
        params.put("documentationLamport", documentationMeta.lamport());
        params.put("documentationClientId", documentationMeta.clientId());
        tx.run("""
                MATCH (n:Element {modelId: $modelId, id: $id})
                SET n.name = $name,
                    n.documentation = $documentation,
                    n.name_lamport = $nameLamport,
                    n.name_clientId = $nameClientId,
                    n.documentation_lamport = $documentationLamport,
                    n.documentation_clientId = $documentationClientId
                """, params);
    }

    private void deleteElementWithTombstone(TransactionContext tx, String modelId, String elementId, JsonNode causalNode, long opRevision) {
        if (elementId == null || elementId.isBlank()) {
            return;
        }
        CausalTuple incoming = parseCausal(causalNode);
        CausalTuple existingTombstone = readElementTombstone(tx, modelId, elementId);
        if (existingTombstone == null || wins(incoming, existingTombstone)) {
            tx.run("""
                    MERGE (m:Model {modelId: $modelId})
                    MERGE (t:ElementTombstone {modelId: $modelId, id: $id})
                    SET t.lamport = $lamport,
                        t.clientId = $clientId,
                        t.updatedRevision = CASE WHEN $updatedRevision >= 0 THEN $updatedRevision ELSE t.updatedRevision END
                    MERGE (m)-[:HAS_ELEMENT_TOMBSTONE]->(t)
                    """, Map.of(
                    "modelId", modelId,
                    "id", elementId,
                    "lamport", incoming.lamport(),
                    "clientId", incoming.clientId(),
                    "updatedRevision", opRevision));
        }
        tx.run("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_ELEMENT]->(n:Element {id: $id})
                DETACH DELETE n
                """, Map.of("modelId", modelId, "id", elementId));
    }

    private CausalTuple readElementTombstone(TransactionContext tx, String modelId, String elementId) {
        var result = tx.run("""
                MATCH (t:ElementTombstone {modelId: $modelId, id: $id})
                RETURN t.lamport AS lamport, t.clientId AS clientId
                """, Map.of("modelId", modelId, "id", elementId));
        if (!result.hasNext()) {
            return null;
        }
        Record record = result.single();
        long lamport = record.get("lamport").isNull() ? -1L : record.get("lamport").asLong(-1L);
        String clientId = record.get("clientId").isNull() ? "" : record.get("clientId").asString("");
        return new CausalTuple(lamport, clientId);
    }

    private CausalTuple readRelationshipTombstone(TransactionContext tx, String modelId, String relationshipId) {
        var result = tx.run("""
                MATCH (t:RelationshipTombstone {modelId: $modelId, id: $id})
                RETURN t.lamport AS lamport, t.clientId AS clientId
                """, Map.of("modelId", modelId, "id", relationshipId));
        if (!result.hasNext()) {
            return null;
        }
        Record record = result.single();
        long lamport = record.get("lamport").isNull() ? -1L : record.get("lamport").asLong(-1L);
        String clientId = record.get("clientId").isNull() ? "" : record.get("clientId").asString("");
        return new CausalTuple(lamport, clientId);
    }

    private CausalTuple readViewTombstone(TransactionContext tx, String modelId, String viewId) {
        var result = tx.run("""
                MATCH (t:ViewTombstone {modelId: $modelId, id: $id})
                RETURN t.lamport AS lamport, t.clientId AS clientId
                """, Map.of("modelId", modelId, "id", viewId));
        if (!result.hasNext()) {
            return null;
        }
        Record record = result.single();
        long lamport = record.get("lamport").isNull() ? -1L : record.get("lamport").asLong(-1L);
        String clientId = record.get("clientId").isNull() ? "" : record.get("clientId").asString("");
        return new CausalTuple(lamport, clientId);
    }

    private CausalTuple readViewObjectTombstone(TransactionContext tx, String modelId, String viewObjectId) {
        var result = tx.run("""
                MATCH (t:ViewObjectTombstone {modelId: $modelId, id: $id})
                RETURN t.lamport AS lamport, t.clientId AS clientId
                """, Map.of("modelId", modelId, "id", viewObjectId));
        if (!result.hasNext()) {
            return null;
        }
        Record record = result.single();
        long lamport = record.get("lamport").isNull() ? -1L : record.get("lamport").asLong(-1L);
        String clientId = record.get("clientId").isNull() ? "" : record.get("clientId").asString("");
        return new CausalTuple(lamport, clientId);
    }

    private CausalTuple readConnectionTombstone(TransactionContext tx, String modelId, String connectionId) {
        var result = tx.run("""
                MATCH (t:ConnectionTombstone {modelId: $modelId, id: $id})
                RETURN t.lamport AS lamport, t.clientId AS clientId
                """, Map.of("modelId", modelId, "id", connectionId));
        if (!result.hasNext()) {
            return null;
        }
        Record record = result.single();
        long lamport = record.get("lamport").isNull() ? -1L : record.get("lamport").asLong(-1L);
        String clientId = record.get("clientId").isNull() ? "" : record.get("clientId").asString("");
        return new CausalTuple(lamport, clientId);
    }

    private CausalTuple readPropertyClock(TransactionContext tx, String modelId, String targetId, String key) {
        var result = tx.run("""
                MATCH (p:PropertyClock {modelId: $modelId, targetId: $targetId, `key`: $key})
                RETURN p.lamport AS lamport, p.clientId AS clientId
                """, Map.of("modelId", modelId, "targetId", targetId, "key", key));
        if (!result.hasNext()) {
            return null;
        }
        Record record = result.single();
        long lamport = record.get("lamport").isNull() ? -1L : record.get("lamport").asLong(-1L);
        String clientId = record.get("clientId").isNull() ? "" : record.get("clientId").asString("");
        return new CausalTuple(lamport, clientId);
    }

    private PropertyClockState readPropertyClockState(TransactionContext tx, String modelId, String targetId, String key) {
        var result = tx.run("""
                MATCH (p:PropertyClock {modelId: $modelId, targetId: $targetId, `key`: $key})
                RETURN p.lamport AS lamport, p.clientId AS clientId, coalesce(p.deleted, false) AS deleted
                """, Map.of("modelId", modelId, "targetId", targetId, "key", key));
        if (!result.hasNext()) {
            return null;
        }
        Record record = result.single();
        long lamport = record.get("lamport").isNull() ? -1L : record.get("lamport").asLong(-1L);
        String clientId = record.get("clientId").isNull() ? "" : record.get("clientId").asString("");
        boolean deleted = record.get("deleted").asBoolean(false);
        return new PropertyClockState(new CausalTuple(lamport, clientId), deleted);
    }

    private String propertySetClockStorageKey(String key, String member) {
        String encodedMember = Base64.getUrlEncoder().withoutPadding().encodeToString(member.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return propertySetClockPrefix(key) + encodedMember;
    }

    private String propertySetClockPrefix(String key) {
        return ORSET_CLOCK_PREFIX + key + ":";
    }

    private String viewObjectChildClockStorageKey(String childViewObjectId) {
        String encodedMember = Base64.getUrlEncoder().withoutPadding().encodeToString(childViewObjectId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return VIEWOBJECT_CHILD_CLOCK_PREFIX + encodedMember;
    }

    private String viewObjectChildClockPrefix() {
        return VIEWOBJECT_CHILD_CLOCK_PREFIX;
    }

    private String decodePropertySetMember(String storageKey, String prefix) {
        if (storageKey == null || !storageKey.startsWith(prefix)) {
            return null;
        }
        String encoded = storageKey.substring(prefix.length());
        if (encoded.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void rematerializeViewObjectChildMembersForParent(TransactionContext tx, String modelId, String parentViewObjectId) {
        String prefix = viewObjectChildClockPrefix();
        List<String> childIds = tx.run("""
                        MATCH (p:PropertyClock {modelId: $modelId, targetId: $targetId})
                        WHERE p.`key` STARTS WITH $prefix
                          AND coalesce(p.deleted, false) = false
                        RETURN p.`key` AS clockKey
                        """, Map.of("modelId", modelId, "targetId", parentViewObjectId, "prefix", prefix))
                .list(record -> decodePropertySetMember(record.get("clockKey").asString(""), prefix))
                .stream()
                .filter(v -> v != null && !v.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();

        tx.run("""
                MATCH (parent:ViewObject {modelId: $modelId, id: $parentId})
                OPTIONAL MATCH (parent)-[old:CHILD_MEMBER]->(:ViewObject)
                DELETE old
                """, Map.of("modelId", modelId, "parentId", parentViewObjectId));

        if (childIds.isEmpty()) {
            return;
        }

        tx.run("""
                MATCH (parent:ViewObject {modelId: $modelId, id: $parentId})
                UNWIND $childIds AS childId
                MATCH (child:ViewObject {modelId: $modelId, id: childId})
                WHERE child.id <> parent.id
                MERGE (parent)-[:CHILD_MEMBER]->(child)
                """, Map.of("modelId", modelId, "parentId", parentViewObjectId, "childIds", childIds));
    }

    private void rematerializeViewObjectChildMembersForChild(TransactionContext tx, String modelId, String childViewObjectId) {
        String clockKey = viewObjectChildClockStorageKey(childViewObjectId);
        List<String> parentIds = tx.run("""
                        MATCH (p:PropertyClock {modelId: $modelId, `key`: $key})
                        WHERE coalesce(p.deleted, false) = false
                        RETURN DISTINCT p.targetId AS parentId
                        """, Map.of("modelId", modelId, "key", clockKey))
                .list(record -> record.get("parentId").asString(""))
                .stream()
                .filter(id -> id != null && !id.isBlank())
                .toList();
        for (String parentId : parentIds) {
            rematerializeViewObjectChildMembersForParent(tx, modelId, parentId);
        }
    }

    private void ensureFolderExists(TransactionContext tx, String modelId, String folderId, String folderType) {
        if (folderId == null || folderId.isBlank()) {
            return;
        }
        String effectiveType = folderType == null || folderType.isBlank() ? rootFolderType(folderId) : folderType;
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (f:Folder {modelId: $modelId, id: $id})
                ON CREATE SET f.folderType = $folderType,
                              f.name = coalesce(f.name, $folderType)
                MERGE (m)-[:HAS_FOLDER]->(f)
                """, Map.of(
                "modelId", modelId,
                "id", folderId,
                "folderType", effectiveType == null || effectiveType.isBlank() ? "USER" : effectiveType));
    }

    private boolean isRootFolder(String folderId) {
        return folderId != null && folderId.startsWith("folder:root-");
    }

    private String rootFolderType(String folderId) {
        if (!isRootFolder(folderId)) {
            return null;
        }
        return folderId.substring("folder:root-".length()).replace('-', '_').toUpperCase();
    }

    private String jsonText(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() ? node.toString() : null;
    }

    private String nullableText(JsonNode node, String field) {
        if (!node.has(field) || node.path(field).isNull()) {
            return null;
        }
        return node.path(field).asText();
    }

    private Object jsonScalar(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.toString();
    }

    private JsonNode parseJsonOrNull(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return JsonNodeFactory.instance.nullNode();
        }
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            LOG.warn("Failed parsing stored notation json", e);
            return JsonNodeFactory.instance.nullNode();
        }
    }

    private record CausalTuple(long lamport, String clientId) {
    }

    private record PropertyClockState(CausalTuple clock, boolean deleted) {
    }
}
