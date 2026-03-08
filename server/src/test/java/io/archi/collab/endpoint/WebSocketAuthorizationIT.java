package io.archi.collab.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archi.collab.service.CollaborationService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@QuarkusTest
@TestProfile(AdminAuthorizationEnabledProfile.class)
class WebSocketAuthorizationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestHTTPResource
    URI baseUri;

    @Inject
    CollaborationService collaborationService;

    private final List<String> modelsToDelete = new ArrayList<>();
    private WebSocket ws1;
    private WebSocket ws2;

    @AfterEach
    void cleanup() {
        closeQuietly(ws1);
        closeQuietly(ws2);
        for (String modelId : modelsToDelete) {
            try {
                collaborationService.deleteModel(modelId, true);
            } catch (Exception ignored) {
            }
        }
        modelsToDelete.clear();
    }

    @Test
    void readerCanJoinButCannotSubmitOps() throws Exception {
        assumeEnabled();

        String modelId = "ws-auth-reader-" + suffix();
        modelsToDelete.add(modelId);
        collaborationService.registerModel(modelId, "Reader Auth Test");

        QueueingListener listener = new QueueingListener();
        ws1 = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri(modelId, "reader", "model_reader"), listener)
                .join();

        ws1.sendText(joinMessage("reader", "reader-session"), true).join();
        JsonNode joinAck = waitForAnyType(listener, 10, "CheckoutSnapshot", "CheckoutDelta");
        Assertions.assertNotNull(joinAck, "reader should be allowed to join");

        ws1.sendText(submitOpsMessage(UUID.randomUUID().toString(), "reader", "reader-session"), true).join();
        JsonNode error = waitForType(listener, "Error", 10);
        Assertions.assertNotNull(error, "reader writes should be denied");
        Assertions.assertEquals("MODEL_WRITE_ROLE_REQUIRED", error.path("payload").path("code").asText());
    }

    @Test
    void anonymousWebSocketIsDeniedOnOpen() throws Exception {
        assumeEnabled();

        String modelId = "ws-auth-open-" + suffix();
        modelsToDelete.add(modelId);
        collaborationService.registerModel(modelId, "Anonymous Auth Test");

        QueueingListener listener = new QueueingListener();
        ws1 = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri(modelId, null, null), listener)
                .join();

        JsonNode error = waitForType(listener, "Error", 10);
        Assertions.assertNotNull(error, "anonymous open should be denied");
        Assertions.assertEquals("AUTH_REQUIRED", error.path("payload").path("code").asText());
    }

    @Test
    void writerCanJoinAndSubmitOps() throws Exception {
        assumeEnabled();

        String modelId = "ws-auth-writer-" + suffix();
        modelsToDelete.add(modelId);
        collaborationService.registerModel(modelId, "Writer Auth Test");

        QueueingListener listener = new QueueingListener();
        ws1 = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri(modelId, "writer", "model_writer"), listener)
                .join();

        ws1.sendText(joinMessage("writer", "writer-session"), true).join();
        JsonNode joinAck = waitForAnyType(listener, 10, "CheckoutSnapshot", "CheckoutDelta");
        Assertions.assertNotNull(joinAck, "writer should be allowed to join");

        String opBatchId = UUID.randomUUID().toString();
        ws1.sendText(submitOpsMessage(opBatchId, "writer", "writer-session"), true).join();
        JsonNode accepted = waitForType(listener, "OpsAccepted", 15);
        Assertions.assertNotNull(accepted, "writer submit should be accepted");
        Assertions.assertEquals(opBatchId, accepted.path("payload").path("opBatchId").asText());
    }

    @Test
    void adminWindowShowsResolvedActiveWebSocketSubject() throws Exception {
        assumeEnabled();

        String modelId = "ws-auth-window-" + suffix();
        modelsToDelete.add(modelId);
        collaborationService.registerModel(modelId, "Window Auth Test");

        QueueingListener listener = new QueueingListener();
        ws1 = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri(modelId, "writer", "model_writer"), listener)
                .join();

        ws1.sendText(joinMessage("writer", "writer-session"), true).join();
        JsonNode joinAck = waitForAnyType(listener, 10, "CheckoutSnapshot", "CheckoutDelta");
        Assertions.assertNotNull(joinAck, "writer should be allowed to join");

        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/admin/models/" + modelId + "/window?limit=5"))
                .timeout(Duration.ofSeconds(5))
                .header("X-Collab-User", "admin-user")
                .header("X-Collab-Roles", "admin")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), response.body());

        JsonNode window = MAPPER.readTree(response.body());
        Assertions.assertEquals(1, window.path("activeSessionCount").asInt(), response.body());
        Assertions.assertEquals("writer", window.path("activeSessions").get(0).path("userId").asText(), response.body());
        Assertions.assertEquals("HEAD", window.path("activeSessions").get(0).path("ref").asText(), response.body());
        Assertions.assertTrue(window.path("activeSessions").get(0).path("writable").asBoolean(), response.body());
        Assertions.assertTrue(response.body().contains("model_writer"), response.body());
    }

    @Test
    void modelAclRestrictsWebSocketAccessToListedUsers() throws Exception {
        assumeEnabled();

        String modelId = "ws-auth-acl-" + suffix();
        modelsToDelete.add(modelId);
        registerModelAsAdmin(modelId, "ACL Auth Test");
        updateAclAsAdmin(modelId, "{\"adminUsers\":[\"owner-user\"],\"writerUsers\":[\"writer-user\"],\"readerUsers\":[\"reader-user\"]}");

        QueueingListener deniedListener = new QueueingListener();
        ws1 = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri(modelId, "global-writer", "model_writer"), deniedListener)
                .join();
        JsonNode denied = waitForType(deniedListener, "Error", 10);
        Assertions.assertNotNull(denied, "unlisted global writer should be denied");
        Assertions.assertEquals("MODEL_ACCESS_DENIED", denied.path("payload").path("code").asText());

        QueueingListener allowedListener = new QueueingListener();
        ws2 = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri(modelId, "writer-user", null), allowedListener)
                .join();
        ws2.sendText(joinMessage("writer-user", "writer-user-session"), true).join();
        JsonNode joinAck = waitForAnyType(allowedListener, 10, "CheckoutSnapshot", "CheckoutDelta");
        Assertions.assertNotNull(joinAck, "listed writer should be allowed to join");
    }

    private static void assumeEnabled() {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT"))
                        && "true".equalsIgnoreCase(System.getenv("RUN_WS_E2E_IT")),
                "Set RUN_LOCAL_INFRA_IT=true and RUN_WS_E2E_IT=true to run websocket authorization tests."
        );
    }

    private void registerModelAsAdmin(String modelId, String modelName) throws Exception {
        String encodedName = URLEncoder.encode(modelName, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/admin/models/" + modelId + "?modelName=" + encodedName))
                .timeout(Duration.ofSeconds(5))
                .header("X-Collab-User", "admin-user")
                .header("X-Collab-Roles", "admin")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "admin model registration failed: HTTP " + response.statusCode() + " body=" + response.body());
    }

    private void updateAclAsAdmin(String modelId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/admin/models/" + modelId + "/acl"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Collab-User", "admin-user")
                .header("X-Collab-Roles", "admin")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "admin ACL update failed: HTTP " + response.statusCode() + " body=" + response.body());
    }

    private URI wsUri(String modelId, String user, String roles) {
        StringBuilder uri = new StringBuilder(baseUri.toString())
                .append("models/")
                .append(modelId)
                .append("/stream");
        boolean hasQuery = false;
        if (user != null && !user.isBlank()) {
            uri.append(hasQuery ? '&' : '?')
                    .append("user=")
                    .append(URLEncoder.encode(user, StandardCharsets.UTF_8));
            hasQuery = true;
        }
        if (roles != null && !roles.isBlank()) {
            uri.append(hasQuery ? '&' : '?')
                    .append("roles=")
                    .append(URLEncoder.encode(roles, StandardCharsets.UTF_8));
        }
        return URI.create(uri.toString().replaceFirst("^http", "ws"));
    }

    private static String joinMessage(String userId, String sessionId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "Join");
        ObjectNode payload = root.putObject("payload");
        payload.put("lastSeenRevision", 0L);
        payload.put("ref", "HEAD");
        ObjectNode actor = payload.putObject("actor");
        actor.put("userId", userId);
        actor.put("sessionId", sessionId);
        return root.toString();
    }

    private static String submitOpsMessage(String opBatchId, String userId, String sessionId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "SubmitOps");
        ObjectNode payload = root.putObject("payload");
        payload.put("baseRevision", 0L);
        payload.put("opBatchId", opBatchId);
        ObjectNode actor = payload.putObject("actor");
        actor.put("userId", userId);
        actor.put("sessionId", sessionId);
        ArrayNode ops = payload.putArray("ops");
        ObjectNode op = ops.addObject();
        op.put("type", "CreateElement");
        op.putObject("element")
                .put("id", "elem:" + suffix())
                .put("archimateType", "BusinessActor")
                .put("name", "Authz WS Element");
        return root.toString();
    }

    private static JsonNode waitForType(QueueingListener listener, String type, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode message = listener.messages.poll(500, TimeUnit.MILLISECONDS);
            if (message != null && type.equals(message.path("type").asText())) {
                return message;
            }
        }
        return null;
    }

    private static JsonNode waitForAnyType(QueueingListener listener, int timeoutSeconds, String... types) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode message = listener.messages.poll(500, TimeUnit.MILLISECONDS);
            if (message == null) {
                continue;
            }
            String actualType = message.path("type").asText();
            for (String type : types) {
                if (type.equals(actualType)) {
                    return message;
                }
            }
        }
        return null;
    }

    private static void closeQuietly(WebSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } catch (Exception ignored) {
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final class QueueingListener implements WebSocket.Listener {
        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final StringBuilder fragment = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragment.append(data);
            if (last) {
                try {
                    messages.offer(MAPPER.readTree(fragment.toString()));
                } catch (Exception ignored) {
                }
                fragment.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
        }
    }
}
