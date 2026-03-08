package io.archi.collab.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
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
import java.util.UUID;
import java.util.concurrent.*;

@QuarkusTest
class WebSocketEndToEndIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestHTTPResource
    URI baseUri;

    private WebSocket ws1;
    private WebSocket ws2;

    @AfterEach
    void closeSockets() {
        if (ws1 != null) {
            ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
        if (ws2 != null) {
            ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void submitOpsIsBroadcastToBothSessionsViaKafka() throws Exception {
        assumeEnabled();

        String modelId = "ws-it-" + UUID.randomUUID().toString().substring(0, 8);
        registerModel(modelId, "WebSocket IT");
        URI wsUri = wsUri(modelId);

        QueueingListener listener1 = new QueueingListener();
        QueueingListener listener2 = new QueueingListener();

        HttpClient client = HttpClient.newHttpClient();
        ws1 = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(wsUri, listener1).join();
        ws2 = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(wsUri, listener2).join();

        ws1.sendText(joinMessage("u1", "s1"), true).join();
        ws2.sendText(joinMessage("u2", "s2"), true).join();

        JsonNode joinAck1 = waitForAnyType(listener1, 10, "CheckoutSnapshot", "CheckoutDelta");
        JsonNode joinAck2 = waitForAnyType(listener2, 10, "CheckoutSnapshot", "CheckoutDelta");
        Assertions.assertNotNull(joinAck1);
        Assertions.assertNotNull(joinAck2);

        String warmupBatchId = UUID.randomUUID().toString();
        ws1.sendText(submitOpsMessage(warmupBatchId), true).join();
        JsonNode warmupAccepted = waitForType(listener1, "OpsAccepted", 15);
        Assertions.assertNotNull(warmupAccepted, "warm-up submit should receive OpsAccepted");

        BroadcastAttempt submitAttempt = submitUntilBroadcast(
                ws1,
                listener1,
                listener2,
                5,
                () -> submitOpsMessage(UUID.randomUUID().toString()));
        JsonNode accepted = submitAttempt.accepted();
        String opBatchId = submitAttempt.opBatchId();
        JsonNode broadcast1 = submitAttempt.senderBroadcast();
        JsonNode broadcast2 = submitAttempt.otherBroadcast();

        Assertions.assertNotNull(accepted, "sender should receive OpsAccepted");
        Assertions.assertEquals(opBatchId, accepted.path("payload").path("opBatchId").asText());
        Assertions.assertNotNull(broadcast1, "sender should receive OpsBroadcast via Kafka consumer");
        Assertions.assertNotNull(broadcast2, "other subscriber should receive OpsBroadcast via Kafka consumer");
        Assertions.assertEquals(opBatchId, broadcast1.path("payload").path("opBatch").path("opBatchId").asText());
        Assertions.assertEquals(opBatchId, broadcast2.path("payload").path("opBatch").path("opBatchId").asText());
    }

    @Test
    void styleUpdateIsBroadcastToBothSessionsViaKafka() throws Exception {
        assumeEnabled();

        String modelId = "ws-it-style-" + UUID.randomUUID().toString().substring(0, 8);
        registerModel(modelId, "WebSocket Style IT");
        URI wsUri = wsUri(modelId);

        QueueingListener listener1 = new QueueingListener();
        QueueingListener listener2 = new QueueingListener();

        HttpClient client = HttpClient.newHttpClient();
        ws1 = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(wsUri, listener1).join();
        ws2 = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(wsUri, listener2).join();

        ws1.sendText(joinMessage("u1", "s1"), true).join();
        ws2.sendText(joinMessage("u2", "s2"), true).join();
        Assertions.assertNotNull(waitForAnyType(listener1, 10, "CheckoutSnapshot", "CheckoutDelta"));
        Assertions.assertNotNull(waitForAnyType(listener2, 10, "CheckoutSnapshot", "CheckoutDelta"));

        String elementId = "elem:" + UUID.randomUUID().toString().substring(0, 8);
        String viewId = "view:" + UUID.randomUUID().toString().substring(0, 8);
        String viewObjectId = "vo:" + UUID.randomUUID().toString().substring(0, 8);

        String createBatchId = UUID.randomUUID().toString();
        ws1.sendText(submitCreateViewObjectBootstrap(createBatchId, elementId, viewId, viewObjectId), true).join();

        Assertions.assertNotNull(waitForType(listener1, "OpsAccepted", 15));

        BroadcastAttempt styleAttempt = submitUntilBroadcast(
                ws1,
                listener1,
                listener2,
                5,
                () -> submitStyleUpdate(UUID.randomUUID().toString(), viewId, viewObjectId));
        JsonNode styleBroadcast1 = styleAttempt.senderBroadcast();
        JsonNode styleBroadcast2 = styleAttempt.otherBroadcast();
        Assertions.assertNotNull(styleBroadcast1);
        Assertions.assertNotNull(styleBroadcast2);

        JsonNode op1 = styleBroadcast1.path("payload").path("opBatch").path("ops").get(0);
        JsonNode op2 = styleBroadcast2.path("payload").path("opBatch").path("ops").get(0);
        Assertions.assertEquals("UpdateViewObjectOpaque", op1.path("type").asText());
        Assertions.assertEquals("UpdateViewObjectOpaque", op2.path("type").asText());
        Assertions.assertEquals("#112233", op1.path("notationJson").path("fillColor").asText());
        Assertions.assertEquals("#112233", op2.path("notationJson").path("fillColor").asText());
    }

    @Test
    void reconnectWithStaleLastSeenReceivesCheckoutDelta() throws Exception {
        assumeEnabled();

        String modelId = "ws-it-rejoin-" + UUID.randomUUID().toString().substring(0, 8);
        registerModel(modelId, "WebSocket Rejoin IT");
        URI wsUri = wsUri(modelId);

        QueueingListener listener1 = new QueueingListener();
        QueueingListener listener2 = new QueueingListener();

        HttpClient client = HttpClient.newHttpClient();
        ws1 = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(wsUri, listener1).join();

        ws1.sendText(joinMessage("u1", "s1", 0L), true).join();
        Assertions.assertNotNull(waitForAnyType(listener1, 10, "CheckoutSnapshot", "CheckoutDelta"));

        String batch1 = UUID.randomUUID().toString();
        String batch2 = UUID.randomUUID().toString();
        ws1.sendText(submitOpsMessage(batch1), true).join();
        Assertions.assertNotNull(waitForType(listener1, "OpsAccepted", 15));
        ws1.sendText(submitOpsMessage(batch2), true).join();
        Assertions.assertNotNull(waitForType(listener1, "OpsAccepted", 15));

        ws2 = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(wsUri, listener2).join();
        ws2.sendText(joinMessage("u2", "s2", 0L), true).join();

        JsonNode rejoinAck = waitForAnyType(listener2, 15, "CheckoutDelta", "CheckoutSnapshot");
        Assertions.assertNotNull(rejoinAck, "rejoining client should receive checkout payload");
        Assertions.assertEquals("CheckoutDelta", rejoinAck.path("type").asText(),
                "stale lastSeen should prefer delta when op-log window is available");

        JsonNode payload = rejoinAck.path("payload");
        Assertions.assertEquals(1L, payload.path("fromRevision").asLong());
        Assertions.assertEquals(2L, payload.path("toRevision").asLong());
        Assertions.assertTrue(payload.path("opBatches").isArray());
        Assertions.assertEquals(2, payload.path("opBatches").size());
    }

    @Test
    void taggedJoinReceivesSnapshotAndRejectsWrites() throws Exception {
        assumeEnabled();

        String modelId = "ws-it-tag-" + UUID.randomUUID().toString().substring(0, 8);
        registerModel(modelId, "WebSocket Tag IT");
        URI wsUri = wsUri(modelId);

        QueueingListener headListener = new QueueingListener();
        QueueingListener tagListener = new QueueingListener();

        HttpClient client = HttpClient.newHttpClient();
        ws1 = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(wsUri, headListener).join();
        ws1.sendText(joinMessage("u1", "s1"), true).join();
        Assertions.assertNotNull(waitForAnyType(headListener, 10, "CheckoutSnapshot", "CheckoutDelta"));

        String createBatchId = UUID.randomUUID().toString();
        ws1.sendText(submitOpsMessage(createBatchId), true).join();
        Assertions.assertNotNull(waitForType(headListener, "OpsAccepted", 15));

        createTag(modelId, "release-1", "First snapshot");

        ws2 = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(wsUri, tagListener).join();
        ws2.sendText(joinMessage("u2", "s2", 0L, "release-1"), true).join();

        JsonNode taggedJoin = waitForAnyType(tagListener, 15, "CheckoutSnapshot", "CheckoutDelta");
        Assertions.assertNotNull(taggedJoin, "tagged join should receive checkout payload");
        Assertions.assertEquals("CheckoutSnapshot", taggedJoin.path("type").asText(),
                "tagged join should resolve to an immutable snapshot");
        Assertions.assertEquals(1L, taggedJoin.path("payload").path("headRevision").asLong());

        String rejectedBatchId = UUID.randomUUID().toString();
        ws2.sendText(submitOpsMessage(rejectedBatchId, "u2", "s2"), true).join();
        JsonNode error = waitForType(tagListener, "Error", 15);
        Assertions.assertNotNull(error, "tagged join should reject writes");
        Assertions.assertEquals("MODEL_REFERENCE_READ_ONLY", error.path("payload").path("code").asText());
    }

    private static void assumeEnabled() {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT"))
                        && "true".equalsIgnoreCase(System.getenv("RUN_WS_E2E_IT")),
                "Set RUN_LOCAL_INFRA_IT=true and RUN_WS_E2E_IT=true to run websocket end-to-end tests."
        );
    }

    private URI wsUri(String modelId) {
        URI http = URI.create(baseUri.toString() + "models/" + modelId + "/stream");
        return URI.create(http.toString().replaceFirst("^http", "ws"));
    }

    private static String joinMessage(String userId, String sessionId) {
        return joinMessage(userId, sessionId, 0L);
    }

    private static String joinMessage(String userId, String sessionId, long lastSeenRevision) {
        return joinMessage(userId, sessionId, lastSeenRevision, "HEAD");
    }

    private static String joinMessage(String userId, String sessionId, long lastSeenRevision, String ref) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "Join");
        ObjectNode payload = root.putObject("payload");
        payload.put("lastSeenRevision", Math.max(0L, lastSeenRevision));
        payload.put("ref", ref == null || ref.isBlank() ? "HEAD" : ref);
        ObjectNode actor = payload.putObject("actor");
        actor.put("userId", userId);
        actor.put("sessionId", sessionId);
        return root.toString();
    }

    private static String submitOpsMessage(String opBatchId) {
        return submitOpsMessage(opBatchId, "u1", "s1");
    }

    private static String submitOpsMessage(String opBatchId, String userId, String sessionId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "SubmitOps");

        ObjectNode payload = root.putObject("payload");
        payload.put("baseRevision", 0);
        payload.put("opBatchId", opBatchId);

        ObjectNode actor = payload.putObject("actor");
        actor.put("userId", userId);
        actor.put("sessionId", sessionId);

        ArrayNode ops = payload.putArray("ops");
        ObjectNode op = ops.addObject();
        op.put("type", "CreateElement");

        ObjectNode element = op.putObject("element");
        element.put("id", "elem:" + UUID.randomUUID().toString().substring(0, 8));
        element.put("archimateType", "BusinessActor");
        element.put("name", "WS IT Element");

        return root.toString();
    }

    private void registerModel(String modelId, String modelName) throws Exception {
        String encodedName = URLEncoder.encode(modelName, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/admin/models/" + modelId + "?modelName=" + encodedName))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "model registration failed: HTTP " + response.statusCode() + " body=" + response.body());
    }

    private void createTag(String modelId, String tagName, String description) throws Exception {
        String encodedTagName = URLEncoder.encode(tagName, StandardCharsets.UTF_8);
        String encodedDescription = URLEncoder.encode(description, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/admin/models/" + modelId
                        + "/tags?tagName=" + encodedTagName + "&description=" + encodedDescription))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "tag creation failed: HTTP " + response.statusCode() + " body=" + response.body());
    }

    private static String submitCreateViewObjectBootstrap(String opBatchId, String elementId, String viewId, String viewObjectId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "SubmitOps");

        ObjectNode payload = root.putObject("payload");
        payload.put("baseRevision", 0);
        payload.put("opBatchId", opBatchId);

        ObjectNode actor = payload.putObject("actor");
        actor.put("userId", "u1");
        actor.put("sessionId", "s1");

        ArrayNode ops = payload.putArray("ops");
        ObjectNode createElement = ops.addObject();
        createElement.put("type", "CreateElement");
        createElement.putObject("element")
                .put("id", elementId)
                .put("archimateType", "BusinessActor")
                .put("name", "Style IT Element");

        ObjectNode createView = ops.addObject();
        createView.put("type", "CreateView");
        createView.putObject("view")
                .put("id", viewId)
                .put("name", "Style IT View")
                .set("notationJson", MAPPER.createObjectNode());

        ObjectNode createViewObject = ops.addObject();
        createViewObject.put("type", "CreateViewObject");
        createViewObject.putObject("viewObject")
                .put("id", viewObjectId)
                .put("viewId", viewId)
                .put("representsId", elementId)
                .set("notationJson", MAPPER.createObjectNode()
                        .put("x", 40)
                        .put("y", 60)
                        .put("width", 120)
                        .put("height", 55));
        return root.toString();
    }

    private static String submitStyleUpdate(String opBatchId, String viewId, String viewObjectId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "SubmitOps");

        ObjectNode payload = root.putObject("payload");
        payload.put("baseRevision", 0);
        payload.put("opBatchId", opBatchId);

        ObjectNode actor = payload.putObject("actor");
        actor.put("userId", "u1");
        actor.put("sessionId", "s1");

        ArrayNode ops = payload.putArray("ops");
        ObjectNode styleUpdate = ops.addObject();
        styleUpdate.put("type", "UpdateViewObjectOpaque");
        styleUpdate.put("viewId", viewId);
        styleUpdate.put("viewObjectId", viewObjectId);
        styleUpdate.set("notationJson", MAPPER.createObjectNode()
                .put("fillColor", "#112233")
                .put("fontColor", "#ffeecc"));
        return root.toString();
    }

    private static JsonNode waitForType(QueueingListener listener, String type, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode message = listener.messages.poll(500, TimeUnit.MILLISECONDS);
            if (message == null) {
                continue;
            }
            if (type.equals(message.path("type").asText())) {
                return message;
            }
        }
        return null;
    }

    private static JsonNode waitForOpsBroadcast(QueueingListener listener, String opBatchId, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode message = listener.messages.poll(500, TimeUnit.MILLISECONDS);
            if (message == null) {
                continue;
            }
            if (!"OpsBroadcast".equals(message.path("type").asText())) {
                continue;
            }
            if (opBatchId.equals(message.path("payload").path("opBatch").path("opBatchId").asText())) {
                return message;
            }
        }
        return null;
    }

    private static BroadcastAttempt submitUntilBroadcast(WebSocket sender,
                                                         QueueingListener senderListener,
                                                         QueueingListener otherListener,
                                                         int maxAttempts,
                                                         java.util.function.Supplier<String> messageSupplier) throws Exception {
        JsonNode lastAccepted = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String message = messageSupplier.get();
            String opBatchId = MAPPER.readTree(message).path("payload").path("opBatchId").asText();
            sender.sendText(message, true).join();

            JsonNode accepted = waitForType(senderListener, "OpsAccepted", 15);
            JsonNode senderBroadcast = waitForOpsBroadcast(senderListener, opBatchId, 5);
            JsonNode otherBroadcast = waitForOpsBroadcast(otherListener, opBatchId, 5);
            if (accepted != null && senderBroadcast != null && otherBroadcast != null) {
                return new BroadcastAttempt(opBatchId, accepted, senderBroadcast, otherBroadcast);
            }
            lastAccepted = accepted;
            Thread.sleep(500L);
        }
        return new BroadcastAttempt(null, lastAccepted, null, null);
    }

    private record BroadcastAttempt(String opBatchId, JsonNode accepted, JsonNode senderBroadcast, JsonNode otherBroadcast) {
    }

    private static JsonNode waitForAnyType(QueueingListener listener, int timeoutSeconds, String... types) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode message = listener.messages.poll(500, TimeUnit.MILLISECONDS);
            if (message == null) {
                continue;
            }
            String messageType = message.path("type").asText();
            for (String type : types) {
                if (type.equals(messageType)) {
                    return message;
                }
            }
        }
        return null;
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
