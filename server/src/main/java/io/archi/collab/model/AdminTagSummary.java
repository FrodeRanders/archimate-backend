package io.archi.collab.model;

public record AdminTagSummary(
        int tagCount,
        String latestTagName,
        long latestTaggedRevision,
        String latestTagCreatedAt) {
}
