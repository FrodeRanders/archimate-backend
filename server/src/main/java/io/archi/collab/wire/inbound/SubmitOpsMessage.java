package io.archi.collab.wire.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import io.archi.collab.model.Actor;

public record SubmitOpsMessage(long baseRevision, String opBatchId, Actor actor, JsonNode ops) {
}
