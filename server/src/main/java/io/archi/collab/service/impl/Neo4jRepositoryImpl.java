package io.archi.collab.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archi.collab.model.RevisionRange;
import io.archi.collab.service.Neo4jRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class Neo4jRepositoryImpl implements Neo4jRepository {
    private static final Logger LOG = LoggerFactory.getLogger(Neo4jRepositoryImpl.class);

    @ConfigProperty(name = "app.neo4j.uri", defaultValue = "bolt://localhost:7687")
    String uri;

    @ConfigProperty(name = "app.neo4j.username", defaultValue = "neo4j")
    String username;

    @ConfigProperty(name = "app.neo4j.password", defaultValue = "devpassword")
    String password;

    @Inject
    ObjectMapper objectMapper;

    private Driver driver;

    @PostConstruct
    void init() {
        driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        LOG.info("Neo4j repository ready at {}", uri);
    }

    @PreDestroy
    void close() {
        if(driver != null) {
            driver.close();
        }
    }

    @Override
    public void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
        if(driver == null) {
            return;
        }
        LOG.debug("appendOpLog: modelId={} opBatchId={} range={}..{}",
                modelId, opBatchId, range.from(), range.to());
        try(var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        CREATE (c:Commit {
                          modelId: $modelId,
                          revisionFrom: $from,
                          revisionTo: $to,
                          opBatchId: $opBatchId,
                          ts: $ts
                        })
                        MERGE (m)-[:HAS_COMMIT]->(c)
                        WITH c
                        OPTIONAL MATCH (prev:Commit {modelId: $modelId, revisionTo: $prevTo})
                        FOREACH (_ IN CASE WHEN prev IS NULL THEN [] ELSE [1] END | MERGE (prev)-[:NEXT]->(c))
                        """,
                        Map.of(
                                "modelId", modelId,
                                "from", range.from(),
                                "to", range.to(),
                                "prevTo", range.from() - 1,
                                "opBatchId", opBatchId,
                                "ts", opBatch.path("timestamp").asText()
                        ));

                int seq = 0;
                for(JsonNode op : opBatch.path("ops")) {
                    tx.run("""
                            MATCH (c:Commit {opBatchId: $opBatchId})
                            CREATE (o:Op {
                              seq: $seq,
                              type: $type,
                              targetId: $targetId,
                              payloadJson: $payloadJson
                            })
                            CREATE (c)-[:HAS_OP]->(o)
                            """, Map.of(
                            "opBatchId", opBatchId,
                            "seq", seq++,
                            "type", op.path("type").asText(),
                            "targetId", deriveTargetId(op),
                            "payloadJson", op.toString()
                    ));
                }

                return null;
            });
        }
        catch(Exception e) {
            LOG.warn("appendOpLog failed for model={} batch={}", modelId, opBatchId, e);
        }
    }

    @Override
    public void applyToMaterializedState(String modelId, JsonNode opBatch) {
        if(driver == null) {
            return;
        }
        int opCount = opBatch.path("ops").isArray() ? opBatch.path("ops").size() : 0;
        LOG.debug("applyToMaterializedState: modelId={} opCount={}", modelId, opCount);
        try(var session = driver.session()) {
            session.executeWrite(tx -> {
                for(JsonNode op : opBatch.path("ops")) {
                    applyOp(tx, modelId, op);
                }
                return null;
            });
        }
        catch(Exception e) {
            LOG.warn("applyToMaterializedState failed for model={}", modelId, e);
        }
    }

    @Override
    public void updateHeadRevision(String modelId, long headRevision) {
        if(driver == null) {
            return;
        }
        LOG.debug("updateHeadRevision: modelId={} headRevision={}", modelId, headRevision);
        try(var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        SET m.headRevision = $headRevision
                        """, Map.of("modelId", modelId, "headRevision", headRevision));
                return null;
            });
        }
        catch(Exception e) {
            LOG.warn("updateHeadRevision failed for model={} head={}", modelId, headRevision, e);
        }
    }

    @Override
    public long readHeadRevision(String modelId) {
        if(driver == null) {
            return 0L;
        }
        try(var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        RETURN coalesce(m.headRevision, 0) AS headRevision
                        """, Map.of("modelId", modelId));
                if(!result.hasNext()) {
                    return 0L;
                }
                return result.next().get("headRevision").asLong(0L);
            });
        }
        catch(Exception e) {
            LOG.warn("readHeadRevision failed for model={}", modelId, e);
            return 0L;
        }
    }

    @Override
    public JsonNode loadSnapshot(String modelId) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("format", "archimate-materialized-v1");
        snapshot.put("modelId", modelId);
        snapshot.put("headRevision", readHeadRevision(modelId));
        snapshot.set("elements", objectMapper.createArrayNode());
        snapshot.set("relationships", objectMapper.createArrayNode());
        snapshot.set("views", objectMapper.createArrayNode());
        snapshot.set("viewObjects", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());

        if(driver == null) {
            return snapshot;
        }

        try(var session = driver.session()) {
            ArrayNode elements = loadElements(session, modelId);
            ArrayNode relationships = loadRelationships(session, modelId);
            ArrayNode views = loadViews(session, modelId);
            ArrayNode viewObjects = loadViewObjects(session, modelId);
            ArrayNode connections = loadConnections(session, modelId);
            snapshot.set("elements", elements);
            snapshot.set("relationships", relationships);
            snapshot.set("views", views);
            snapshot.set("viewObjects", viewObjects);
            snapshot.set("connections", connections);
        }
        catch(Exception e) {
            LOG.warn("loadSnapshot failed for model={}", modelId, e);
        }

        return snapshot;
    }

    @Override
    public JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive) {
        ArrayNode opBatches = objectMapper.createArrayNode();
        if(driver == null || fromRevisionInclusive > toRevisionInclusive) {
            return opBatches;
        }
        LOG.debug("loadOpBatches: modelId={} range={}..{}",
                modelId, fromRevisionInclusive, toRevisionInclusive);
        try(var session = driver.session()) {
            List<Record> records = session.executeRead(tx -> tx.run("""
                            MATCH (c:Commit {modelId: $modelId})
                            WHERE c.revisionFrom >= $fromRevision AND c.revisionTo <= $toRevision
                            OPTIONAL MATCH (c)-[:HAS_OP]->(o:Op)
                            WITH c, o
                            ORDER BY c.revisionFrom ASC, o.seq ASC
                            RETURN c.opBatchId AS opBatchId,
                                   c.revisionFrom AS revisionFrom,
                                   c.revisionTo AS revisionTo,
                                   c.ts AS ts,
                                   collect(o.payloadJson) AS opPayloads
                            ORDER BY revisionFrom ASC
                            """, Map.of(
                                    "modelId", modelId,
                                    "fromRevision", fromRevisionInclusive,
                                    "toRevision", toRevisionInclusive
                    ))
                    .list());

            for(Record record : records) {
                ObjectNode opBatch = objectMapper.createObjectNode();
                long revisionFrom = record.get("revisionFrom").asLong();
                long revisionTo = record.get("revisionTo").asLong();
                opBatch.put("modelId", modelId);
                opBatch.put("opBatchId", record.get("opBatchId").asString(""));
                opBatch.put("baseRevision", Math.max(0, revisionFrom - 1));

                ObjectNode assignedRange = objectMapper.createObjectNode();
                assignedRange.put("from", revisionFrom);
                assignedRange.put("to", revisionTo);
                opBatch.set("assignedRevisionRange", assignedRange);
                opBatch.put("timestamp", record.get("ts").asString(""));

                ArrayNode ops = objectMapper.createArrayNode();
                for(var payloadValue : record.get("opPayloads").values()) {
                    if(payloadValue == null || payloadValue.isNull()) {
                        continue;
                    }
                    String payload = payloadValue.asString(null);
                    if(payload == null || payload.isBlank()) {
                        continue;
                    }
                    try {
                        ops.add(objectMapper.readTree(payload));
                    }
                    catch(Exception e) {
                        LOG.warn("Skipping malformed op payload while loading checkout delta: modelId={} opBatchId={}",
                                modelId, record.get("opBatchId").asString(""), e);
                    }
                }
                opBatch.set("ops", ops);
                opBatches.add(opBatch);
            }
        }
        catch(Exception e) {
            LOG.warn("loadOpBatches failed for model={} range={}..{}",
                    modelId, fromRevisionInclusive, toRevisionInclusive, e);
        }

        return opBatches;
    }

    @Override
    public boolean elementExists(String modelId, String elementId) {
        if(elementId == null || elementId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_ELEMENT]->(:Element {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, elementId);
    }

    @Override
    public boolean relationshipExists(String modelId, String relationshipId) {
        if(relationshipId == null || relationshipId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_REL]->(:Relationship {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, relationshipId);
    }

    @Override
    public boolean viewExists(String modelId, String viewId) {
        if(viewId == null || viewId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, viewId);
    }

    @Override
    public boolean viewObjectExists(String modelId, String viewObjectId) {
        if(viewObjectId == null || viewObjectId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, viewObjectId);
    }

    @Override
    public boolean connectionExists(String modelId, String connectionId) {
        if(connectionId == null || connectionId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:Connection {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, connectionId);
    }

    private void applyOp(TransactionContext tx, String modelId, JsonNode op) {
        String type = op.path("type").asText("");
        switch(type) {
            case "CreateElement" -> createElement(tx, modelId, op.path("element"));
            case "UpdateElement" -> updateSimpleNode(tx, "Element", op.path("elementId").asText(), op.path("patch"));
            case "DeleteElement" -> deleteNode(tx, "Element", op.path("elementId").asText());
            case "CreateRelationship" -> createRelationship(tx, modelId, op.path("relationship"));
            case "UpdateRelationship" -> updateRelationship(tx, op.path("relationshipId").asText(), op.path("patch"));
            case "DeleteRelationship" -> deleteNode(tx, "Relationship", op.path("relationshipId").asText());
            case "SetProperty" -> setProperty(tx, op.path("targetId").asText(), op.path("key").asText(), op.path("value"));
            case "UnsetProperty" -> unsetProperty(tx, op.path("targetId").asText(), op.path("key").asText());
            case "CreateView" -> createView(tx, modelId, op.path("view"));
            case "UpdateView" -> updateView(tx, op.path("viewId").asText(), op.path("patch"));
            case "DeleteView" -> deleteNode(tx, "View", op.path("viewId").asText());
            case "CreateViewObject" -> createViewObject(tx, op.path("viewObject"));
            case "UpdateViewObjectOpaque" -> updateNotation(tx, "ViewObject", op.path("viewObjectId").asText(), op.path("notationJson"));
            case "DeleteViewObject" -> deleteNode(tx, "ViewObject", op.path("viewObjectId").asText());
            case "CreateConnection" -> createConnection(tx, op.path("connection"));
            case "UpdateConnectionOpaque" -> updateNotation(tx, "Connection", op.path("connectionId").asText(), op.path("notationJson"));
            case "DeleteConnection" -> deleteNode(tx, "Connection", op.path("connectionId").asText());
            default -> {
                // Unknown ops are retained in op-log but ignored for materialized writes.
            }
        }
    }

    private void createElement(TransactionContext tx, String modelId, JsonNode element) {
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", element.path("id").asText());
        params.put("archimateType", element.path("archimateType").asText());
        params.put("name", nullableText(element, "name"));
        params.put("documentation", nullableText(element, "documentation"));
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (e:Element {id: $id})
                SET e.archimateType = $archimateType,
                    e.name = $name,
                    e.documentation = $documentation
                MERGE (m)-[:HAS_ELEMENT]->(e)
                """, params);
    }

    private void createRelationship(TransactionContext tx, String modelId, JsonNode relationship) {
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", relationship.path("id").asText());
        params.put("archimateType", relationship.path("archimateType").asText());
        params.put("name", nullableText(relationship, "name"));
        params.put("documentation", nullableText(relationship, "documentation"));
        params.put("sourceId", relationship.path("sourceId").asText());
        params.put("targetId", relationship.path("targetId").asText());
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (r:Relationship {id: $id})
                SET r.archimateType = $archimateType,
                    r.name = $name,
                    r.documentation = $documentation
                MERGE (m)-[:HAS_REL]->(r)
                WITH r
                OPTIONAL MATCH (s:Element {id: $sourceId})
                FOREACH (_ IN CASE WHEN s IS NULL THEN [] ELSE [1] END | MERGE (r)-[:SOURCE]->(s))
                WITH r
                OPTIONAL MATCH (t:Element {id: $targetId})
                FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END | MERGE (r)-[:TARGET]->(t))
                """, params);
    }

    private void updateRelationship(TransactionContext tx, String relationshipId, JsonNode patch) {
        updateSimpleNode(tx, "Relationship", relationshipId, patch);

        if(patch.has("sourceId")) {
            tx.run("""
                    MATCH (r:Relationship {id: $id})
                    OPTIONAL MATCH (r)-[old:SOURCE]->()
                    DELETE old
                    WITH r
                    OPTIONAL MATCH (s:Element {id: $sourceId})
                    FOREACH (_ IN CASE WHEN s IS NULL THEN [] ELSE [1] END | MERGE (r)-[:SOURCE]->(s))
                    """, Map.of("id", relationshipId, "sourceId", patch.path("sourceId").asText()));
        }
        if(patch.has("targetId")) {
            tx.run("""
                    MATCH (r:Relationship {id: $id})
                    OPTIONAL MATCH (r)-[old:TARGET]->()
                    DELETE old
                    WITH r
                    OPTIONAL MATCH (t:Element {id: $targetId})
                    FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END | MERGE (r)-[:TARGET]->(t))
                    """, Map.of("id", relationshipId, "targetId", patch.path("targetId").asText()));
        }
    }

    private void createView(TransactionContext tx, String modelId, JsonNode view) {
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("id", view.path("id").asText());
        params.put("name", view.path("name").asText());
        params.put("notationJson", jsonText(view.path("notationJson")));
        tx.run("""
                MERGE (m:Model {modelId: $modelId})
                MERGE (v:View {id: $id})
                SET v.name = $name,
                    v.notationJson = $notationJson
                MERGE (m)-[:HAS_VIEW]->(v)
                """, params);
    }

    private void updateView(TransactionContext tx, String viewId, JsonNode patch) {
        updateSimpleNode(tx, "View", viewId, patch);
        if(patch.has("notationJson")) {
            updateNotation(tx, "View", viewId, patch.path("notationJson"));
        }
    }

    private void createViewObject(TransactionContext tx, JsonNode viewObject) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", viewObject.path("id").asText());
        params.put("viewId", viewObject.path("viewId").asText());
        params.put("representsId", viewObject.path("representsId").asText());
        params.put("notationJson", jsonText(viewObject.path("notationJson")));
        tx.run("""
                MATCH (v:View {id: $viewId})
                MERGE (vo:ViewObject {id: $id})
                SET vo.notationJson = $notationJson
                MERGE (v)-[:CONTAINS]->(vo)
                WITH vo
                OPTIONAL MATCH (e:Element {id: $representsId})
                FOREACH (_ IN CASE WHEN e IS NULL THEN [] ELSE [1] END | MERGE (vo)-[:REPRESENTS]->(e))
                """, params);
    }

    private void createConnection(TransactionContext tx, JsonNode connection) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", connection.path("id").asText());
        params.put("viewId", connection.path("viewId").asText());
        params.put("representsId", connection.path("representsId").asText());
        params.put("sourceViewObjectId", connection.path("sourceViewObjectId").asText());
        params.put("targetViewObjectId", connection.path("targetViewObjectId").asText());
        params.put("notationJson", jsonText(connection.path("notationJson")));
        tx.run("""
                MATCH (v:View {id: $viewId})
                MERGE (c:Connection {id: $id})
                SET c.notationJson = $notationJson
                MERGE (v)-[:CONTAINS]->(c)
                WITH c
                OPTIONAL MATCH (r:Relationship {id: $representsId})
                FOREACH (_ IN CASE WHEN r IS NULL THEN [] ELSE [1] END | MERGE (c)-[:REPRESENTS]->(r))
                WITH c
                OPTIONAL MATCH (f:ViewObject {id: $sourceViewObjectId})
                FOREACH (_ IN CASE WHEN f IS NULL THEN [] ELSE [1] END | MERGE (c)-[:FROM]->(f))
                WITH c
                OPTIONAL MATCH (t:ViewObject {id: $targetViewObjectId})
                FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END | MERGE (c)-[:TO]->(t))
                """, params);
    }

    private void updateNotation(TransactionContext tx, String label, String id, JsonNode notationJson) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("notationJson", jsonText(notationJson));
        tx.run("MATCH (n:" + label + " {id: $id}) SET n.notationJson = $notationJson",
                params);
    }

    private void setProperty(TransactionContext tx, String targetId, String key, JsonNode value) {
        if(targetId == null || targetId.isBlank() || key == null || key.isBlank()) {
            return;
        }
        Object scalarValue = jsonScalar(value);
        Map<String, Object> params = new HashMap<>();
        params.put("id", targetId);
        params.put("key", key);
        params.put("value", scalarValue);
        tx.run("MATCH (n {id: $id}) SET n[$key] = $value",
                params);
    }

    private void unsetProperty(TransactionContext tx, String targetId, String key) {
        if(targetId == null || targetId.isBlank() || key == null || key.isBlank()) {
            return;
        }
        tx.run("MATCH (n {id: $id}) REMOVE n[$key]", Map.of("id", targetId, "key", key));
    }

    private void updateSimpleNode(TransactionContext tx, String label, String id, JsonNode patch) {
        if(id == null || id.isBlank() || patch == null || !patch.isObject()) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        StringBuilder set = new StringBuilder();

        if(patch.has("name")) {
            params.put("name", nullableText(patch, "name"));
            set.append("n.name = $name, ");
        }
        if(patch.has("documentation")) {
            params.put("documentation", nullableText(patch, "documentation"));
            set.append("n.documentation = $documentation, ");
        }

        if(set.length() == 0) {
            return;
        }
        set.setLength(set.length() - 2);
        tx.run("MATCH (n:" + label + " {id: $id}) SET " + set, params);
    }

    private void deleteNode(TransactionContext tx, String label, String id) {
        tx.run("MATCH (n:" + label + " {id: $id}) DETACH DELETE n", Map.of("id", id));
    }

    private String deriveTargetId(JsonNode op) {
        if(op.has("elementId")) {
            return op.path("elementId").asText();
        }
        if(op.has("relationshipId")) {
            return op.path("relationshipId").asText();
        }
        if(op.has("viewId")) {
            return op.path("viewId").asText();
        }
        if(op.has("viewObjectId")) {
            return op.path("viewObjectId").asText();
        }
        if(op.has("connectionId")) {
            return op.path("connectionId").asText();
        }
        if(op.has("targetId")) {
            return op.path("targetId").asText();
        }
        return "";
    }

    private String jsonText(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() ? node.toString() : null;
    }

    private String nullableText(JsonNode node, String field) {
        if(!node.has(field) || node.path(field).isNull()) {
            return null;
        }
        return node.path(field).asText();
    }

    private Object jsonScalar(JsonNode value) {
        if(value == null || value.isNull()) {
            return null;
        }
        if(value.isNumber()) {
            return value.numberValue();
        }
        if(value.isBoolean()) {
            return value.booleanValue();
        }
        if(value.isTextual()) {
            return value.asText();
        }
        return value.toString();
    }

    private boolean exists(String cypher, String modelId, String id) {
        if(driver == null) {
            return false;
        }
        try(var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(cypher, Map.of("modelId", modelId, "id", id));
                if(!result.hasNext()) {
                    return false;
                }
                return result.next().get("exists").asBoolean(false);
            });
        }
        catch(Exception e) {
            LOG.warn("Exists query failed for model={} id={}", modelId, id, e);
            return false;
        }
    }

    private ArrayNode loadElements(org.neo4j.driver.Session session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.executeRead(tx -> tx.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_ELEMENT]->(e:Element)
                        RETURN e.id AS id,
                               e.archimateType AS archimateType,
                               e.name AS name,
                               e.documentation AS documentation
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list());
        for(Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            node.put("archimateType", record.get("archimateType").asString(""));
            putNullableText(node, "name", record.get("name").asString(null));
            putNullableText(node, "documentation", record.get("documentation").asString(null));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadRelationships(org.neo4j.driver.Session session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.executeRead(tx -> tx.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_REL]->(r:Relationship)
                        OPTIONAL MATCH (r)-[:SOURCE]->(src:Element)
                        OPTIONAL MATCH (r)-[:TARGET]->(dst:Element)
                        RETURN r.id AS id,
                               r.archimateType AS archimateType,
                               r.name AS name,
                               r.documentation AS documentation,
                               src.id AS sourceId,
                               dst.id AS targetId
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list());
        for(Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            node.put("archimateType", record.get("archimateType").asString(""));
            putNullableText(node, "name", record.get("name").asString(null));
            putNullableText(node, "documentation", record.get("documentation").asString(null));
            putNullableText(node, "sourceId", record.get("sourceId").asString(null));
            putNullableText(node, "targetId", record.get("targetId").asString(null));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadViews(org.neo4j.driver.Session session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.executeRead(tx -> tx.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(v:View)
                        RETURN v.id AS id,
                               v.name AS name,
                               v.notationJson AS notationJson
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list());
        for(Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            putNullableText(node, "name", record.get("name").asString(null));
            node.set("notationJson", parseJsonOrNull(record.get("notationJson").asString(null)));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadViewObjects(org.neo4j.driver.Session session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.executeRead(tx -> tx.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(v:View)-[:CONTAINS]->(vo:ViewObject)
                        OPTIONAL MATCH (vo)-[:REPRESENTS]->(e:Element)
                        RETURN vo.id AS id,
                               v.id AS viewId,
                               e.id AS representsId,
                               vo.notationJson AS notationJson
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list());
        for(Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            putNullableText(node, "viewId", record.get("viewId").asString(null));
            putNullableText(node, "representsId", record.get("representsId").asString(null));
            node.set("notationJson", parseJsonOrNull(record.get("notationJson").asString(null)));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadConnections(org.neo4j.driver.Session session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.executeRead(tx -> tx.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(v:View)-[:CONTAINS]->(c:Connection)
                        OPTIONAL MATCH (c)-[:REPRESENTS]->(r:Relationship)
                        OPTIONAL MATCH (c)-[:FROM]->(f:ViewObject)
                        OPTIONAL MATCH (c)-[:TO]->(t:ViewObject)
                        RETURN c.id AS id,
                               v.id AS viewId,
                               r.id AS representsId,
                               f.id AS sourceViewObjectId,
                               t.id AS targetViewObjectId,
                               c.notationJson AS notationJson
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list());
        for(Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            putNullableText(node, "viewId", record.get("viewId").asString(null));
            putNullableText(node, "representsId", record.get("representsId").asString(null));
            putNullableText(node, "sourceViewObjectId", record.get("sourceViewObjectId").asString(null));
            putNullableText(node, "targetViewObjectId", record.get("targetViewObjectId").asString(null));
            node.set("notationJson", parseJsonOrNull(record.get("notationJson").asString(null)));
            array.add(node);
        }
        return array;
    }

    private JsonNode parseJsonOrNull(String rawJson) {
        if(rawJson == null || rawJson.isBlank()) {
            return JsonNodeFactory.instance.nullNode();
        }
        try {
            return objectMapper.readTree(rawJson);
        }
        catch(Exception e) {
            LOG.warn("Failed parsing stored notation json", e);
            return JsonNodeFactory.instance.nullNode();
        }
    }

    private void putNullableText(ObjectNode node, String field, String value) {
        if(value == null) {
            node.putNull(field);
            return;
        }
        node.put(field, value);
    }
}
