package io.archi.collab.wire.outbound;

import com.fasterxml.jackson.databind.JsonNode;

public record OpsBroadcastMessage(JsonNode opBatch) {
}
