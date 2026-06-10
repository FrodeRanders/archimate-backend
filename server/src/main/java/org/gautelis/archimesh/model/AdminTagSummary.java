package org.gautelis.archimesh.model;

public record AdminTagSummary(
        int tagCount,
        String latestTagName,
        long latestTaggedRevision,
        String latestTagCreatedAt) {
}
