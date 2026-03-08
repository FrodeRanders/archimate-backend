package io.archi.collab.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record AdminModelWindow(
        String modelId,
        String modelName,
        int activeSessionCount,
        AdminAccessSummary accessSummary,
        AdminTagSummary tagSummary,
        AdminStatus status,
        AdminStyleCounters styleCounters,
        AdminIntegrityReport integrity,
        List<AdminActivityEvent> recentActivity,
        JsonNode recentOpBatches) {
}
