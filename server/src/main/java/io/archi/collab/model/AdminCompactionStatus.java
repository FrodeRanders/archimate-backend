package io.archi.collab.model;

public record AdminCompactionStatus(
        String modelId,
        long headRevision,
        long committedHorizonRevision,
        long watermarkRevision,
        long retainRevisions,
        long deletedCommitCount,
        long deletedOpCount,
        long deletedPropertyClockCount,
        long eligibleFieldClockCount,
        long retainedTombstoneCount,
        long eligibleTombstoneCount,
        boolean executed,
        String note) {
}
