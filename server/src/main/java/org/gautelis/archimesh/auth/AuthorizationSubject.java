package org.gautelis.archimesh.auth;

import java.util.Set;

public record AuthorizationSubject(
        String userId,
        Set<String> roles) {
}
