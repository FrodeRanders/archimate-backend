package io.archi.collab.model;

public record ConsistencyStatus(
        String modelId,
        long inMemoryHeadRevision,
        long persistedHeadRevision,
        long latestCommitRevision,
        boolean materializedStateConsistent,
        boolean headAligned,
        boolean commitAligned,
        boolean consistent) {
}
