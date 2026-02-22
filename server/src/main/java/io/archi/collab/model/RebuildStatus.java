package io.archi.collab.model;

public record RebuildStatus(
        String modelId,
        long requestedToRevision,
        long rebuiltHeadRevision,
        int appliedBatchCount,
        int appliedOpCount,
        boolean consistent) {
}

