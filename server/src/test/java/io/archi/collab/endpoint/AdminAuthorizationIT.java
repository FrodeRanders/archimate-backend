package io.archi.collab.endpoint;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@QuarkusTest
@TestProfile(AdminAuthorizationEnabledProfile.class)
class AdminAuthorizationIT {

    @TestHTTPResource
    URI baseUri;

    @Test
    void adminCatalogRequiresAuthenticatedAdminRole() throws Exception {
        HttpResponse<String> noHeaders = send("/admin/models", null, null);
        Assertions.assertEquals(403, noHeaders.statusCode());

        HttpResponse<String> readerHeaders = send("/admin/models", "alice", "model_reader");
        Assertions.assertEquals(403, readerHeaders.statusCode());

        HttpResponse<String> adminHeaders = send("/admin/models", "alice", "admin");
        Assertions.assertEquals(200, adminHeaders.statusCode());
    }

    @Test
    void adminModelCreateIsDeniedWithoutAdminRole() throws Exception {
        HttpResponse<String> denied = post("/admin/models/authz-demo?modelName=Authz+Demo", "bob", "model_writer");
        Assertions.assertEquals(403, denied.statusCode());
    }

    @Test
    void diagnosticsExposeResolvedBootstrapSubjectForAdmins() throws Exception {
        HttpResponse<String> denied = send("/admin/auth/diagnostics", "alice", "model_reader");
        Assertions.assertEquals(403, denied.statusCode());

        HttpResponse<String> allowed = send("/admin/auth/diagnostics", "alice", "editor,admin");
        Assertions.assertEquals(200, allowed.statusCode(), allowed.body());
        Assertions.assertTrue(allowed.body().contains("\"identityMode\":\"bootstrap\""), allowed.body());
        Assertions.assertTrue(allowed.body().contains("\"userId\":\"alice\""), allowed.body());
        Assertions.assertTrue(allowed.body().contains("\"admin\""), allowed.body());
    }

    @Test
    void auditConfigExposesResolvedAdminAuditSettings() throws Exception {
        HttpResponse<String> denied = send("/admin/audit/config", "alice", "model_reader");
        Assertions.assertEquals(403, denied.statusCode());

        HttpResponse<String> allowed = send("/admin/audit/config", "alice", "admin");
        Assertions.assertEquals(200, allowed.statusCode(), allowed.body());
        Assertions.assertTrue(allowed.body().contains("\"identityMode\":\"bootstrap\""), allowed.body());
        Assertions.assertTrue(allowed.body().contains("\"authorizationEnabled\":true"), allowed.body());
        Assertions.assertTrue(allowed.body().contains("\"WebSocketJoin\""), allowed.body());
    }

    @Test
    void modelAdminMayManageAclWithoutGlobalAdminRole() throws Exception {
        String modelId = "authz-model-admin-demo";

        HttpResponse<String> created = post("/admin/models/" + modelId + "?modelName=Authz+Model+Admin", "admin-user", "admin");
        Assertions.assertEquals(200, created.statusCode(), created.body());

        HttpResponse<String> updated = put("/admin/models/" + modelId + "/acl",
                "{\"adminUsers\":[\"owner-user\"],\"writerUsers\":[\"writer-user\"],\"readerUsers\":[\"reader-user\"]}",
                "admin-user", "admin");
        Assertions.assertEquals(200, updated.statusCode(), updated.body());

        HttpResponse<String> ownerRead = send("/admin/models/" + modelId + "/acl", "owner-user", null);
        Assertions.assertEquals(200, ownerRead.statusCode(), ownerRead.body());

        HttpResponse<String> unrelatedDenied = send("/admin/models/" + modelId + "/acl", "other-user", "model_writer");
        Assertions.assertEquals(403, unrelatedDenied.statusCode());

        delete("/admin/models/" + modelId + "?force=true", "admin-user", "admin");
    }

    private HttpResponse<String> send(String path, String user, String roles) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (user != null) {
            builder.header("X-Collab-User", user);
        }
        if (roles != null) {
            builder.header("X-Collab-Roles", roles);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String user, String roles) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (user != null) {
            builder.header("X-Collab-User", user);
        }
        if (roles != null) {
            builder.header("X-Collab-Roles", roles);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body, String user, String roles) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            builder.header("X-Collab-User", user);
        }
        if (roles != null) {
            builder.header("X-Collab-Roles", roles);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String user, String roles) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .DELETE();
        if (user != null) {
            builder.header("X-Collab-User", user);
        }
        if (roles != null) {
            builder.header("X-Collab-Roles", roles);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
