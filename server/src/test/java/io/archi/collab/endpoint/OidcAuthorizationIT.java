package io.archi.collab.endpoint;

import io.archi.collab.service.CollaborationService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@QuarkusTest
@TestProfile(OidcAuthorizationEnabledProfile.class)
class OidcAuthorizationIT {

    @TestHTTPResource
    URI baseUri;

    @Inject
    CollaborationService collaborationService;

    private final List<String> modelsToDelete = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String modelId : modelsToDelete) {
            try {
                collaborationService.deleteModel(modelId, true);
            } catch (Exception ignored) {
            }
        }
        modelsToDelete.clear();
    }

    @Test
    void oidcModeIgnoresBootstrapHeadersForAdminRestCalls() throws Exception {
        HttpResponse<String> denied = get("/admin/models", "bootstrap-user", "admin", false);
        Assertions.assertEquals(403, denied.statusCode());

        HttpResponse<String> allowed = get("/admin/models", "oidc-admin", "admin", true);
        Assertions.assertEquals(200, allowed.statusCode());
    }

    @Test
    void oidcModeAuthorizesModelReadsFromSecurityContext() throws Exception {
        String modelId = "oidc-authz-snapshot-demo";
        modelsToDelete.add(modelId);
        collaborationService.registerModel(modelId, "OIDC Snapshot Demo");

        HttpResponse<String> denied = get("/models/" + modelId + "/snapshot", "reader", "model_reader", false);
        Assertions.assertEquals(403, denied.statusCode());

        HttpResponse<String> allowed = get("/models/" + modelId + "/snapshot", "reader", "model_reader", true);
        Assertions.assertEquals(200, allowed.statusCode());
    }

    @Test
    void diagnosticsExposeResolvedOidcSubjectForAdmins() throws Exception {
        HttpResponse<String> allowed = get("/admin/auth/diagnostics", "oidc-admin", "realm-admin", true);
        Assertions.assertEquals(200, allowed.statusCode(), allowed.body());
        Assertions.assertTrue(allowed.body().contains("\"identityMode\":\"oidc\""), allowed.body());
        Assertions.assertTrue(allowed.body().contains("\"userId\":\"oidc-admin\""), allowed.body());
        Assertions.assertTrue(allowed.body().contains("\"admin\""), allowed.body());
    }

    private HttpResponse<String> get(String path, String user, String roles, boolean bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (bearer && user != null) {
            builder.header("Authorization", "Bearer " + TestJwtTokens.token(user, roles == null ? new String[0] : roles.split(",")));
        } else {
            if (user != null) {
                builder.header("X-Collab-User", user);
            }
            if (roles != null) {
                builder.header("X-Collab-Roles", roles);
            }
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
