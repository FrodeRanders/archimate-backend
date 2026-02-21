package io.archi.collab.wire;

import com.fasterxml.jackson.databind.JsonNode;

public record ClientEnvelope(String type, JsonNode payload) {
}
