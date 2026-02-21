package io.archi.collab.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface KafkaPublisher {
    void publishOps(String modelId, JsonNode opBatch);

    void publishLockEvent(String modelId, Object lockEvent);

    void publishPresence(String modelId, Object presenceEvent);
}
