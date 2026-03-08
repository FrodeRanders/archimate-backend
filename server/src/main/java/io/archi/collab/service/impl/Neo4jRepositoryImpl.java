package io.archi.collab.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

    @ConfigProperty(name = "app.neo4j.uri", defaultValue = "bolt://localhost:7687")
    String uri;

    @ConfigProperty(name = "app.neo4j.username", defaultValue = "neo4j")
    String username;

    @ConfigProperty(name = "app.neo4j.password", defaultValue = "devpassword")
    String password;

    @Inject
    ObjectMapper objectMapper;

    private Driver driver;
    private Neo4jMaterializedStateSupport materializedStateSupport;
    private Neo4jReadSupport readSupport;
    private Neo4jCompactionSupport compactionSupport;
    private Neo4jOpLogSupport opLogSupport;

    @PostConstruct
    void init() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        materializedStateSupport = new Neo4jMaterializedStateSupport(objectMapper);
        readSupport = new Neo4jReadSupport(objectMapper);
        compactionSupport = new Neo4jCompactionSupport();
        opLogSupport = new Neo4jOpLogSupport();
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
        try (var session = requireDriver("appendOpLog").session()) {
            opLogSupport.appendOpLog(session, modelId, opBatchId, range, opBatch);
        } catch (Exception e) {
            LOG.warn("appendOpLog failed for model={} batch={}", modelId, opBatchId, e);
            throw writeFailure("appendOpLog", modelId, e);
        }
    }

    @Override
    public void applyToMaterializedState(String modelId, JsonNode opBatch) {
        int opCount = opBatch.path("ops").isArray() ? opBatch.path("ops").size() : 0;
        LOG.debug("applyToMaterializedState: modelId={} opCount={}", modelId, opCount);
        try (var session = requireDriver("applyToMaterializedState").session()) {
            session.executeWrite(tx -> {
                materializedStateSupport.applyBatch(tx, modelId, opBatch);
                return null;
            });
        } catch (Exception e) {
            LOG.warn("applyToMaterializedState failed for model={}", modelId, e);
            throw writeFailure("applyToMaterializedState", modelId, e);
        }
    }

    @Override
    public void updateHeadRevision(String modelId, long headRevision) {
        LOG.debug("updateHeadRevision: modelId={} headRevision={}", modelId, headRevision);
        try (var session = requireDriver("updateHeadRevision").session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        MERGE (m:Model {modelId: $modelId})
                        SET m.headRevision = $headRevision
                        """, Map.of("modelId", modelId, "headRevision", headRevision));
                return null;
            });
        } catch (Exception e) {
            LOG.warn("updateHeadRevision failed for model={} head={}", modelId, headRevision, e);
            throw writeFailure("updateHeadRevision", modelId, e);
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
        String normalizedName = normalizeModelName(modelName);
        String now = Instant.now().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("modelName", normalizedName);
        params.put("now", now);
        try (var session = requireDriver("registerModel").session()) {
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
            throw writeFailure("registerModel", modelId, e);
        }
    }

    @Override
    public ModelCatalogEntry renameModel(String modelId, String modelName) {
        String normalizedName = normalizeModelName(modelName);
        String now = Instant.now().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("modelId", modelId);
        params.put("modelName", normalizedName);
        params.put("now", now);
        try (var session = requireDriver("renameModel").session()) {
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
            throw writeFailure("renameModel", modelId, e);
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
        long headRevision = readHeadRevision(modelId);
        if (driver == null) {
            return readSupport.loadSnapshot(null, modelId, headRevision);
        }
        try (var session = driver.session()) {
            return readSupport.loadSnapshot(session, modelId, headRevision);
        } catch (Exception e) {
            LOG.warn("loadSnapshot failed for model={}", modelId, e);
            return readSupport.loadSnapshot(null, modelId, headRevision);
        }
    }

    @Override
    public boolean isMaterializedStateConsistent(String modelId, long expectedHeadRevision) {
        if (driver == null) {
            return true;
        }
        try (var session = driver.session()) {
            return readSupport.isMaterializedStateConsistent(session, modelId, expectedHeadRevision);
        } catch (Exception e) {
            LOG.warn("isMaterializedStateConsistent failed for model={} expectedHeadRevision={}",
                    modelId, expectedHeadRevision, e);
            return false;
        }
    }

    @Override
    public JsonNode loadOpBatches(String modelId, long fromRevisionInclusive, long toRevisionInclusive) {
        if (driver == null) {
            return readSupport.loadOpBatches(null, modelId, fromRevisionInclusive, toRevisionInclusive);
        }
        try (var session = driver.session()) {
            return readSupport.loadOpBatches(session, modelId, fromRevisionInclusive, toRevisionInclusive);
        } catch (Exception e) {
            LOG.warn("loadOpBatches failed for model={} range={}..{}",
                    modelId, fromRevisionInclusive, toRevisionInclusive, e);
            return readSupport.loadOpBatches(null, modelId, fromRevisionInclusive, toRevisionInclusive);
        }
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
        try (var session = driver.session()) {
            return compactionSupport.compactMetadata(
                    session, modelId, headRevision, committedHorizonRevision, watermarkRevision, safeRetain);
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
        LOG.info("clearMaterializedState: modelId={}", modelId);
        try (var session = requireDriver("clearMaterializedState").session()) {
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
            throw writeFailure("clearMaterializedState", modelId, e);
        }
    }

    @Override
    public void deleteModel(String modelId) {
        LOG.warn("deleteModel: modelId={}", modelId);
        try (var session = requireDriver("deleteModel").session()) {
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
            throw writeFailure("deleteModel", modelId, e);
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

    private Driver requireDriver(String operation) {
        if (driver == null) {
            throw new IllegalStateException("Neo4j driver unavailable for " + operation);
        }
        return driver;
    }

    private RuntimeException writeFailure(String operation, String modelId, Exception cause) {
        return new IllegalStateException(
                "Neo4j write operation failed: " + operation + " modelId=" + modelId,
                cause);
    }

    private record CausalTuple(long lamport, String clientId) {
    }

    private record PropertyClockState(CausalTuple clock, boolean deleted) {
    }
}
