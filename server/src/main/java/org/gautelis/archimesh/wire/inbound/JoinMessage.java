package org.gautelis.archimesh.wire.inbound;

import org.gautelis.archimesh.model.Actor;

public record JoinMessage(Long lastSeenRevision, Actor actor, String ref) {
}
