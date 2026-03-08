package io.archi.collab.endpoint;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class AdminAuthorizationEnabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "app.authz.enabled", "true",
                "app.authz.admin-role", "admin"
        );
    }
}
