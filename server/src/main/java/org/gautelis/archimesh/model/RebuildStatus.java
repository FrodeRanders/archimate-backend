package org.gautelis.archimesh.model;

public record RebuildStatus(
        String modelId,
        long requestedToRevision,
        long rebuiltHeadRevision,
        int appliedBatchCount,
        int appliedOpCount,
        boolean consistent) {
}

