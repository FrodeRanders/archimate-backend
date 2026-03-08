package io.archi.collab.auth;

import io.archi.collab.model.ModelAccessControl;

public record AuthorizationRequest(
        AuthorizationSubject subject,
        AuthorizationAction action,
        String modelId,
        String ref,
        ModelAccessControl accessControl,
        AuthorizationTransport transport) {
}
