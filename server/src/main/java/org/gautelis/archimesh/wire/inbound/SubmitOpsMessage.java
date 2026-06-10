package org.gautelis.archimesh.wire.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import org.gautelis.archimesh.model.Actor;

public record SubmitOpsMessage(long baseRevision, String opBatchId, Actor actor, JsonNode ops) {
}
