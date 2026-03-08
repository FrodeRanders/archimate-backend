package io.archi.collab.auth;

public class AuthorizationDeniedException extends RuntimeException {
    private final String code;

    public AuthorizationDeniedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
