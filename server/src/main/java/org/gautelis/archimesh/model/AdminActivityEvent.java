package org.gautelis.archimesh.model;

public record AdminActivityEvent(
        String timestamp,
        String type,
        String modelId,
        String details) {
}
