package io.archi.collab.wire.outbound;

import com.fasterxml.jackson.databind.JsonNode;

public record CheckoutDeltaMessage(long fromRevision, long toRevision, JsonNode opBatches) {
}
