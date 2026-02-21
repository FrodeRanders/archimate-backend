package io.archi.collab.service;

import io.archi.collab.model.Actor;
import io.archi.collab.wire.outbound.LockEventMessage;
import java.util.List;
import java.util.Optional;

public interface LockService {
    Optional<String> checkLockConflicts(String modelId, Actor actor, List<String> targets);

    LockEventMessage acquire(String modelId, Actor actor, List<String> targets, long ttlMs);

    LockEventMessage release(String modelId, Actor actor, List<String> targets);
}
