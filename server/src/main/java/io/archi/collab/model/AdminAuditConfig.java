package io.archi.collab.model;

import java.util.List;

public record AdminAuditConfig(
        String identityMode,
        boolean authorizationEnabled,
        List<String> websocketAuditActions,
        boolean websocketAuditVerbose) {
}
