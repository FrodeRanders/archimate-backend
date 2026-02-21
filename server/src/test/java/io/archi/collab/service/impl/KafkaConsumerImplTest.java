package io.archi.collab.service.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KafkaConsumerImplTest {

    @Test
    void extractsModelIdFromOpsTopic() {
        String modelId = KafkaConsumerImpl.extractModelIdFromTopic("archi.model", "archi.model.demo.ops");
        Assertions.assertEquals("demo", modelId);
    }

    @Test
    void extractsModelIdFromPresenceTopic() {
        String modelId = KafkaConsumerImpl.extractModelIdFromTopic("archi.model", "archi.model.demo.presence");
        Assertions.assertEquals("demo", modelId);
    }

    @Test
    void parsesTopicKind() {
        KafkaConsumerImpl.TopicRoute route = KafkaConsumerImpl.parseTopic("archi.model", "archi.model.demo.locks");
        Assertions.assertNotNull(route);
        Assertions.assertEquals("demo", route.modelId());
        Assertions.assertEquals("locks", route.kind());
    }

    @Test
    void returnsNullForNonMatchingTopic() {
        Assertions.assertNull(KafkaConsumerImpl.extractModelIdFromTopic("archi.model", "other.demo.ops"));
        Assertions.assertNull(KafkaConsumerImpl.parseTopic("archi.model", "archi.model.demo.unknown"));
    }
}
