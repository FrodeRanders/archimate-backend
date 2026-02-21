package io.archi.collab.model;

import java.time.Instant;

public record LockState(String targetId, Actor owner, long ttlMs, Instant expiresAt) {
    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }
}
