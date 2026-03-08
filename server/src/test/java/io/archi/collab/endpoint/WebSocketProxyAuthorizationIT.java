package io.archi.collab.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.http.HttpClient;
import java.net.http.WebSocket;
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
@TestProfile(ProxyAuthorizationEnabledProfile.class)
class WebSocketProxyAuthorizationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestHTTPResource
    URI baseUri;

    @Inject
    CollaborationService collaborationService;

    private final List<String> modelsToDelete = new ArrayList<>();
    private WebSocket ws;

    @AfterEach
    void cleanup() {
        closeQuietly(ws);
        for (String modelId : modelsToDelete) {
            try {
                collaborationService.deleteModel(modelId, true);
            } catch (Exception ignored) {
            }
        }
        modelsToDelete.clear();
    }

    @Test
    void forwardedHeadersAuthorizeWriterWebSocketSession() throws Exception {
        assumeEnabled();

        String modelId = "ws-proxy-auth-writer-" + suffix();
        modelsToDelete.add(modelId);
        collaborationService.registerModel(modelId, "Proxy Writer Auth Test");

        QueueingListener listener = new QueueingListener();
        ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .header("X-Forwarded-User", "writer")
                .header("X-Forwarded-Roles", "model_writer")
                .buildAsync(wsUri(modelId), listener)
                .join();

        ws.sendText(joinMessage("writer", "proxy-writer-session"), true).join();
        JsonNode joinAck = waitForAnyType(listener, 10, "CheckoutSnapshot", "CheckoutDelta");
        Assertions.assertNotNull(joinAck);

        String opBatchId = UUID.randomUUID().toString();
        ws.sendText(submitOpsMessage(opBatchId, "writer", "proxy-writer-session"), true).join();
        JsonNode accepted = waitForType(listener, "OpsAccepted", 15);
        Assertions.assertNotNull(accepted);
        Assertions.assertEquals(opBatchId, accepted.path("payload").path("opBatchId").asText());
    }

    @Test
    void missingForwardedHeadersDenyProxyWebSocketSession() throws Exception {
        assumeEnabled();

        String modelId = "ws-proxy-auth-open-" + suffix();
        modelsToDelete.add(modelId);
        collaborationService.registerModel(modelId, "Proxy Anonymous Auth Test");

        QueueingListener listener = new QueueingListener();
        ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(wsUri(modelId), listener)
                .join();

        JsonNode error = waitForType(listener, "Error", 10);
        Assertions.assertNotNull(error);
        Assertions.assertEquals("AUTH_REQUIRED", error.path("payload").path("code").asText());
    }

    private static void assumeEnabled() {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT"))
                        && "true".equalsIgnoreCase(System.getenv("RUN_WS_E2E_IT")),
                "Set RUN_LOCAL_INFRA_IT=true and RUN_WS_E2E_IT=true to run websocket proxy authorization tests."
        );
    }

    private URI wsUri(String modelId) {
        return URI.create((baseUri.toString() + "models/" + modelId + "/stream").replaceFirst("^http", "ws"));
    }

    private static String joinMessage(String userId, String sessionId) {
        var root = MAPPER.createObjectNode();
        root.put("type", "Join");
        var payload = root.putObject("payload");
        payload.put("lastSeenRevision", 0L);
        payload.put("ref", "HEAD");
        var actor = payload.putObject("actor");
        actor.put("userId", userId);
        actor.put("sessionId", sessionId);
        return root.toString();
    }

    private static String submitOpsMessage(String opBatchId, String userId, String sessionId) {
        var root = MAPPER.createObjectNode();
        root.put("type", "SubmitOps");
        var payload = root.putObject("payload");
        payload.put("baseRevision", 0L);
        payload.put("opBatchId", opBatchId);
        var actor = payload.putObject("actor");
        actor.put("userId", userId);
        actor.put("sessionId", sessionId);
        var ops = payload.putArray("ops");
        var op = ops.addObject();
        op.put("type", "CreateElement");
        op.put("elementId", "elem:proxy-" + opBatchId.substring(0, 8));
        op.put("name", "Proxy WS Element");
        op.put("conceptType", "BusinessActor");
        return root.toString();
    }

    private static JsonNode waitForType(QueueingListener listener, String type, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            JsonNode message = listener.messages.poll(250, TimeUnit.MILLISECONDS);
            if (message == null) {
                continue;
            }
            if (type.equals(message.path("type").asText())) {
                return message;
            }
        }
        return null;
    }

    private static JsonNode waitForAnyType(QueueingListener listener, int timeoutSeconds, String... types) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            JsonNode message = listener.messages.poll(250, TimeUnit.MILLISECONDS);
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
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        } catch (Exception ignored) {
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static class QueueingListener implements WebSocket.Listener {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                try {
                    messages.add(MAPPER.readTree(textBuffer.toString()));
                } catch (Exception e) {
                    CompletableFuture<Void> failed = new CompletableFuture<>();
                    failed.completeExceptionally(e);
                    return failed;
                } finally {
                    textBuffer.setLength(0);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
