package io.archi.collab.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archi.collab.model.AdminCompactionStatus;
import io.archi.collab.model.ModelCatalogEntry;
import io.archi.collab.model.RevisionRange;
import io.archi.collab.service.Neo4jRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class Neo4jRepositoryImpl implements Neo4jRepository {
    private static final Logger LOG = LoggerFactory.getLogger(Neo4jRepositoryImpl.class);
    private static final String ORSET_CLOCK_PREFIX = "orset:";
    private static final String VIEWOBJECT_CHILD_CLOCK_PREFIX = ORSET_CLOCK_PREFIX + "vo-child:";

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
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        LOG.info("Neo4j repository ready at {}", uri);
    }

    @PreDestroy
    void close() {
        if (driver != null) {
            driver.close();
        }
    }

    @Override
    public void appendOpLog(String modelId, String opBatchId, RevisionRange range, JsonNode opBatch) {
        if (driver == null) {
            return;
        }
        LOG.debug("appendOpLog: modelId={} opBatchId={} range={}..{}",
                modelId, opBatchId, range.from(), range.to());
        try (var session = driver.session()) {
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
                for (JsonNode op : opBatch.path("ops")) {
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
        } catch (Exception e) {
            LOG.warn("appendOpLog failed for model={} batch={}", modelId, opBatchId, e);
        }
    }

    @Override
    public void applyToMaterializedState(String modelId, JsonNode opBatch) {
        if (driver == null) {
            return;
        }
        int opCount = opBatch.path("ops").isArray() ? opBatch.path("ops").size() : 0;
        LOG.debug("applyToMaterializedState: modelId={} opCount={}", modelId, opCount);
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                long firstRevision = opBatch.path("assignedRevisionRange").path("from").asLong(-1L);
                int opIndex = 0;
                for (JsonNode op : opBatch.path("ops")) {
                    // Assign a deterministic per-op revision within the accepted batch range
                    long opRevision = firstRevision < 0 ? -1L : firstRevision + opIndex;
                    applyOp(tx, modelId, op, opRevision);
                    opIndex++;
                }
                return null;
            });
        } catch (Exception e) {
            LOG.warn("applyToMaterializedState failed for model={}", modelId, e);
        }
    }

    @Override
    public void updateHeadRevision(String modelId, long headRevision) {
        if (driver == null) {
            return;
        }
        LOG.debug("updateHeadRevision: modelId={} headRevision={}", modelId, headRevision);
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        SET m.headRevision = $headRevision
                        """, Map.of("modelId", modelId, "headRevision", headRevision));
                return null;
            });
        } catch (Exception e) {
            LOG.warn("updateHeadRevision failed for model={} head={}", modelId, headRevision, e);
        }
    }

    @Override
    public long readHeadRevision(String modelId) {
        if (driver == null) {
            return 0L;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        RETURN coalesce(m.headRevision, 0) AS headRevision
                        """, Map.of("modelId", modelId));
                if (!result.hasNext()) {
                    return 0L;
                }
                return result.next().get("headRevision").asLong(0L);
            });
        } catch (Exception e) {
            LOG.warn("readHeadRevision failed for model={}", modelId, e);
            return 0L;
        }
    }

    @Override
    public ModelCatalogEntry registerModel(String modelId, String modelName) {
        if (driver == null) {
            return new ModelCatalogEntry(modelId, modelName, 0L);
        }
        String normalizedName = normalizeModelName(modelName);
        String now = Instant.now().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("modelName", normalizedName);
        params.put("now", now);
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                Record record = tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        ON CREATE SET m.headRevision = coalesce(m.headRevision, 0),
                                      m.createdAt = $now
                        SET m.registered = true,
                            m.modelName = $modelName,
                            m.updatedAt = $now
                        RETURN m.modelId AS modelId,
                               m.modelName AS modelName,
                               coalesce(m.headRevision, 0) AS headRevision
                        """, params).single();
                return toModelCatalogEntry(record);
            });
        } catch (Exception e) {
            LOG.warn("registerModel failed for model={}", modelId, e);
            return new ModelCatalogEntry(modelId, normalizedName, readHeadRevision(modelId));
        }
    }

    @Override
    public ModelCatalogEntry renameModel(String modelId, String modelName) {
        if (driver == null) {
            return new ModelCatalogEntry(modelId, modelName, 0L);
        }
        String normalizedName = normalizeModelName(modelName);
        String now = Instant.now().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("modelName", normalizedName);
        params.put("now", now);
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        SET m.modelName = $modelName,
                            m.updatedAt = $now,
                            m.registered = true
                        RETURN m.modelId AS modelId,
                               m.modelName AS modelName,
                               coalesce(m.headRevision, 0) AS headRevision
                        """, params);
                if (!result.hasNext()) {
                    return registerModel(modelId, normalizedName);
                }
                return toModelCatalogEntry(result.next());
            });
        } catch (Exception e) {
            LOG.warn("renameModel failed for model={}", modelId, e);
            return new ModelCatalogEntry(modelId, normalizedName, readHeadRevision(modelId));
        }
    }

    @Override
    public String readModelName(String modelId) {
        if (driver == null) {
            return null;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        RETURN m.modelName AS modelName
                        """, Map.of("modelId", modelId));
                if (!result.hasNext()) {
                    return null;
                }
                Value name = result.next().get("modelName");
                return name.isNull() ? null : name.asString(null);
            });
        } catch (Exception e) {
            LOG.warn("readModelName failed for model={}", modelId, e);
            return null;
        }
    }

    @Override
    public List<ModelCatalogEntry> listModelCatalog() {
        if (driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (m:Model)
                        WHERE coalesce(m.registered, false) = true
                        RETURN m.modelId AS modelId,
                               m.modelName AS modelName,
                               coalesce(m.headRevision, 0) AS headRevision
                        ORDER BY m.modelId
                        """);
                List<ModelCatalogEntry> models = new ArrayList<>();
                while (result.hasNext()) {
                    models.add(toModelCatalogEntry(result.next()));
                }
                return models;
            });
        } catch (Exception e) {
            LOG.warn("listModelCatalog failed", e);
            return List.of();
        }
    }

    @Override
    public long readLatestCommitRevision(String modelId) {
        if (driver == null) {
            return 0L;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH (c:Commit {modelId: $modelId})
                        RETURN coalesce(max(c.revisionTo), 0) AS latestRevision
                        """, Map.of("modelId", modelId));
                if (!result.hasNext()) {
                    return 0L;
                }
                return result.next().get("latestRevision").asLong(0L);
            });
        } catch (Exception e) {
            LOG.warn("readLatestCommitRevision failed for model={}", modelId, e);
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
        snapshot.set("viewObjectChildMembers", objectMapper.createArrayNode());
        snapshot.set("connections", objectMapper.createArrayNode());

        if (driver == null) {
            return snapshot;
        }

        try (var session = driver.session()) {
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
        } catch (Exception e) {
            LOG.warn("loadSnapshot failed for model={}", modelId, e);
        }

        return snapshot;
    }

    @Override
    public boolean isMaterializedStateConsistent(String modelId, long expectedHeadRevision) {
        if (driver == null) {
            return true;
        }
        try (var session = driver.session()) {
            boolean modelExists = session.executeRead(tx -> tx.run("""
                    MATCH (m:Model {modelId: $modelId})
                    RETURN count(m) > 0 AS exists
                    """, Map.of("modelId", modelId)).single().get("exists").asBoolean(false));

            if (!modelExists) {
                long latestCommitRevision = readLatestCommitRevision(modelId);
                return expectedHeadRevision == 0L && latestCommitRevision == 0L;
            }

            return session.executeRead(tx -> {
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

                long headRevision = record.get("headRevision").asLong(0L);
                long latestCommitRevision = record.get("latestCommitRevision").asLong(0L);
                long danglingRelSources = record.get("danglingRelSources").asLong(0L);
                long danglingRelTargets = record.get("danglingRelTargets").asLong(0L);
                long danglingViewObjectRepresents = record.get("danglingViewObjectRepresents").asLong(0L);
                long danglingConnectionRepresents = record.get("danglingConnectionRepresents").asLong(0L);
                long danglingConnectionSources = record.get("danglingConnectionSources").asLong(0L);
                long danglingConnectionTargets = record.get("danglingConnectionTargets").asLong(0L);

                return headRevision == expectedHeadRevision
                        && headRevision == latestCommitRevision
                        && danglingRelSources == 0
                        && danglingRelTargets == 0
                        && danglingViewObjectRepresents == 0
                        && danglingConnectionRepresents == 0
                        && danglingConnectionSources == 0
                        && danglingConnectionTargets == 0;
            });
        } catch (Exception e) {
            LOG.warn("isMaterializedStateConsistent failed for model={} expectedHeadRevision={}",
                    modelId, expectedHeadRevision, e);
            return false;
        }
    }

    @Override
    public JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive) {
        ArrayNode opBatches = objectMapper.createArrayNode();
        if (driver == null || fromRevisionInclusive > toRevisionInclusive) {
            return opBatches;
        }
        LOG.debug("loadOpBatches: modelId={} range={}..{}",
                modelId, fromRevisionInclusive, toRevisionInclusive);
        try (var session = driver.session()) {
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
                        LOG.warn("Skipping malformed op payload while loading checkout delta: modelId={} opBatchId={}",
                                modelId, record.get("opBatchId").asString(""), e);
                    }
                }
                opBatch.set("ops", ops);
                opBatches.add(opBatch);
            }
        } catch (Exception e) {
            LOG.warn("loadOpBatches failed for model={} range={}..{}",
                    modelId, fromRevisionInclusive, toRevisionInclusive, e);
        }

        return opBatches;
    }

    @Override
    public AdminCompactionStatus compactMetadata(String modelId, long retainRevisions) {
        long safeRetain = Math.max(0L, retainRevisions);
        long headRevision = readHeadRevision(modelId);
        long committedHorizonRevision = Math.max(0L, readLatestCommitRevision(modelId));
        long watermarkRevision = Math.max(0L, committedHorizonRevision - safeRetain);
        if (driver == null) {
            return new AdminCompactionStatus(
                    modelId, headRevision, committedHorizonRevision, watermarkRevision, safeRetain,
                    0L, 0L, 0L, 0L, 0L, 0L, false, "Neo4j driver unavailable");
        }

        LOG.info("Compaction start: modelId={} headRevision={} committedHorizonRevision={} watermarkRevision={} retainRevisions={}",
                modelId, headRevision, committedHorizonRevision, watermarkRevision, safeRetain);
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                Map<String, Object> params = Map.of("modelId", modelId, "watermark", watermarkRevision);

                Record opLogCounts = tx.run("""
                        MATCH (c:Commit {modelId: $modelId})
                        WHERE c.revisionTo < $watermark
                        OPTIONAL MATCH (c)-[:HAS_OP]->(o:Op)
                        RETURN count(DISTINCT c) AS commitCount, count(o) AS opCount
                        """, params).single();
                long deletedCommitCount = opLogCounts.get("commitCount").asLong(0L);
                long deletedOpCount = opLogCounts.get("opCount").asLong(0L);

                tx.run("""
                        MATCH (c:Commit {modelId: $modelId})
                        WHERE c.revisionTo < $watermark
                        OPTIONAL MATCH (c)-[:HAS_OP]->(o:Op)
                        DETACH DELETE o, c
                        """, params);

                Record propertyClockCounts = tx.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_PROPERTY_CLOCK]->(p:PropertyClock)
                        WHERE coalesce(p.updatedRevision, 0) <= $watermark
                          AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: p.targetId}) }
                          AND NOT EXISTS { MATCH (m)-[:HAS_REL]->(:Relationship {id: p.targetId}) }
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View {id: p.targetId}) }
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: p.targetId}) }
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:Connection {id: p.targetId}) }
                        RETURN count(p) AS propertyClockCount
                        """, params).single();
                long deletedPropertyClockCount = propertyClockCounts.get("propertyClockCount").asLong(0L);

                tx.run("""
                        MATCH (m:Model {modelId: $modelId})-[:HAS_PROPERTY_CLOCK]->(p:PropertyClock)
                        WHERE coalesce(p.updatedRevision, 0) <= $watermark
                          AND NOT EXISTS { MATCH (m)-[:HAS_ELEMENT]->(:Element {id: p.targetId}) }
                          AND NOT EXISTS { MATCH (m)-[:HAS_REL]->(:Relationship {id: p.targetId}) }
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View {id: p.targetId}) }
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: p.targetId}) }
                          AND NOT EXISTS { MATCH (m)-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:Connection {id: p.targetId}) }
                        DETACH DELETE p
                        """, params);

                Record fieldClockCounts = tx.run("""
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (m:Model {modelId: modelId})-[:HAS_ELEMENT]->(n:Element)
                          UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                          WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                          WHERE lamport >= 0 AND lamport <= watermark
                          RETURN count(*) AS cElement
                        }
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (m:Model {modelId: modelId})-[:HAS_REL]->(n:Relationship)
                          UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                          WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                          WHERE lamport >= 0 AND lamport <= watermark
                          RETURN count(*) AS cRelationship
                        }
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (m:Model {modelId: modelId})-[:HAS_VIEW]->(n:View)
                          UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                          WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                          WHERE lamport >= 0 AND lamport <= watermark
                          RETURN count(*) AS cView
                        }
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (m:Model {modelId: modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(n:ViewObject)
                          UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                          WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                          WHERE lamport >= 0 AND lamport <= watermark
                          RETURN count(*) AS cViewObject
                        }
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (m:Model {modelId: modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(n:Connection)
                          UNWIND [k IN keys(n) WHERE k ENDS WITH '_lamport'] AS key
                          WITH coalesce(toInteger(n[key]), -1) AS lamport, watermark
                          WHERE lamport >= 0 AND lamport <= watermark
                          RETURN count(*) AS cConnection
                        }
                        RETURN cElement + cRelationship + cView + cViewObject + cConnection AS eligibleFieldClocks
                        """, params).single();
                long eligibleFieldClockCount = fieldClockCounts.get("eligibleFieldClocks").asLong(0L);

                Record tombstoneCounts = tx.run("""
                        CALL {
                          WITH $modelId AS modelId
                          MATCH (:ElementTombstone {modelId: modelId})
                          RETURN count(*) AS elementTombstones
                        }
                        CALL {
                          WITH $modelId AS modelId
                          MATCH (:RelationshipTombstone {modelId: modelId})
                          RETURN count(*) AS relationshipTombstones
                        }
                        CALL {
                          WITH $modelId AS modelId
                          MATCH (:ViewTombstone {modelId: modelId})
                          RETURN count(*) AS viewTombstones
                        }
                        CALL {
                          WITH $modelId AS modelId
                          MATCH (:ViewObjectTombstone {modelId: modelId})
                          RETURN count(*) AS viewObjectTombstones
                        }
                        CALL {
                          WITH $modelId AS modelId
                          MATCH (:ConnectionTombstone {modelId: modelId})
                          RETURN count(*) AS connectionTombstones
                        }
                        RETURN elementTombstones
                             + relationshipTombstones
                             + viewTombstones
                             + viewObjectTombstones
                             + connectionTombstones AS retainedTombstones
                        """, params).single();
                long retainedTombstoneCount = tombstoneCounts.get("retainedTombstones").asLong(0L);
                Record eligibleTombstoneCounts = tx.run("""
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (t:ElementTombstone {modelId: modelId})
                          RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS elementTombstonesEligible
                        }
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (t:RelationshipTombstone {modelId: modelId})
                          RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS relationshipTombstonesEligible
                        }
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (t:ViewTombstone {modelId: modelId})
                          RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS viewTombstonesEligible
                        }
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (t:ViewObjectTombstone {modelId: modelId})
                          RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS viewObjectTombstonesEligible
                        }
                        CALL {
                          WITH $modelId AS modelId, $watermark AS watermark
                          MATCH (t:ConnectionTombstone {modelId: modelId})
                          RETURN count(CASE WHEN coalesce(t.updatedRevision, 0) <= watermark THEN 1 END) AS connectionTombstonesEligible
                        }
                        RETURN elementTombstonesEligible
                             + relationshipTombstonesEligible
                             + viewTombstonesEligible
                             + viewObjectTombstonesEligible
                             + connectionTombstonesEligible AS eligibleTombstones
                        """, params).single();
                long eligibleTombstoneCount = eligibleTombstoneCounts.get("eligibleTombstones").asLong(0L);

                return new AdminCompactionStatus(
                        modelId,
                        headRevision,
                        committedHorizonRevision,
                        watermarkRevision,
                        safeRetain,
                        deletedCommitCount,
                        deletedOpCount,
                        deletedPropertyClockCount,
                        eligibleFieldClockCount,
                        retainedTombstoneCount,
                        eligibleTombstoneCount,
                        true,
                        "Compaction completed; tombstones/field clocks retained for safety (eligibility reported)");
            });
        } catch (Exception e) {
            LOG.warn("Compaction failed for model={} watermarkRevision={}", modelId, watermarkRevision, e);
            return new AdminCompactionStatus(
                    modelId,
                    headRevision,
                    committedHorizonRevision,
                    watermarkRevision,
                    safeRetain,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    false,
                    "Compaction failed: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void clearMaterializedState(String modelId) {
        if (driver == null) {
            return;
        }
        LOG.info("clearMaterializedState: modelId={}", modelId);
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        WITH m
                        OPTIONAL MATCH (m)-[:HAS_ELEMENT]->(e:Element)
                        DETACH DELETE e
                        """, Map.of("modelId", modelId));
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        WITH m
                        OPTIONAL MATCH (m)-[:HAS_REL]->(r:Relationship)
                        DETACH DELETE r
                        """, Map.of("modelId", modelId));
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        WITH m
                        OPTIONAL MATCH (m)-[:HAS_RELATIONSHIP_TOMBSTONE]->(t:RelationshipTombstone)
                        DETACH DELETE t
                        """, Map.of("modelId", modelId));
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        WITH m
                        OPTIONAL MATCH (m)-[:HAS_VIEW_TOMBSTONE]->(t:ViewTombstone)
                        DETACH DELETE t
                        """, Map.of("modelId", modelId));
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        WITH m
                        OPTIONAL MATCH (m)-[:HAS_VIEWOBJECT_TOMBSTONE]->(t:ViewObjectTombstone)
                        DETACH DELETE t
                        """, Map.of("modelId", modelId));
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        WITH m
                        OPTIONAL MATCH (m)-[:HAS_CONNECTION_TOMBSTONE]->(t:ConnectionTombstone)
                        DETACH DELETE t
                        """, Map.of("modelId", modelId));
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        WITH m
                        OPTIONAL MATCH (m)-[:HAS_PROPERTY_CLOCK]->(p:PropertyClock)
                        DETACH DELETE p
                        """, Map.of("modelId", modelId));
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        WITH m
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v:View)
                        DETACH DELETE v
                        """, Map.of("modelId", modelId));
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        WITH m
                        OPTIONAL MATCH (m)-[:HAS_ELEMENT_TOMBSTONE]->(t:ElementTombstone)
                        DETACH DELETE t
                        """, Map.of("modelId", modelId));
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        SET m.headRevision = 0
                        """, Map.of("modelId", modelId));
                return null;
            });
        } catch (Exception e) {
            LOG.warn("clearMaterializedState failed for model={}", modelId, e);
        }
    }

    @Override
    public void deleteModel(String modelId) {
        if (driver == null) {
            return;
        }
        LOG.warn("deleteModel: modelId={}", modelId);
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MATCH (c:Commit {modelId: $modelId})
                        OPTIONAL MATCH (c)-[:HAS_OP]->(o:Op)
                        DETACH DELETE o, c
                        """, Map.of("modelId", modelId));

                tx.run("""
                        MATCH (m:Model {modelId: $modelId})
                        OPTIONAL MATCH (m)-[:HAS_VIEW]->(v:View)
                        OPTIONAL MATCH (v)-[:CONTAINS]->(contained)
                        OPTIONAL MATCH (m)-[:HAS_ELEMENT]->(e:Element)
                        OPTIONAL MATCH (m)-[:HAS_REL]->(r:Relationship)
                        OPTIONAL MATCH (m)-[:HAS_RELATIONSHIP_TOMBSTONE]->(rt:RelationshipTombstone)
                        OPTIONAL MATCH (m)-[:HAS_ELEMENT_TOMBSTONE]->(t:ElementTombstone)
                        OPTIONAL MATCH (m)-[:HAS_VIEW_TOMBSTONE]->(vt:ViewTombstone)
                        OPTIONAL MATCH (m)-[:HAS_VIEWOBJECT_TOMBSTONE]->(vot:ViewObjectTombstone)
                        OPTIONAL MATCH (m)-[:HAS_CONNECTION_TOMBSTONE]->(ct:ConnectionTombstone)
                        OPTIONAL MATCH (m)-[:HAS_PROPERTY_CLOCK]->(pc:PropertyClock)
                        WITH m,
                             [x IN collect(DISTINCT contained) WHERE x IS NOT NULL] +
                             [x IN collect(DISTINCT v) WHERE x IS NOT NULL] +
                             [x IN collect(DISTINCT e) WHERE x IS NOT NULL] +
                             [x IN collect(DISTINCT r) WHERE x IS NOT NULL] +
                             [x IN collect(DISTINCT rt) WHERE x IS NOT NULL] +
                             [x IN collect(DISTINCT vt) WHERE x IS NOT NULL] +
                             [x IN collect(DISTINCT vot) WHERE x IS NOT NULL] +
                             [x IN collect(DISTINCT ct) WHERE x IS NOT NULL] +
                             [x IN collect(DISTINCT pc) WHERE x IS NOT NULL] +
                             [x IN collect(DISTINCT t) WHERE x IS NOT NULL] AS nodes
                        FOREACH (x IN nodes | DETACH DELETE x)
                        DETACH DELETE m
                        """, Map.of("modelId", modelId));
                return null;
            });
        } catch (Exception e) {
            LOG.warn("deleteModel failed for model={}", modelId, e);
        }
    }

    @Override
    public boolean elementExists(String modelId, String elementId) {
        if (elementId == null || elementId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_ELEMENT]->(:Element {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, elementId);
    }

    @Override
    public boolean relationshipExists(String modelId, String relationshipId) {
        if (relationshipId == null || relationshipId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_REL]->(:Relationship {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, relationshipId);
    }

    @Override
    public boolean viewExists(String modelId, String viewId) {
        if (viewId == null || viewId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, viewId);
    }

    @Override
    public boolean viewObjectExists(String modelId, String viewObjectId) {
        if (viewObjectId == null || viewObjectId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:ViewObject {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, viewObjectId);
    }

    @Override
    public boolean connectionExists(String modelId, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return false;
        }
        return exists("""
                MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(:Connection {id: $id})
                RETURN count(*) > 0 AS exists
                """, modelId, connectionId);
    }

    @Override
    public List<String> findRelationshipIdsByElement(String modelId, String elementId) {
        if (elementId == null || elementId.isBlank() || driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (m:Model {modelId: $modelId})-[:HAS_REL]->(r:Relationship)
                            WHERE r.sourceId = $elementId OR r.targetId = $elementId
                            RETURN DISTINCT r.id AS id
                            """, Map.of("modelId", modelId, "elementId", elementId))
                    .list(record -> record.get("id").asString(null))
                    .stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
        } catch (Exception e) {
            LOG.warn("findRelationshipIdsByElement failed for model={} elementId={}", modelId, elementId, e);
            return List.of();
        }
    }

    @Override
    public List<String> findViewObjectIdsByRepresents(String modelId, String representsId) {
        if (representsId == null || representsId.isBlank() || driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(vo:ViewObject)
                            WHERE vo.representsId = $representsId
                            RETURN DISTINCT vo.id AS id
                            """, Map.of("modelId", modelId, "representsId", representsId))
                    .list(record -> record.get("id").asString(null))
                    .stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
        } catch (Exception e) {
            LOG.warn("findViewObjectIdsByRepresents failed for model={} representsId={}", modelId, representsId, e);
            return List.of();
        }
    }

    @Override
    public List<String> findConnectionIdsByViewObject(String modelId, String viewObjectId) {
        if (viewObjectId == null || viewObjectId.isBlank() || driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(c:Connection)
                            WHERE c.sourceViewObjectId = $viewObjectId OR c.targetViewObjectId = $viewObjectId
                            RETURN DISTINCT c.id AS id
                            """, Map.of("modelId", modelId, "viewObjectId", viewObjectId))
                    .list(record -> record.get("id").asString(null))
                    .stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
        } catch (Exception e) {
            LOG.warn("findConnectionIdsByViewObject failed for model={} viewObjectId={}", modelId, viewObjectId, e);
            return List.of();
        }
    }

    @Override
    public List<String> findConnectionIdsByRelationship(String modelId, String relationshipId) {
        if (relationshipId == null || relationshipId.isBlank() || driver == null) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH (m:Model {modelId: $modelId})-[:HAS_VIEW]->(:View)-[:CONTAINS]->(c:Connection)
                            WHERE c.representsId = $relationshipId
                            RETURN DISTINCT c.id AS id
                            """, Map.of("modelId", modelId, "relationshipId", relationshipId))
                    .list(record -> record.get("id").asString(null))
                    .stream()
                    .filter(id -> id != null && !id.isBlank())
                    .toList());
        } catch (Exception e) {
            LOG.warn("findConnectionIdsByRelationship failed for model={} relationshipId={}", modelId, relationshipId, e);
            return List.of();
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
            case "UpdateViewObjectOpaque" -> updateViewObjectNotation(tx, op);
            case "DeleteViewObject" ->
                    deleteViewObjectWithTombstone(tx, modelId, op.path("viewObjectId").asText(), op.path("causal"), opRevision);
            case "CreateConnection" -> createConnection(tx, modelId, op.path("connection"), op.path("causal"));
            case "UpdateConnectionOpaque" -> updateConnectionNotationWithLww(tx, op);
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
                MERGE (e:Element {id: $id})
                SET e.archimateType = $archimateType,
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
                MERGE (r:Relationship {id: $id})
                SET r.archimateType = $archimateType,
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
                OPTIONAL MATCH (s:Element {id: $sourceId})
                FOREACH (_ IN CASE WHEN s IS NULL THEN [] ELSE [1] END | MERGE (r)-[:SOURCE]->(s))
                WITH r
                OPTIONAL MATCH (t:Element {id: $targetId})
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
                MATCH (r:Relationship {id: $id})
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
                """, Map.of("id", relationshipId));
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
                MATCH (r:Relationship {id: $id})
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
                    MATCH (r:Relationship {id: $id})
                    OPTIONAL MATCH (r)-[old:SOURCE]->()
                    DELETE old
                    WITH r
                    OPTIONAL MATCH (s:Element {id: $sourceId})
                    FOREACH (_ IN CASE WHEN s IS NULL THEN [] ELSE [1] END | MERGE (r)-[:SOURCE]->(s))
                    """, Map.of("id", relationshipId, "sourceId", newSourceId));
        }
        if (updateTarget) {
            tx.run("""
                    MATCH (r:Relationship {id: $id})
                    OPTIONAL MATCH (r)-[old:TARGET]->()
                    DELETE old
                    WITH r
                    OPTIONAL MATCH (t:Element {id: $targetId})
                    FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END | MERGE (r)-[:TARGET]->(t))
                    """, Map.of("id", relationshipId, "targetId", newTargetId));
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
                MERGE (v:View {id: $id})
                SET v.name = $name,
                    v.documentation = $documentation,
                    v.notationJson = $notationJson,
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
                MATCH (v:View {id: $id})
                RETURN v.name AS name,
                       v.documentation AS documentation,
                       v.notationJson AS notationJson,
                       v.name_lamport AS nameLamport,
                       v.name_clientId AS nameClientId,
                       v.documentation_lamport AS documentationLamport,
                       v.documentation_clientId AS documentationClientId,
                       v.notation_lamport AS notationLamport,
                       v.notation_clientId AS notationClientId
                """, Map.of("id", viewId));
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
                MATCH (v:View {id: $id})
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
        params.put("id", viewObjectId);
        params.put("viewId", viewObject.path("viewId").asText());
        params.put("representsId", viewObject.path("representsId").asText());
        tx.run("""
                MATCH (v:View {id: $viewId})
                MERGE (vo:ViewObject {id: $id})
                MERGE (v)-[:CONTAINS]->(vo)
                WITH vo
                OPTIONAL MATCH (e:Element {id: $representsId})
                FOREACH (_ IN CASE WHEN e IS NULL THEN [] ELSE [1] END | MERGE (vo)-[:REPRESENTS]->(e))
                """, params);
        tx.run("""
                MATCH (t:ViewObjectTombstone {modelId: $modelId, id: $id})
                DETACH DELETE t
                """, Map.of("modelId", modelId, "id", viewObjectId));
        applyViewObjectNotationWithLww(tx, viewObject.path("id").asText(null), viewObject.path("notationJson"), op.path("causal"));
        rematerializeViewObjectChildMembersForParent(tx, modelId, viewObjectId);
        rematerializeViewObjectChildMembersForChild(tx, modelId, viewObjectId);
    }

    private void updateViewObjectNotation(TransactionContext tx, JsonNode op) {
        applyViewObjectNotationWithLww(tx, op.path("viewObjectId").asText(null), op.path("notationJson"), op.path("causal"));
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
        params.put("id", connectionId);
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
        applyConnectionNotationWithLww(tx, connectionId, connection.path("notationJson"), causalNode);
        tx.run("""
                MATCH (t:ConnectionTombstone {modelId: $modelId, id: $id})
                DETACH DELETE t
                """, Map.of("modelId", modelId, "id", connectionId));
    }

    private void updateNotation(TransactionContext tx, String label, String id, JsonNode notationJson) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("notationJson", jsonText(notationJson));
        tx.run("MATCH (n:" + label + " {id: $id}) SET n.notationJson = $notationJson",
                params);
    }

    private void applyViewObjectNotationWithLww(TransactionContext tx, String viewObjectId, JsonNode incomingNotationNode, JsonNode causalNode) {
        if (viewObjectId == null || viewObjectId.isBlank()) {
            return;
        }
        ObjectNode incomingNotation = asObjectNode(incomingNotationNode);
        if (incomingNotation == null) {
            return;
        }

        var result = tx.run("""
                MATCH (vo:ViewObject {id: $id})
                RETURN vo.notationJson AS notationJson,
                       vo.geom_x_lamport AS xLamport,
                       vo.geom_x_clientId AS xClientId,
                       vo.geom_y_lamport AS yLamport,
                       vo.geom_y_clientId AS yClientId,
                       vo.geom_width_lamport AS widthLamport,
                       vo.geom_width_clientId AS widthClientId,
                       vo.geom_height_lamport AS heightLamport,
                       vo.geom_height_clientId AS heightClientId,
                       vo.vo_type_lamport AS typeLamport,
                       vo.vo_type_clientId AS typeClientId,
                       vo.vo_alpha_lamport AS alphaLamport,
                       vo.vo_alpha_clientId AS alphaClientId,
                       vo.vo_lineAlpha_lamport AS lineAlphaLamport,
                       vo.vo_lineAlpha_clientId AS lineAlphaClientId,
                       vo.vo_lineWidth_lamport AS lineWidthLamport,
                       vo.vo_lineWidth_clientId AS lineWidthClientId,
                       vo.vo_lineStyle_lamport AS lineStyleLamport,
                       vo.vo_lineStyle_clientId AS lineStyleClientId,
                       vo.vo_textAlignment_lamport AS textAlignmentLamport,
                       vo.vo_textAlignment_clientId AS textAlignmentClientId,
                       vo.vo_textPosition_lamport AS textPositionLamport,
                       vo.vo_textPosition_clientId AS textPositionClientId,
                       vo.vo_gradient_lamport AS gradientLamport,
                       vo.vo_gradient_clientId AS gradientClientId,
                       vo.vo_iconVisibleState_lamport AS iconVisibleStateLamport,
                       vo.vo_iconVisibleState_clientId AS iconVisibleStateClientId,
                       vo.vo_deriveElementLineColor_lamport AS deriveElementLineColorLamport,
                       vo.vo_deriveElementLineColor_clientId AS deriveElementLineColorClientId,
                       vo.vo_fillColor_lamport AS fillColorLamport,
                       vo.vo_fillColor_clientId AS fillColorClientId,
                       vo.vo_lineColor_lamport AS lineColorLamport,
                       vo.vo_lineColor_clientId AS lineColorClientId,
                       vo.vo_font_lamport AS fontLamport,
                       vo.vo_font_clientId AS fontClientId,
                       vo.vo_fontColor_lamport AS fontColorLamport,
                       vo.vo_fontColor_clientId AS fontColorClientId,
                       vo.vo_iconColor_lamport AS iconColorLamport,
                       vo.vo_iconColor_clientId AS iconColorClientId,
                       vo.vo_imagePath_lamport AS imagePathLamport,
                       vo.vo_imagePath_clientId AS imagePathClientId,
                       vo.vo_imagePosition_lamport AS imagePositionLamport,
                       vo.vo_imagePosition_clientId AS imagePositionClientId,
                       vo.vo_name_lamport AS nameLamport,
                       vo.vo_name_clientId AS nameClientId,
                       vo.vo_documentation_lamport AS documentationLamport,
                       vo.vo_documentation_clientId AS documentationClientId
                """, Map.of("id", viewObjectId));
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

        // Each notation field has its own causal clock; merge field-by-field for deterministic convergence
        CausalTuple incomingCausal = parseCausal(causalNode);
        CausalTuple xMeta = mergeGeometryField("x", "x", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "xLamport", "xClientId"));
        CausalTuple yMeta = mergeGeometryField("y", "y", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "yLamport", "yClientId"));
        CausalTuple widthMeta = mergeGeometryField("width", "width", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "widthLamport", "widthClientId"));
        CausalTuple heightMeta = mergeGeometryField("height", "height", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "heightLamport", "heightClientId"));
        CausalTuple typeMeta = mergeNotationField("type", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "typeLamport", "typeClientId"));
        CausalTuple alphaMeta = mergeNotationField("alpha", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "alphaLamport", "alphaClientId"));
        CausalTuple lineAlphaMeta = mergeNotationField("lineAlpha", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "lineAlphaLamport", "lineAlphaClientId"));
        CausalTuple lineWidthMeta = mergeNotationField("lineWidth", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "lineWidthLamport", "lineWidthClientId"));
        CausalTuple lineStyleMeta = mergeNotationField("lineStyle", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "lineStyleLamport", "lineStyleClientId"));
        CausalTuple textAlignmentMeta = mergeNotationField("textAlignment", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "textAlignmentLamport", "textAlignmentClientId"));
        CausalTuple textPositionMeta = mergeNotationField("textPosition", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "textPositionLamport", "textPositionClientId"));
        CausalTuple gradientMeta = mergeNotationField("gradient", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "gradientLamport", "gradientClientId"));
        CausalTuple iconVisibleStateMeta = mergeNotationField("iconVisibleState", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "iconVisibleStateLamport", "iconVisibleStateClientId"));
        CausalTuple deriveElementLineColorMeta = mergeNotationField("deriveElementLineColor", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "deriveElementLineColorLamport", "deriveElementLineColorClientId"));
        CausalTuple fillColorMeta = mergeNotationField("fillColor", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "fillColorLamport", "fillColorClientId"));
        CausalTuple lineColorMeta = mergeNotationField("lineColor", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "lineColorLamport", "lineColorClientId"));
        CausalTuple fontMeta = mergeNotationField("font", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "fontLamport", "fontClientId"));
        CausalTuple fontColorMeta = mergeNotationField("fontColor", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "fontColorLamport", "fontColorClientId"));
        CausalTuple iconColorMeta = mergeNotationField("iconColor", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "iconColorLamport", "iconColorClientId"));
        CausalTuple imagePathMeta = mergeNotationField("imagePath", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "imagePathLamport", "imagePathClientId"));
        CausalTuple imagePositionMeta = mergeNotationField("imagePosition", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "imagePositionLamport", "imagePositionClientId"));
        CausalTuple nameMeta = mergeNotationField("name", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "nameLamport", "nameClientId"));
        CausalTuple documentationMeta = mergeNotationField("documentation", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "documentationLamport", "documentationClientId"));

        Map<String, Object> params = new HashMap<>();
        params.put("id", viewObjectId);
        params.put("notationJson", jsonText(mergedNotation));
        params.put("xLamport", xMeta.lamport());
        params.put("xClientId", xMeta.clientId());
        params.put("yLamport", yMeta.lamport());
        params.put("yClientId", yMeta.clientId());
        params.put("widthLamport", widthMeta.lamport());
        params.put("widthClientId", widthMeta.clientId());
        params.put("heightLamport", heightMeta.lamport());
        params.put("heightClientId", heightMeta.clientId());
        params.put("typeLamport", typeMeta.lamport());
        params.put("typeClientId", typeMeta.clientId());
        params.put("alphaLamport", alphaMeta.lamport());
        params.put("alphaClientId", alphaMeta.clientId());
        params.put("lineAlphaLamport", lineAlphaMeta.lamport());
        params.put("lineAlphaClientId", lineAlphaMeta.clientId());
        params.put("lineWidthLamport", lineWidthMeta.lamport());
        params.put("lineWidthClientId", lineWidthMeta.clientId());
        params.put("lineStyleLamport", lineStyleMeta.lamport());
        params.put("lineStyleClientId", lineStyleMeta.clientId());
        params.put("textAlignmentLamport", textAlignmentMeta.lamport());
        params.put("textAlignmentClientId", textAlignmentMeta.clientId());
        params.put("textPositionLamport", textPositionMeta.lamport());
        params.put("textPositionClientId", textPositionMeta.clientId());
        params.put("gradientLamport", gradientMeta.lamport());
        params.put("gradientClientId", gradientMeta.clientId());
        params.put("iconVisibleStateLamport", iconVisibleStateMeta.lamport());
        params.put("iconVisibleStateClientId", iconVisibleStateMeta.clientId());
        params.put("deriveElementLineColorLamport", deriveElementLineColorMeta.lamport());
        params.put("deriveElementLineColorClientId", deriveElementLineColorMeta.clientId());
        params.put("fillColorLamport", fillColorMeta.lamport());
        params.put("fillColorClientId", fillColorMeta.clientId());
        params.put("lineColorLamport", lineColorMeta.lamport());
        params.put("lineColorClientId", lineColorMeta.clientId());
        params.put("fontLamport", fontMeta.lamport());
        params.put("fontClientId", fontMeta.clientId());
        params.put("fontColorLamport", fontColorMeta.lamport());
        params.put("fontColorClientId", fontColorMeta.clientId());
        params.put("iconColorLamport", iconColorMeta.lamport());
        params.put("iconColorClientId", iconColorMeta.clientId());
        params.put("imagePathLamport", imagePathMeta.lamport());
        params.put("imagePathClientId", imagePathMeta.clientId());
        params.put("imagePositionLamport", imagePositionMeta.lamport());
        params.put("imagePositionClientId", imagePositionMeta.clientId());
        params.put("nameLamport", nameMeta.lamport());
        params.put("nameClientId", nameMeta.clientId());
        params.put("documentationLamport", documentationMeta.lamport());
        params.put("documentationClientId", documentationMeta.clientId());
        tx.run("""
                MATCH (vo:ViewObject {id: $id})
                SET vo.notationJson = $notationJson,
                    vo.geom_x_lamport = $xLamport,
                    vo.geom_x_clientId = $xClientId,
                    vo.geom_y_lamport = $yLamport,
                    vo.geom_y_clientId = $yClientId,
                    vo.geom_width_lamport = $widthLamport,
                    vo.geom_width_clientId = $widthClientId,
                    vo.geom_height_lamport = $heightLamport,
                    vo.geom_height_clientId = $heightClientId,
                    vo.vo_type_lamport = $typeLamport,
                    vo.vo_type_clientId = $typeClientId,
                    vo.vo_alpha_lamport = $alphaLamport,
                    vo.vo_alpha_clientId = $alphaClientId,
                    vo.vo_lineAlpha_lamport = $lineAlphaLamport,
                    vo.vo_lineAlpha_clientId = $lineAlphaClientId,
                    vo.vo_lineWidth_lamport = $lineWidthLamport,
                    vo.vo_lineWidth_clientId = $lineWidthClientId,
                    vo.vo_lineStyle_lamport = $lineStyleLamport,
                    vo.vo_lineStyle_clientId = $lineStyleClientId,
                    vo.vo_textAlignment_lamport = $textAlignmentLamport,
                    vo.vo_textAlignment_clientId = $textAlignmentClientId,
                    vo.vo_textPosition_lamport = $textPositionLamport,
                    vo.vo_textPosition_clientId = $textPositionClientId,
                    vo.vo_gradient_lamport = $gradientLamport,
                    vo.vo_gradient_clientId = $gradientClientId,
                    vo.vo_iconVisibleState_lamport = $iconVisibleStateLamport,
                    vo.vo_iconVisibleState_clientId = $iconVisibleStateClientId,
                    vo.vo_deriveElementLineColor_lamport = $deriveElementLineColorLamport,
                    vo.vo_deriveElementLineColor_clientId = $deriveElementLineColorClientId,
                    vo.vo_fillColor_lamport = $fillColorLamport,
                    vo.vo_fillColor_clientId = $fillColorClientId,
                    vo.vo_lineColor_lamport = $lineColorLamport,
                    vo.vo_lineColor_clientId = $lineColorClientId,
                    vo.vo_font_lamport = $fontLamport,
                    vo.vo_font_clientId = $fontClientId,
                    vo.vo_fontColor_lamport = $fontColorLamport,
                    vo.vo_fontColor_clientId = $fontColorClientId,
                    vo.vo_iconColor_lamport = $iconColorLamport,
                    vo.vo_iconColor_clientId = $iconColorClientId,
                    vo.vo_imagePath_lamport = $imagePathLamport,
                    vo.vo_imagePath_clientId = $imagePathClientId,
                    vo.vo_imagePosition_lamport = $imagePositionLamport,
                    vo.vo_imagePosition_clientId = $imagePositionClientId,
                    vo.vo_name_lamport = $nameLamport,
                    vo.vo_name_clientId = $nameClientId,
                    vo.vo_documentation_lamport = $documentationLamport,
                    vo.vo_documentation_clientId = $documentationClientId
                """, params);
    }

    private void updateConnectionNotationWithLww(TransactionContext tx, JsonNode op) {
        applyConnectionNotationWithLww(tx, op.path("connectionId").asText(null), op.path("notationJson"), op.path("causal"));
    }

    private void applyConnectionNotationWithLww(TransactionContext tx, String connectionId, JsonNode incomingNotationNode, JsonNode causalNode) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        ObjectNode incomingNotation = asObjectNode(incomingNotationNode);
        if (incomingNotation == null) {
            return;
        }

        var result = tx.run("""
                MATCH (c:Connection {id: $id})
                RETURN c.notationJson AS notationJson,
                       c.conn_type_lamport AS typeLamport,
                       c.conn_type_clientId AS typeClientId,
                       c.conn_nameVisible_lamport AS nameVisibleLamport,
                       c.conn_nameVisible_clientId AS nameVisibleClientId,
                       c.conn_textAlignment_lamport AS textAlignmentLamport,
                       c.conn_textAlignment_clientId AS textAlignmentClientId,
                       c.conn_textPosition_lamport AS textPositionLamport,
                       c.conn_textPosition_clientId AS textPositionClientId,
                       c.conn_lineWidth_lamport AS lineWidthLamport,
                       c.conn_lineWidth_clientId AS lineWidthClientId,
                       c.conn_name_lamport AS nameLamport,
                       c.conn_name_clientId AS nameClientId,
                       c.conn_lineColor_lamport AS lineColorLamport,
                       c.conn_lineColor_clientId AS lineColorClientId,
                       c.conn_font_lamport AS fontLamport,
                       c.conn_font_clientId AS fontClientId,
                       c.conn_fontColor_lamport AS fontColorLamport,
                       c.conn_fontColor_clientId AS fontColorClientId,
                       c.conn_documentation_lamport AS documentationLamport,
                       c.conn_documentation_clientId AS documentationClientId,
                       c.conn_bendpoints_lamport AS bendpointsLamport,
                       c.conn_bendpoints_clientId AS bendpointsClientId
                """, Map.of("id", connectionId));
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
        CausalTuple typeMeta = mergeNotationField("type", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "typeLamport", "typeClientId"));
        CausalTuple nameVisibleMeta = mergeNotationField("nameVisible", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "nameVisibleLamport", "nameVisibleClientId"));
        CausalTuple textAlignmentMeta = mergeNotationField("textAlignment", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "textAlignmentLamport", "textAlignmentClientId"));
        CausalTuple textPositionMeta = mergeNotationField("textPosition", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "textPositionLamport", "textPositionClientId"));
        CausalTuple lineWidthMeta = mergeNotationField("lineWidth", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "lineWidthLamport", "lineWidthClientId"));
        CausalTuple nameMeta = mergeNotationField("name", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "nameLamport", "nameClientId"));
        CausalTuple lineColorMeta = mergeNotationField("lineColor", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "lineColorLamport", "lineColorClientId"));
        CausalTuple fontMeta = mergeNotationField("font", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "fontLamport", "fontClientId"));
        CausalTuple fontColorMeta = mergeNotationField("fontColor", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "fontColorLamport", "fontColorClientId"));
        CausalTuple documentationMeta = mergeNotationField("documentation", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "documentationLamport", "documentationClientId"));
        CausalTuple bendpointsMeta = mergeNotationField("bendpoints", incomingNotation, existingNotation, mergedNotation, incomingCausal, readCausal(record, "bendpointsLamport", "bendpointsClientId"));

        Map<String, Object> params = new HashMap<>();
        params.put("id", connectionId);
        params.put("notationJson", jsonText(mergedNotation));
        params.put("typeLamport", typeMeta.lamport());
        params.put("typeClientId", typeMeta.clientId());
        params.put("nameVisibleLamport", nameVisibleMeta.lamport());
        params.put("nameVisibleClientId", nameVisibleMeta.clientId());
        params.put("textAlignmentLamport", textAlignmentMeta.lamport());
        params.put("textAlignmentClientId", textAlignmentMeta.clientId());
        params.put("textPositionLamport", textPositionMeta.lamport());
        params.put("textPositionClientId", textPositionMeta.clientId());
        params.put("lineWidthLamport", lineWidthMeta.lamport());
        params.put("lineWidthClientId", lineWidthMeta.clientId());
        params.put("nameLamport", nameMeta.lamport());
        params.put("nameClientId", nameMeta.clientId());
        params.put("lineColorLamport", lineColorMeta.lamport());
        params.put("lineColorClientId", lineColorMeta.clientId());
        params.put("fontLamport", fontMeta.lamport());
        params.put("fontClientId", fontMeta.clientId());
        params.put("fontColorLamport", fontColorMeta.lamport());
        params.put("fontColorClientId", fontColorMeta.clientId());
        params.put("documentationLamport", documentationMeta.lamport());
        params.put("documentationClientId", documentationMeta.clientId());
        params.put("bendpointsLamport", bendpointsMeta.lamport());
        params.put("bendpointsClientId", bendpointsMeta.clientId());
        tx.run("""
                MATCH (c:Connection {id: $id})
                SET c.notationJson = $notationJson,
                    c.conn_type_lamport = $typeLamport,
                    c.conn_type_clientId = $typeClientId,
                    c.conn_nameVisible_lamport = $nameVisibleLamport,
                    c.conn_nameVisible_clientId = $nameVisibleClientId,
                    c.conn_textAlignment_lamport = $textAlignmentLamport,
                    c.conn_textAlignment_clientId = $textAlignmentClientId,
                    c.conn_textPosition_lamport = $textPositionLamport,
                    c.conn_textPosition_clientId = $textPositionClientId,
                    c.conn_lineWidth_lamport = $lineWidthLamport,
                    c.conn_lineWidth_clientId = $lineWidthClientId,
                    c.conn_name_lamport = $nameLamport,
                    c.conn_name_clientId = $nameClientId,
                    c.conn_lineColor_lamport = $lineColorLamport,
                    c.conn_lineColor_clientId = $lineColorClientId,
                    c.conn_font_lamport = $fontLamport,
                    c.conn_font_clientId = $fontClientId,
                    c.conn_fontColor_lamport = $fontColorLamport,
                    c.conn_fontColor_clientId = $fontColorClientId,
                    c.conn_documentation_lamport = $documentationLamport,
                    c.conn_documentation_clientId = $documentationClientId,
                    c.conn_bendpoints_lamport = $bendpointsLamport,
                    c.conn_bendpoints_clientId = $bendpointsClientId
                """, params);
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
        tx.run("MATCH (n {id: $id}) SET n[$key] = $value", Map.of("id", targetId, "key", key, "value", scalarValue));
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

        tx.run("MATCH (n {id: $id}) REMOVE n[$key]", Map.of("id", targetId, "key", key));
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
            tx.run("MATCH (n {id: $id}) REMOVE n[$key]", Map.of("id", targetId, "key", key));
            return;
        }

        ArrayNode node = JsonNodeFactory.instance.arrayNode();
        for (String member : members) {
            node.add(member);
        }
        tx.run("MATCH (n {id: $id}) SET n[$key] = $value", Map.of(
                "id", targetId,
                "key", key,
                "value", node.toString()));
    }

    private void updateSimpleNode(TransactionContext tx, String label, String id, JsonNode patch) {
        if (id == null || id.isBlank() || patch == null || !patch.isObject()) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
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
        tx.run("MATCH (n:" + label + " {id: $id}) SET " + set, params);
    }

    private void deleteNode(TransactionContext tx, String label, String id) {
        tx.run("MATCH (n:" + label + " {id: $id}) DETACH DELETE n", Map.of("id", id));
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
                MATCH (n:Element {id: $id})
                RETURN n.name AS name,
                       n.documentation AS documentation,
                       n.name_lamport AS nameLamport,
                       n.name_clientId AS nameClientId,
                       n.documentation_lamport AS documentationLamport,
                       n.documentation_clientId AS documentationClientId
                """, Map.of("id", elementId));
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
        params.put("name", mergedName);
        params.put("documentation", mergedDocumentation);
        params.put("nameLamport", nameMeta.lamport());
        params.put("nameClientId", nameMeta.clientId());
        params.put("documentationLamport", documentationMeta.lamport());
        params.put("documentationClientId", documentationMeta.clientId());
        tx.run("""
                MATCH (n:Element {id: $id})
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
                MATCH (parent:ViewObject {id: $parentId})
                OPTIONAL MATCH (parent)-[old:CHILD_MEMBER]->(:ViewObject)
                DELETE old
                """, Map.of("parentId", parentViewObjectId));

        if (childIds.isEmpty()) {
            return;
        }

        tx.run("""
                MATCH (parent:ViewObject {id: $parentId})
                UNWIND $childIds AS childId
                MATCH (child:ViewObject {id: childId})
                WHERE child.id <> parent.id
                MERGE (parent)-[:CHILD_MEMBER]->(child)
                """, Map.of("parentId", parentViewObjectId, "childIds", childIds));
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

    private String deriveTargetId(JsonNode op) {
        if (op.has("elementId")) {
            return op.path("elementId").asText();
        }
        if (op.has("relationshipId")) {
            return op.path("relationshipId").asText();
        }
        if (op.has("viewId")) {
            return op.path("viewId").asText();
        }
        if (op.has("viewObjectId")) {
            return op.path("viewObjectId").asText();
        }
        if (op.has("connectionId")) {
            return op.path("connectionId").asText();
        }
        if (op.has("targetId")) {
            return op.path("targetId").asText();
        }
        if (op.has("parentViewObjectId")) {
            return op.path("parentViewObjectId").asText();
        }
        return "";
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

    private boolean exists(String cypher, String modelId, String id) {
        if (driver == null) {
            return false;
        }
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(cypher, Map.of("modelId", modelId, "id", id));
                if (!result.hasNext()) {
                    return false;
                }
                return result.next().get("exists").asBoolean(false);
            });
        } catch (Exception e) {
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

    private ModelCatalogEntry toModelCatalogEntry(Record record) {
        String modelId = record.get("modelId").asString("");
        String modelName = record.get("modelName").isNull() ? null : record.get("modelName").asString(null);
        long headRevision = record.get("headRevision").asLong(0L);
        return new ModelCatalogEntry(modelId, modelName, headRevision);
    }

    private String normalizeModelName(String modelName) {
        if (modelName == null) {
            return null;
        }
        String trimmed = modelName.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private record CausalTuple(long lamport, String clientId) {
    }

    private record PropertyClockState(CausalTuple clock, boolean deleted) {
    }
}
