package org.gautelis.archimesh.service.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KafkaConsumerImplTest {

    @Test
    void extractsModelIdFromOpsTopic() {
        String modelId = KafkaConsumerImpl.extractModelIdFromTopic("archimesh.model", "archimesh.model.demo.ops");
        Assertions.assertEquals("demo", modelId);
    }

    @Test
    void extractsModelIdFromPresenceTopic() {
        String modelId = KafkaConsumerImpl.extractModelIdFromTopic("archimesh.model", "archimesh.model.demo.presence");
        Assertions.assertEquals("demo", modelId);
    }

    @Test
    void parsesTopicKind() {
        KafkaConsumerImpl.TopicRoute route = KafkaConsumerImpl.parseTopic("archimesh.model", "archimesh.model.demo.locks");
        Assertions.assertNotNull(route);
        Assertions.assertEquals("demo", route.modelId());
        Assertions.assertEquals("locks", route.kind());
    }

    @Test
    void returnsNullForNonMatchingTopic() {
        Assertions.assertNull(KafkaConsumerImpl.extractModelIdFromTopic("archimesh.model", "other.demo.ops"));
        Assertions.assertNull(KafkaConsumerImpl.parseTopic("archimesh.model", "archimesh.model.demo.unknown"));
    }
}
