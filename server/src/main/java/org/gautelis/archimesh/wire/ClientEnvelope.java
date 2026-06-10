package org.gautelis.archimesh.wire;

import com.fasterxml.jackson.databind.JsonNode;

public record ClientEnvelope(String type, JsonNode payload) {
}
