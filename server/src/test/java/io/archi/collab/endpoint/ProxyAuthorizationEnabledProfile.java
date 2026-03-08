package io.archi.collab.endpoint;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class ProxyAuthorizationEnabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "app.authz.enabled", "true",
                "app.identity.mode", "proxy",
                "app.identity.proxy.user-header", "X-Forwarded-User",
                "app.identity.proxy.roles-header", "X-Forwarded-Roles",
                "app.authz.admin-role", "admin",
                "app.authz.admin-role-aliases", "realm-admin"
        );
    }
}
