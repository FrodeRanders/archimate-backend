package io.archi.collab.endpoint;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class OidcAuthorizationEnabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "app.authz.enabled", "true",
                "app.identity.mode", "oidc",
                "app.authz.admin-role", "admin",
                "app.authz.reader-role", "model_reader",
                "app.authz.writer-role", "model_writer",
                "mp.jwt.verify.publickey.location", "jwt/publicKey.pem",
                "mp.jwt.verify.issuer", TestJwtTokens.issuer()
        );
    }
}
