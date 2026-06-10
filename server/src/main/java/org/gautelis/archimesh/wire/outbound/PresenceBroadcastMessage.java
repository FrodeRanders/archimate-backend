package org.gautelis.archimesh.wire.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import org.gautelis.archimesh.model.Actor;

import java.time.Instant;
import java.util.List;

public record PresenceBroadcastMessage(Actor actor, Instant ts, String viewId, List<String> selection,
                                       JsonNode cursor) {
}
