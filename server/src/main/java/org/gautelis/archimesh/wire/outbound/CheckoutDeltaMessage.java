package org.gautelis.archimesh.wire.outbound;

import com.fasterxml.jackson.databind.JsonNode;

public record CheckoutDeltaMessage(long fromRevision, long toRevision, JsonNode opBatches) {
}
