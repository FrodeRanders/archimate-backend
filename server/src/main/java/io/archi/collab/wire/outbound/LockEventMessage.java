package io.archi.collab.wire.outbound;

import io.archi.collab.model.Actor;

import java.time.Instant;
import java.util.List;

public record LockEventMessage(
        String eventId,
        String eventType,
        Actor owner,
        List<String> targets,
        Instant ts,
        long ttlMs,
        String reason
) {
}
