package io.archi.collab.model;

import java.util.Map;

public record AdminAuditEvent(
        String timestamp,
        String action,
        String modelId,
        String userId,
        Map<String, Object> context) {
}
