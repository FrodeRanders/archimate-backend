package io.archi.collab.wire.inbound;

import io.archi.collab.model.Actor;

public record JoinMessage(Long lastSeenRevision, Actor actor, String ref) {
}
