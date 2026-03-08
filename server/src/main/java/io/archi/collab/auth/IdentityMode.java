package io.archi.collab.auth;

public enum IdentityMode {
    BOOTSTRAP,
    PROXY;

    public static IdentityMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return BOOTSTRAP;
        }
        return switch (raw.trim().toLowerCase()) {
            case "bootstrap" -> BOOTSTRAP;
            case "proxy" -> PROXY;
            default -> throw new IllegalArgumentException("Unsupported app.identity.mode: " + raw);
        };
    }
}
