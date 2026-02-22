package io.archi.collab.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archi.collab.model.RevisionRange;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

class LocalInfraIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void kafkaPublisherRoundTripAgainstLocalBroker() {
        assumeLocalInfraEnabled();

        String bootstrapServers = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topicPrefix = "archi.model";
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = topicPrefix + "." + modelId + ".ops";

        KafkaPublisherImpl publisher = new KafkaPublisherImpl();
        publisher.bootstrapServers = bootstrapServers;
        publisher.topicPrefix = topicPrefix;
        publisher.objectMapper = MAPPER;
        publisher.init();

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("modelId", modelId);
        payload.put("opBatchId", UUID.randomUUID().toString());
        payload.put("timestamp", "2026-01-01T00:00:00Z");
        payload.set("ops", JsonNodeFactory.instance.arrayNode());
        publisher.publishOps(modelId, payload);
        publisher.close();

        boolean received = false;
        try(KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps(bootstrapServers))) {
            consumer.subscribe(java.util.List.of(topic));
            long deadline = System.currentTimeMillis() + 10_000;
            while(System.currentTimeMillis() < deadline && !received) {
                for(ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if(record.topic().equals(topic) && record.value().contains(modelId)) {
                        received = true;
                        break;
                    }
                }
            }
        }

        Assertions.assertTrue(received, "Expected one published ops message on local Kafka");
    }

    @Test
    void neo4jRepositoryWritesCommitAndHeadRevision() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String opBatchId = UUID.randomUUID().toString();

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode element = JsonNodeFactory.instance.objectNode();
        element.put("id", "elem:e1");
        element.put("archimateType", "BusinessActor");

        ObjectNode op = JsonNodeFactory.instance.objectNode();
        op.put("type", "CreateElement");
        op.set("element", element);

        ObjectNode opBatch = JsonNodeFactory.instance.objectNode();
        opBatch.put("modelId", modelId);
        opBatch.put("opBatchId", opBatchId);
        opBatch.put("timestamp", "2026-01-01T00:00:00Z");
        opBatch.set("ops", JsonNodeFactory.instance.arrayNode().add(op));

        RevisionRange range = new RevisionRange(1, 1);
        repository.appendOpLog(modelId, opBatchId, range, opBatch);
        repository.applyToMaterializedState(modelId, opBatch);
        repository.updateHeadRevision(modelId, 1);

        long head = repository.readHeadRevision(modelId);
        Assertions.assertEquals(1L, head);
        Assertions.assertTrue(repository.elementExists(modelId, "elem:e1"));

        var snapshot = repository.loadSnapshot(modelId);
        Assertions.assertEquals(modelId, snapshot.path("modelId").asText());
        Assertions.assertEquals(1, snapshot.path("elements").size());

        repository.close();

        try(var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
            var session = driver.session()) {
            var modelHead = session.run("MATCH (m:Model {modelId: $modelId}) RETURN m.headRevision AS head",
                    Map.of("modelId", modelId)).single().get("head").asLong();
            var commitCount = session.run("MATCH (:Commit {opBatchId: $opBatchId}) RETURN count(*) AS c",
                    Map.of("opBatchId", opBatchId)).single().get("c").asInt();

            Assertions.assertEquals(1L, modelHead);
            Assertions.assertEquals(1, commitCount);
        }
    }

    @Test
    void neo4jRepositoryReplayAndConsistencyChecks() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        ObjectNode element = JsonNodeFactory.instance.objectNode();
        element.put("id", "elem:e1");
        element.put("archimateType", "BusinessActor");
        element.put("name", "Actor 1");
        createElement.set("element", element);

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", "batch-1");
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("id", "view:v1");
        view.put("name", "View 1");
        createView.set("view", view);

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        ObjectNode viewObject = JsonNodeFactory.instance.objectNode();
        viewObject.put("id", "vo:o1");
        viewObject.put("viewId", "view:v1");
        viewObject.put("representsId", "elem:e1");
        viewObject.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 50).put("y", 50).put("width", 120).put("height", 55));
        createViewObject.set("viewObject", viewObject);

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", "batch-2");
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(createView).add(createViewObject));

        repository.appendOpLog(modelId, "batch-1", new RevisionRange(1, 1), batch1);
        repository.applyToMaterializedState(modelId, batch1);
        repository.appendOpLog(modelId, "batch-2", new RevisionRange(2, 3), batch2);
        repository.applyToMaterializedState(modelId, batch2);
        repository.updateHeadRevision(modelId, 3);

        Assertions.assertEquals(3L, repository.readHeadRevision(modelId));
        Assertions.assertEquals(3L, repository.readLatestCommitRevision(modelId));
        Assertions.assertTrue(repository.isMaterializedStateConsistent(modelId, 3L));

        var snapshot = repository.loadSnapshot(modelId);
        Assertions.assertEquals(1, snapshot.path("elements").size());
        Assertions.assertEquals(1, snapshot.path("views").size());
        Assertions.assertEquals(1, snapshot.path("viewObjects").size());

        var delta = repository.loadOpBatches(modelId, 1, 3);
        Assertions.assertEquals(2, delta.size());
        Assertions.assertEquals(1, delta.get(0).path("ops").size());
        Assertions.assertEquals(2, delta.get(1).path("ops").size());

        repository.close();
    }

    @Test
    void viewObjectGeometryUsesLamportLwwMerge() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        ObjectNode element = JsonNodeFactory.instance.objectNode();
        element.put("id", "elem:e1");
        element.put("archimateType", "BusinessActor");
        createElement.set("element", element);
        createElement.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1)
                .put("opId", "batch-1:0"));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("id", "view:v1");
        view.put("name", "View 1");
        createView.set("view", view);
        createView.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2)
                .put("opId", "batch-1:1"));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        ObjectNode viewObject = JsonNodeFactory.instance.objectNode();
        viewObject.put("id", "vo:o1");
        viewObject.put("viewId", "view:v1");
        viewObject.put("representsId", "elem:e1");
        viewObject.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 10).put("y", 10).put("width", 100).put("height", 50).put("name", "base"));
        createViewObject.set("viewObject", viewObject);
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "batch-1:2"));

        ObjectNode staleGeometryUpdate = JsonNodeFactory.instance.objectNode();
        staleGeometryUpdate.put("type", "UpdateViewObjectOpaque");
        staleGeometryUpdate.put("viewId", "view:v1");
        staleGeometryUpdate.put("viewObjectId", "vo:o1");
        staleGeometryUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 999).put("y", 999).put("width", 999).put("height", 999).put("name", "stale-name"));
        staleGeometryUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-z")
                .put("lamport", 5)
                .put("opId", "batch-2:0"));

        ObjectNode winningGeometryUpdate = JsonNodeFactory.instance.objectNode();
        winningGeometryUpdate.put("type", "UpdateViewObjectOpaque");
        winningGeometryUpdate.put("viewId", "view:v1");
        winningGeometryUpdate.put("viewObjectId", "vo:o1");
        winningGeometryUpdate.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 20));
        winningGeometryUpdate.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 11)
                .put("opId", "batch-3:0"));

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", "batch-1");
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement).add(createView).add(createViewObject));

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", "batch-2");
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(staleGeometryUpdate));

        ObjectNode batch3 = JsonNodeFactory.instance.objectNode();
        batch3.put("modelId", modelId);
        batch3.put("opBatchId", "batch-3");
        batch3.put("timestamp", "2026-01-01T00:00:02Z");
        batch3.set("ops", JsonNodeFactory.instance.arrayNode().add(winningGeometryUpdate));

        repository.appendOpLog(modelId, "batch-1", new RevisionRange(1, 3), batch1);
        repository.applyToMaterializedState(modelId, batch1);
        repository.appendOpLog(modelId, "batch-2", new RevisionRange(4, 4), batch2);
        repository.applyToMaterializedState(modelId, batch2);
        repository.appendOpLog(modelId, "batch-3", new RevisionRange(5, 5), batch3);
        repository.applyToMaterializedState(modelId, batch3);
        repository.updateHeadRevision(modelId, 5L);

        var snapshot = repository.loadSnapshot(modelId);
        var notation = snapshot.path("viewObjects").get(0).path("notationJson");

        Assertions.assertEquals(20, notation.path("x").asInt());
        Assertions.assertEquals(10, notation.path("y").asInt(), "stale y update should be ignored");
        Assertions.assertEquals(100, notation.path("width").asInt(), "stale width update should be ignored");
        Assertions.assertEquals(50, notation.path("height").asInt(), "stale height update should be ignored");
        Assertions.assertEquals("stale-name", notation.path("name").asText(), "non-geometry fields still use latest opaque value");

        repository.close();
    }

    @Test
    void viewObjectGeometryUsesClientIdTieBreakWhenLamportEqual() {
        assumeLocalInfraEnabled();

        String uri = env("NEO4J_URI", "bolt://localhost:7687");
        String username = env("NEO4J_USER", "neo4j");
        String password = env("NEO4J_PASSWORD", "devpassword");
        String modelId = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        Neo4jRepositoryImpl repository = new Neo4jRepositoryImpl();
        repository.uri = uri;
        repository.username = username;
        repository.password = password;
        repository.init();

        ObjectNode createElement = JsonNodeFactory.instance.objectNode();
        createElement.put("type", "CreateElement");
        ObjectNode element = JsonNodeFactory.instance.objectNode();
        element.put("id", "elem:e1");
        element.put("archimateType", "BusinessActor");
        createElement.set("element", element);
        createElement.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 1)
                .put("opId", "batch-1:0"));

        ObjectNode createView = JsonNodeFactory.instance.objectNode();
        createView.put("type", "CreateView");
        ObjectNode view = JsonNodeFactory.instance.objectNode();
        view.put("id", "view:v1");
        view.put("name", "View 1");
        createView.set("view", view);
        createView.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 2)
                .put("opId", "batch-1:1"));

        ObjectNode createViewObject = JsonNodeFactory.instance.objectNode();
        createViewObject.put("type", "CreateViewObject");
        ObjectNode viewObject = JsonNodeFactory.instance.objectNode();
        viewObject.put("id", "vo:o1");
        viewObject.put("viewId", "view:v1");
        viewObject.put("representsId", "elem:e1");
        viewObject.set("notationJson", JsonNodeFactory.instance.objectNode()
                .put("x", 10).put("y", 10).put("width", 100).put("height", 50));
        createViewObject.set("viewObject", viewObject);
        createViewObject.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 10)
                .put("opId", "batch-1:2"));

        ObjectNode tieFirst = JsonNodeFactory.instance.objectNode();
        tieFirst.put("type", "UpdateViewObjectOpaque");
        tieFirst.put("viewId", "view:v1");
        tieFirst.put("viewObjectId", "vo:o1");
        tieFirst.set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 101));
        tieFirst.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-a")
                .put("lamport", 12)
                .put("opId", "batch-2:0"));

        ObjectNode tieSecond = JsonNodeFactory.instance.objectNode();
        tieSecond.put("type", "UpdateViewObjectOpaque");
        tieSecond.put("viewId", "view:v1");
        tieSecond.put("viewObjectId", "vo:o1");
        tieSecond.set("notationJson", JsonNodeFactory.instance.objectNode().put("x", 202));
        tieSecond.set("causal", JsonNodeFactory.instance.objectNode()
                .put("clientId", "client-b")
                .put("lamport", 12)
                .put("opId", "batch-3:0"));

        ObjectNode batch1 = JsonNodeFactory.instance.objectNode();
        batch1.put("modelId", modelId);
        batch1.put("opBatchId", "batch-1");
        batch1.put("timestamp", "2026-01-01T00:00:00Z");
        batch1.set("ops", JsonNodeFactory.instance.arrayNode().add(createElement).add(createView).add(createViewObject));

        ObjectNode batch2 = JsonNodeFactory.instance.objectNode();
        batch2.put("modelId", modelId);
        batch2.put("opBatchId", "batch-2");
        batch2.put("timestamp", "2026-01-01T00:00:01Z");
        batch2.set("ops", JsonNodeFactory.instance.arrayNode().add(tieFirst));

        ObjectNode batch3 = JsonNodeFactory.instance.objectNode();
        batch3.put("modelId", modelId);
        batch3.put("opBatchId", "batch-3");
        batch3.put("timestamp", "2026-01-01T00:00:02Z");
        batch3.set("ops", JsonNodeFactory.instance.arrayNode().add(tieSecond));

        repository.appendOpLog(modelId, "batch-1", new RevisionRange(1, 3), batch1);
        repository.applyToMaterializedState(modelId, batch1);
        repository.appendOpLog(modelId, "batch-2", new RevisionRange(4, 4), batch2);
        repository.applyToMaterializedState(modelId, batch2);
        repository.appendOpLog(modelId, "batch-3", new RevisionRange(5, 5), batch3);
        repository.applyToMaterializedState(modelId, batch3);
        repository.updateHeadRevision(modelId, 5L);

        var snapshot = repository.loadSnapshot(modelId);
        var notation = snapshot.path("viewObjects").get(0).path("notationJson");

        Assertions.assertEquals(202, notation.path("x").asInt(),
                "equal lamport should resolve by clientId tie-break (lexicographically larger wins)");
        Assertions.assertEquals(10, notation.path("y").asInt());
        Assertions.assertEquals(100, notation.path("width").asInt());
        Assertions.assertEquals(50, notation.path("height").asInt());

        repository.close();
    }

    private static void assumeLocalInfraEnabled() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")),
                "Set RUN_LOCAL_INFRA_IT=true to run local Kafka/Neo4j integration tests.");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Properties consumerProps(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "archi-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

}
