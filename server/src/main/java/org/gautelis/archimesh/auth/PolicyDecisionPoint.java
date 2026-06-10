package org.gautelis.archimesh.auth;

public interface PolicyDecisionPoint {
    AuthorizationDecision decide(AuthorizationRequest request);
}
