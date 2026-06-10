package org.gautelis.archimesh.model;

public record AdminAccessSummary(
        boolean aclConfigured,
        String mode,
        int adminUserCount,
        int writerUserCount,
        int readerUserCount) {
}
