package io.archi.collab.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AdminModelWindow(
        String modelId,
        int activeSessionCount,
        AdminStatus status,
        AdminStyleCounters styleCounters,
        AdminIntegrityReport integrity,
        List<AdminActivityEvent> recentActivity,
        JsonNode recentOpBatches) {
}
