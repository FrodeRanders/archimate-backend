package io.archi.collab.model;

public record AdminStatus(
        String modelId,
        long snapshotHeadRevision,
        int elementCount,
        int relationshipCount,
        int viewCount,
        int viewObjectCount,
        int connectionCount,
        ConsistencyStatus consistency) {
}
