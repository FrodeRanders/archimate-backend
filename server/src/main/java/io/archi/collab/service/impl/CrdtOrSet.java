package io.archi.collab.service.impl;

/**
 * Merge helpers for OR-Set style member add/remove clocks.
 */
final class CrdtOrSet {

    private CrdtOrSet() {
    }

    static Clock clock(long lamport, String clientId) {
        return new Clock(lamport, clientId == null ? "" : clientId);
    }

    static boolean wins(Clock incoming, Clock existing) {
        if (incoming == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        if (incoming.lamport() > existing.lamport()) {
            return true;
        }
        if (incoming.lamport() < existing.lamport()) {
            return false;
        }
        return incoming.clientId().compareTo(existing.clientId()) >= 0;
    }

    static boolean shouldApplyAdd(Clock incomingAdd, Clock existingAdd, Clock existingRemove) {
        if (existingRemove != null && !wins(incomingAdd, existingRemove)) {
            return false;
        }
        return existingAdd == null || wins(incomingAdd, existingAdd);
    }

    static boolean shouldApplyRemove(Clock incomingRemove, Clock existingAdd, Clock existingRemove) {
        Clock baseline = max(existingAdd, existingRemove);
        return baseline == null || wins(incomingRemove, baseline);
    }

    static boolean isPresent(Clock addClock, Clock removeClock) {
        if (addClock == null) {
            return false;
        }
        if (removeClock == null) {
            return true;
        }
        return wins(addClock, removeClock);
    }

    private static Clock max(Clock a, Clock b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return wins(a, b) ? a : b;
    }

    record Clock(long lamport, String clientId) {
    }
}
