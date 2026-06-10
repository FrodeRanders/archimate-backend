package org.gautelis.archimesh.model;

public record AdminDeleteResult(
        String modelId,
        boolean deleted,
        int activeSessions,
        String message) {
}
