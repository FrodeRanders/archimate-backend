package io.archi.collab.wire.inbound;

import io.archi.collab.model.Actor;

import java.util.List;

public record ReleaseLockMessage(Actor actor, List<String> targets) {
}
