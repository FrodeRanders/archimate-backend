package org.gautelis.archimesh.model;

public record Actor(String userId, String sessionId) {
    public static Actor anonymous() {
        return new Actor("anonymous", "anonymous-session");
    }
}
