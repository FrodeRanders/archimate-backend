package io.archi.collab.wire.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import io.archi.collab.model.Actor;
import java.util.List;

public record PresenceMessage(Actor actor, String viewId, List<String> selection, JsonNode cursor) {
}
