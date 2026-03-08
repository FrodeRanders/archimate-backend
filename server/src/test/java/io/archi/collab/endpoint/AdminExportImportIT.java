package io.archi.collab.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.archi.collab.service.CollaborationService;
import io.archi.collab.wire.inbound.SubmitOpsMessage;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@QuarkusTest
class AdminExportImportIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void exportAndImportEndpointsRoundTripModelHistoryAndTags() throws Exception {
        assumeLocalInfra();

        String sourceModelId = "export-src-" + suffix();
        String importedModelId = "export-dst-" + suffix();
        modelsToDelete.add(sourceModelId);
        modelsToDelete.add(importedModelId);

        collaborationService.registerModel(sourceModelId, "Export Source");
        collaborationService.onSubmitOps(sourceModelId,
                new SubmitOpsMessage(0L, "batch-" + suffix(), null, singleCreateElementOp("elem:" + suffix())));
        collaborationService.createModelTag(sourceModelId, "release-1", "First release");

        JsonNode exported = getJson("/admin/models/" + sourceModelId + "/export");
        Assertions.assertEquals("archi-model-export-v1", exported.path("format").asText());
        Assertions.assertEquals(sourceModelId, exported.path("model").path("modelId").asText());
        Assertions.assertEquals(1, exported.path("tags").size());

        ObjectNode importedPayload = exported.deepCopy();
        importedPayload.with("model").put("modelId", importedModelId);
        importedPayload.with("model").put("modelName", "Imported Copy");
        importedPayload.with("snapshot").put("modelId", importedModelId);
        for (JsonNode batch : importedPayload.withArray("opBatches")) {
            ((ObjectNode) batch).put("modelId", importedModelId);
        }
        for (JsonNode tag : importedPayload.withArray("tags")) {
            ((ObjectNode) tag).put("modelId", importedModelId);
            ((ObjectNode) tag.with("snapshot")).put("modelId", importedModelId);
        }

        JsonNode importResult = postJson("/admin/models/import?overwrite=false", importedPayload, 200);
        Assertions.assertEquals(importedModelId, importResult.path("modelId").asText());
        Assertions.assertFalse(importResult.path("overwritten").asBoolean());
        Assertions.assertEquals(1L, importResult.path("headRevision").asLong());

        JsonNode importedSnapshot = getJson("/models/" + importedModelId + "/snapshot");
        Assertions.assertEquals(importedModelId, importedSnapshot.path("modelId").asText());
        Assertions.assertEquals(1L, importedSnapshot.path("headRevision").asLong());
        Assertions.assertEquals(1, importedSnapshot.path("elements").size());

        JsonNode importedTags = getJson("/admin/models/" + importedModelId + "/tags");
        Assertions.assertEquals(1, importedTags.size());
        Assertions.assertEquals("release-1", importedTags.get(0).path("tagName").asText());
    }

    @Test
    void importEndpointRejectsExistingModelWithoutOverwrite() throws Exception {
        assumeLocalInfra();

        String modelId = "import-existing-" + suffix();
        modelsToDelete.add(modelId);
        collaborationService.registerModel(modelId, "Existing Model");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("format", "archi-model-export-v1");
        payload.put("exportedAt", "2026-03-08T12:00:00Z");
        payload.set("model", MAPPER.createObjectNode()
                .put("modelId", modelId)
                .put("modelName", "Existing Model")
                .put("headRevision", 0));
        payload.set("snapshot", emptySnapshot(modelId));
        payload.set("opBatches", MAPPER.createArrayNode());
        payload.set("tags", MAPPER.createArrayNode());

        HttpResponse<String> response = sendJson("/admin/models/import?overwrite=false", payload);
        Assertions.assertEquals(409, response.statusCode());
    }

    private static void assumeLocalInfra() {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("RUN_LOCAL_INFRA_IT")),
                "Set RUN_LOCAL_INFRA_IT=true to run admin export/import integration tests."
        );
    }

    private JsonNode getJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "GET failed: HTTP " + response.statusCode() + " body=" + response.body());
        return MAPPER.readTree(response.body());
    }

    private JsonNode postJson(String path, JsonNode payload, int expectedStatus) throws Exception {
        HttpResponse<String> response = sendJson(path, payload);
        Assertions.assertEquals(expectedStatus, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> sendJson(String path, JsonNode payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode singleCreateElementOp(String elementId) {
        ArrayNode ops = MAPPER.createArrayNode();
        ObjectNode op = ops.addObject();
        op.put("type", "CreateElement");
        op.putObject("element")
                .put("id", elementId)
                .put("archimateType", "BusinessActor")
                .put("name", "Imported");
        return ops;
    }

    private static ObjectNode emptySnapshot(String modelId) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("format", "archimate-materialized-v1");
        snapshot.put("modelId", modelId);
        snapshot.put("headRevision", 0);
        snapshot.set("elements", MAPPER.createArrayNode());
        snapshot.set("relationships", MAPPER.createArrayNode());
        snapshot.set("views", MAPPER.createArrayNode());
        snapshot.set("viewObjects", MAPPER.createArrayNode());
        snapshot.set("viewObjectChildMembers", MAPPER.createArrayNode());
        snapshot.set("connections", MAPPER.createArrayNode());
        return snapshot;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
