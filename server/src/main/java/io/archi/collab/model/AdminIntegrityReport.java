package io.archi.collab.model;

import java.util.List;

public record AdminIntegrityReport(
        String modelId,
        boolean ok,
        int issueCount,
        int missingRelationshipEndpointCount,
        int missingConnectionEndpointCount,
        int missingViewObjectReferenceCount,
        int missingViewContainerCount,
        List<AdminIntegrityIssue> issues) {
}
