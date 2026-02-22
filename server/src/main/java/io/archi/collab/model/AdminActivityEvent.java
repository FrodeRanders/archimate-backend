package io.archi.collab.model;

public record AdminActivityEvent(
        String timestamp,
        String type,
        String modelId,
        String details) {
}
