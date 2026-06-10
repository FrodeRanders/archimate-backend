package org.gautelis.archimesh.model;

import java.util.Set;

public record AdminActiveSession(
        String websocketSessionId,
        String userId,
        Set<String> normalizedRoles,
        String ref,
        boolean writable) {
}
