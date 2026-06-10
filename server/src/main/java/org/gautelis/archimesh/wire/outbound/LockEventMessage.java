package org.gautelis.archimesh.wire.outbound;

import org.gautelis.archimesh.model.Actor;

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
