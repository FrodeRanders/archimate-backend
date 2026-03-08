package io.archi.collab.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

final class Neo4jReadSupport {
    private static final Logger LOG = LoggerFactory.getLogger(Neo4jReadSupport.class);

    private final ObjectMapper objectMapper;

    Neo4jReadSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode loadSnapshot(Session session, String modelId, long headRevision) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("format", "archimate-materialized-v1");
        snapshot.put("modelId", modelId);
        snapshot.put("headRevision", headRevision);
        snapshot.set("elements", objectMapper.createArrayNode());
        snapshot.set("relationships", objectMapper.createArrayNode());
        snapshot.set("views", objectMapper.createArrayNode());
        snapshot.set("viewObjects", objectMapper.createArrayNode());
        snapshot.set("viewObjectChildMembers", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());

        if (session == null) {
            return snapshot;
        }

        // Snapshot export is assembled from the materialized graph rather than replaying ops on demand so
        // checkout and admin export stay fast even when the op-log is large.
        ArrayNode elements = loadElements(session, modelId);
        ArrayNode relationships = loadRelationships(session, modelId);
        ArrayNode views = loadViews(session, modelId);
        ArrayNode viewObjects = loadViewObjects(session, modelId);
        ArrayNode viewObjectChildMembers = loadViewObjectChildMembers(session, modelId);
        ArrayNode connections = loadConnections(session, modelId);
        snapshot.set("elements", elements);
        snapshot.set("relationships", relationships);
        snapshot.set("views", views);
        snapshot.set("viewObjects", viewObjects);
        snapshot.set("viewObjectChildMembers", viewObjectChildMembers);
        snapshot.set("connections", connections);

        return snapshot;
    }

    boolean isMaterializedStateConsistent(Session session, String modelId, long expectedHeadRevision) {
        boolean modelExists = session.executeRead(tx -> tx.run("""
                MATCH (m:Model {modelId: $modelId})
                RETURN count(m) > 0 AS exists
                """, Map.of("modelId", modelId)).single().get("exists").asBoolean(false));

        if (!modelExists) {
            long latestCommitRevision = session.executeRead(tx -> tx.run("""
                            MATCH (c:Commit {modelId: $modelId})
                            RETURN coalesce(max(c.revisionTo), 0) AS latestCommitRevision
                            """, Map.of("modelId", modelId))
                    .single()
                    .get("latestCommitRevision")
                    .asLong(0L));
            return expectedHeadRevision == 0L && latestCommitRevision == 0L;
        }

        return session.executeRead(tx -> {
            // Consistency is stronger than "head revision matches": all materialized references must still point
            // at live nodes inside the same model subgraph.
            var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        OPTIONAL MATCH (c:Commit {modelId: $modelId})
                        WITH m, coalesce(max(c.revisionTo), 0) AS latestCommitRevision
                        
                        OPTIONAL MATCH (m)-[:HAS_REL]->(rel:Relationship)
                        WHERE rel.sourceId IS NOT NULL AND rel.sourceId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: rel.sourceId}) }
                        WITH m, latestCommitRevision, count(rel) AS danglingRelSources
                        
                        OPTIONAL MATCH (m)-[:HAS_REL]->(rel2:Relationship)
                        WHERE rel2.targetId IS NOT NULL AND rel2.targetId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: rel2.targetId}) }
                        WITH m, latestCommitRevision, danglingRelSources, count(rel2) AS danglingRelTargets
                        
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v1:View)-[:CONTAINS]->(vo:ViewObject)
                        WHERE vo.representsId IS NOT NULL AND vo.representsId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: vo.representsId}) }
                        WITH m, latestCommitRevision, danglingRelSources, danglingRelTargets, count(vo) AS danglingViewObjectRepresents
                        
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v2:View)-[:CONTAINS]->(conn:Connection)
                        WHERE conn.representsId IS NOT NULL AND conn.representsId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_REL]->(:Relationship {id: conn.representsId}) }
                        WITH m, latestCommitRevision, danglingRelSources, danglingRelTargets, danglingViewObjectRepresents, count(conn) AS danglingConnectionRepresents
                        
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v3:View)-[:CONTAINS]->(conn2:Connection)
                        WHERE conn2.sourceViewObjectId IS NOT NULL AND conn2.sourceViewObjectId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: conn2.sourceViewObjectId}) }
                        WITH m, latestCommitRevision, danglingRelSources, danglingRelTargets, danglingViewObjectRepresents, danglingConnectionRepresents, count(conn2) AS danglingConnectionSources
                        
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v4:View)-[:CONTAINS]->(conn3:Connection)
                        WHERE conn3.targetViewObjectId IS NOT NULL AND conn3.targetViewObjectId <> ''
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: conn3.targetViewObjectId}) }
                        RETURN coalesce(m.headRevision, 0) AS headRevision,
                               latestCommitRevision,
                               danglingRelSources,
                               danglingRelTargets,
                               danglingViewObjectRepresents,
                               danglingConnectionRepresents,
                               danglingConnectionSources,
                               count(conn3) AS danglingConnectionTargets
                    """, Map.of("modelId", modelId));
            if (!result.hasNext()) {
                long latestCommitRevision = tx.run("""
                                MATCH (c:Commit {modelId: $modelId})
                                RETURN coalesce(max(c.revisionTo), 0) AS latestCommitRevision
                                """, Map.of("modelId", modelId))
                        .single()
                        .get("latestCommitRevision")
                        .asLong(0L);
                return expectedHeadRevision == 0L && latestCommitRevision == 0L;
            }
            Record record = result.next();

            long actualHeadRevision = record.get("headRevision").asLong(0L);
            long latestCommitRevision = record.get("latestCommitRevision").asLong(0L);
            long danglingRelSources = record.get("danglingRelSources").asLong(0L);
            long danglingRelTargets = record.get("danglingRelTargets").asLong(0L);
            long danglingViewObjectRepresents = record.get("danglingViewObjectRepresents").asLong(0L);
            long danglingConnectionRepresents = record.get("danglingConnectionRepresents").asLong(0L);
            long danglingConnectionSources = record.get("danglingConnectionSources").asLong(0L);
            long danglingConnectionTargets = record.get("danglingConnectionTargets").asLong(0L);

            return actualHeadRevision == expectedHeadRevision
                    && actualHeadRevision == latestCommitRevision
                    && danglingRelSources == 0
                    && danglingRelTargets == 0
                    && danglingViewObjectRepresents == 0
                    && danglingConnectionRepresents == 0
                    && danglingConnectionSources == 0
                    && danglingConnectionTargets == 0;
        });
    }

    JsonNode loadOpBatches(Session session, String modelId, long fromRevisionInclusive, long toRevisionInclusive) {
        ArrayNode opBatches = objectMapper.createArrayNode();
        if (session == null || fromRevisionInclusive > toRevisionInclusive) {
            return opBatches;
        }
        LOG.debug("loadOpBatches: modelId={} range={}..{}",
                modelId, fromRevisionInclusive, toRevisionInclusive);
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

        for (Record record : records) {
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
            for (var payloadValue : record.get("opPayloads").values()) {
                if (payloadValue == null || payloadValue.isNull()) {
                    continue;
                }
                String payload = payloadValue.asString(null);
                if (payload == null || payload.isBlank()) {
                    continue;
                }
                try {
                    ops.add(objectMapper.readTree(payload));
                } catch (Exception e) {
                    // Delta checkout should degrade to a shorter/missing batch rather than fail the whole response
                    // because one stored payload is malformed.
                    LOG.warn("Skipping malformed op payload while loading checkout delta: modelId={} opBatchId={}",
                            modelId, record.get("opBatchId").asString(""), e);
                }
            }
            opBatch.set("ops", ops);
            opBatches.add(opBatch);
        }

        return opBatches;
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
        for (Record record : records) {
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
        for (Record record : records) {
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
                               v.documentation AS documentation,
                               v.notationJson AS notationJson
                        ORDER BY id
                        """, Map.of("modelId", modelId))
                .list());
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            putNullableText(node, "name", record.get("name").asString(null));
            putNullableText(node, "documentation", record.get("documentation").asString(null));
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
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", record.get("id").asString(""));
            putNullableText(node, "viewId", record.get("viewId").asString(null));
            putNullableText(node, "representsId", record.get("representsId").asString(null));
            node.set("notationJson", parseJsonOrNull(record.get("notationJson").asString(null)));
            array.add(node);
        }
        return array;
    }

    private ArrayNode loadViewObjectChildMembers(org.neo4j.driver.Session session, String modelId) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<Record> records = session.executeRead(tx -> tx.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(parent:ViewObject)-[:CHILD_MEMBER]->(child:ViewObject)
                        RETURN parent.id AS parentViewObjectId,
                               child.id AS childViewObjectId
                        ORDER BY parentViewObjectId, childViewObjectId
                        """, Map.of("modelId", modelId))
                .list());
        for (Record record : records) {
            ObjectNode node = objectMapper.createObjectNode();
            putNullableText(node, "parentViewObjectId", record.get("parentViewObjectId").asString(null));
            putNullableText(node, "childViewObjectId", record.get("childViewObjectId").asString(null));
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
        for (Record record : records) {
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

    private void putNullableText(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
            return;
        }
        node.put(field, value);
    }

}
