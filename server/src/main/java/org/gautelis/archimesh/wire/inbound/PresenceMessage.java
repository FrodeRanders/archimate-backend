package org.gautelis.archimesh.wire.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import org.gautelis.archimesh.model.Actor;

import java.util.List;

public record PresenceMessage(Actor actor, String viewId, List<String> selection, JsonNode cursor) {
}
