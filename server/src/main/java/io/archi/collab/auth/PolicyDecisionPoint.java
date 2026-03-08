package io.archi.collab.auth;

public interface PolicyDecisionPoint {
    AuthorizationDecision decide(AuthorizationRequest request);
}
