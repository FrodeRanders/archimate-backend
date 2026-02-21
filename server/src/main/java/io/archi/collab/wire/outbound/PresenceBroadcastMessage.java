package io.archi.collab.wire.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import io.archi.collab.model.Actor;
import java.time.Instant;
import java.util.List;

public record PresenceBroadcastMessage(Actor actor, Instant ts, String viewId, List<String> selection, JsonNode cursor) {
}
