package io.archi.collab.model;

import java.util.Set;

public record AdminAuthorizationDiagnostics(
        String identityMode,
        String userId,
        Set<String> normalizedRoles) {
}
