package io.archi.collab.service.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CrdtOrSetTest {

    @Test
    void staleAddDoesNotBeatExistingRemove() {
        var existingRemove = CrdtOrSet.clock(20, "client-z");
        var staleAdd = CrdtOrSet.clock(20, "client-a");

        Assertions.assertFalse(CrdtOrSet.shouldApplyAdd(staleAdd, null, existingRemove));
    }

    @Test
    void newerAddAfterRemoveRestoresPresence() {
        var existingAdd = CrdtOrSet.clock(10, "client-a");
        var existingRemove = CrdtOrSet.clock(11, "client-a");
        var newerAdd = CrdtOrSet.clock(12, "client-b");

        Assertions.assertTrue(CrdtOrSet.shouldApplyAdd(newerAdd, existingAdd, existingRemove));
        Assertions.assertTrue(CrdtOrSet.isPresent(newerAdd, existingRemove));
    }

    @Test
    void removeMustBeatLatestKnownClock() {
        var existingAdd = CrdtOrSet.clock(30, "client-z");
        var existingRemove = CrdtOrSet.clock(29, "client-a");
        var staleRemove = CrdtOrSet.clock(30, "client-a");
        var winningRemove = CrdtOrSet.clock(30, "client-zz");

        Assertions.assertFalse(CrdtOrSet.shouldApplyRemove(staleRemove, existingAdd, existingRemove));
        Assertions.assertTrue(CrdtOrSet.shouldApplyRemove(winningRemove, existingAdd, existingRemove));
        Assertions.assertFalse(CrdtOrSet.isPresent(existingAdd, winningRemove));
    }
}
