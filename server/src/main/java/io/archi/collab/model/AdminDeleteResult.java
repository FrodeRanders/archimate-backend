package io.archi.collab.model;

public record AdminDeleteResult(
        String modelId,
        boolean deleted,
        int activeSessions,
        String message) {
}
