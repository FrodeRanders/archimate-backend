package io.archi.collab.model;

public record ModelTagEntry(
        String modelId,
        String tagName,
        String description,
        long revision,
        String createdAt) {
}
