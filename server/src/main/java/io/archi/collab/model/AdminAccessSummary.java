package io.archi.collab.model;

public record AdminAccessSummary(
        boolean aclConfigured,
        String mode,
        int adminUserCount,
        int writerUserCount,
        int readerUserCount) {
}
