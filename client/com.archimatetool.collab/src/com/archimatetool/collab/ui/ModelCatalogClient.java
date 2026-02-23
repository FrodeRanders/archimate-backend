package com.archimatetool.collab.ui;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.archimatetool.collab.util.SimpleJson;

/**
 * Lightweight admin catalog client used by connection dialogs.
 */
public final class ModelCatalogClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private ModelCatalogClient() {
    }

    public static List<ModelOption> fetchModels(String wsBaseUrl) throws IOException, InterruptedException {
        URI wsUri = normalizeUri(wsBaseUrl);
        String scheme = wsUri.getScheme();
        String httpScheme = "wss".equalsIgnoreCase(scheme) ? "https" : "http";
        URI overviewUri;
        try {
            overviewUri = new URI(
                    httpScheme,
                    wsUri.getUserInfo(),
                    wsUri.getHost(),
                    wsUri.getPort(),
                    "/admin/models",
                    null,
                    null);
        }
        catch(URISyntaxException ex) {
            throw new IOException("Invalid admin overview URI from WebSocket base URL", ex);
        }

        HttpRequest request = HttpRequest.newBuilder(overviewUri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if(response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to load model catalog: HTTP " + response.statusCode());
        }

        List<String> items = SimpleJson.splitArrayObjects(response.body());
        List<ModelOption> result = new ArrayList<>();
        for(String item : items) {
            String modelId = SimpleJson.readStringField(item, "modelId");
            if(modelId == null || modelId.isBlank()) {
                continue;
            }
            // Optional future-compatible display name fields.
            String modelName = SimpleJson.readStringField(item, "modelName");
            if(modelName == null || modelName.isBlank()) {
                modelName = SimpleJson.readStringField(item, "name");
            }
            result.add(new ModelOption(modelId, modelName));
        }
        return result;
    }

    public static URI normalizeUri(String value) throws IOException {
        if(value == null || value.isBlank()) {
            throw new IOException("WebSocket base URL is required");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        }
        catch(IllegalArgumentException ex) {
            throw new IOException("Invalid WebSocket base URL", ex);
        }
        String scheme = uri.getScheme();
        if(scheme == null || (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme))) {
            throw new IOException("WebSocket base URL must use ws:// or wss://");
        }
        if(uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("WebSocket base URL must include host");
        }
        return uri;
    }

    public record ModelOption(String modelId, String modelName) {
        public String label() {
            if(modelName != null && !modelName.isBlank() && !modelName.equals(modelId)) {
                return modelName + " (" + modelId + ")";
            }
            return modelId;
        }
    }
}
