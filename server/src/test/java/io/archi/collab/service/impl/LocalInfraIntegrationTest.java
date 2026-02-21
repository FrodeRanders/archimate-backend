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
