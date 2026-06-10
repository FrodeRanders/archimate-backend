package org.gautelis.archimesh.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

public interface KafkaPublisher {
    CompletableFuture<Void> publishOps(String modelId, JsonNode opBatch);

    void publishLockEvent(String modelId, Object lockEvent);

    void publishPresence(String modelId, Object presenceEvent);
}
