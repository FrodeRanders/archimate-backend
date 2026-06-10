package org.gautelis.archimesh.auth;

import org.gautelis.archimesh.model.ModelAccessControl;

public record AuthorizationRequest(
        AuthorizationSubject subject,
        AuthorizationAction action,
        String modelId,
        String ref,
        ModelAccessControl accessControl,
        AuthorizationTransport transport) {
}
