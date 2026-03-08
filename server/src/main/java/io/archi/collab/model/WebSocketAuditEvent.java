package io.archi.collab.model;

import java.util.Map;

public record WebSocketAuditEvent(
        String timestamp,
        String action,
        String modelId,
        String websocketSessionId,
        String userId,
        Map<String, Object> context) {
}
