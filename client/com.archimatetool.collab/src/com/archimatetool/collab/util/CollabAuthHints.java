package com.archimatetool.collab.util;

public final class CollabAuthHints {

    private CollabAuthHints() {
    }

    public static String describeHttpFailure(String action, int statusCode, boolean usingBearerToken) {
        String base = action + " failed: HTTP " + statusCode;
        if(statusCode == 401) {
            return usingBearerToken
                    ? base + " (bearer token missing, invalid, or expired)"
                    : base + " (authentication required)";
        }
        if(statusCode == 403) {
            return usingBearerToken
                    ? base + " (bearer token accepted but missing required roles/access)"
                    : base + " (request forbidden)";
        }
        return base;
    }

    public static String describeConnectionFailure(Throwable error, boolean usingBearerToken) {
        String message = rootMessage(error);
        if(message != null && !message.isBlank()) {
            if(usingBearerToken) {
                return "Connection failed. If using a bearer token, check that it is valid, unexpired, and carries the required roles. Details: " + message;
            }
            return "Connection failed: " + message;
        }
        return usingBearerToken
                ? "Connection failed. If using a bearer token, check that it is valid, unexpired, and carries the required roles."
                : "Connection failed.";
    }

    public static String describeServerError(String code, String message, boolean usingBearerToken, boolean readOnlyRef) {
        if(code == null || code.isBlank()) {
            return message == null || message.isBlank() ? "" : message;
        }
        if("AUTH_REQUIRED".equals(code)) {
            return usingBearerToken
                    ? "Authentication required. Check that the bearer token is present and still valid."
                    : "Authentication required by the server.";
        }
        if("ADMIN_ROLE_REQUIRED".equals(code)
                || "MODEL_READ_ROLE_REQUIRED".equals(code)
                || "MODEL_WRITE_ROLE_REQUIRED".equals(code)
                || "MODEL_ACCESS_DENIED".equals(code)
                || "MODEL_ADMIN_REQUIRED".equals(code)) {
            return usingBearerToken
                    ? "Authorization denied. The bearer token is valid but does not grant the required roles or model access."
                    : (message == null || message.isBlank() ? "Authorization denied by the server." : message);
        }
        if("MODEL_REFERENCE_READ_ONLY".equals(code) || readOnlyRef) {
            return "This model reference is read-only. Switch back to HEAD to submit changes.";
        }
        return message == null || message.isBlank() ? code : message;
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while(cursor != null && cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor == null ? null : cursor.getMessage();
    }
}
