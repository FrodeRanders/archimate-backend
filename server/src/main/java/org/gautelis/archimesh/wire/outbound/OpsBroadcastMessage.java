package org.gautelis.archimesh.wire.outbound;

import com.fasterxml.jackson.databind.JsonNode;

public record OpsBroadcastMessage(JsonNode opBatch) {
}
