package org.gautelis.archimesh.model;

public record AdminIntegrityIssue(
        String code,
        String severity,
        String entityType,
        String entityId,
        String message,
        String suggestedAction) {
}
