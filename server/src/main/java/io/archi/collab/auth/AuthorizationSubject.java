package io.archi.collab.auth;

import java.util.Set;

public record AuthorizationSubject(
        String userId,
        Set<String> roles) {
}
