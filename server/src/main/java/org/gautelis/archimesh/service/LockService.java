package org.gautelis.archimesh.service;

import org.gautelis.archimesh.model.Actor;
import org.gautelis.archimesh.wire.outbound.LockEventMessage;

import java.util.List;
import java.util.Optional;

public interface LockService {
    Optional<String> checkLockConflicts(String modelId, Actor actor, List<String> targets);

    LockEventMessage acquire(String modelId, Actor actor, List<String> targets, long ttlMs);

    LockEventMessage release(String modelId, Actor actor, List<String> targets);

    void clearModel(String modelId);
}
