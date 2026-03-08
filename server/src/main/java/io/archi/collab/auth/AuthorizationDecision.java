package io.archi.collab.auth;

public record AuthorizationDecision(
        boolean allowed,
        String code,
        String reason) {

    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, "ALLOW", "allowed");
    }

    public static AuthorizationDecision deny(String code, String reason) {
        return new AuthorizationDecision(false, code, reason);
    }
}
