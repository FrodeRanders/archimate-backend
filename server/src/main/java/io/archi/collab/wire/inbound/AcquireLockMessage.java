package io.archi.collab.wire.inbound;

import io.archi.collab.model.Actor;
import java.util.List;

public record AcquireLockMessage(Actor actor, List<String> targets, Long ttlMs) {
}
