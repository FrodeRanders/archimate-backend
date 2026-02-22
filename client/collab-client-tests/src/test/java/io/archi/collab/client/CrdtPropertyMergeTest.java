package io.archi.collab.client;

import com.archimatetool.collab.emf.CrdtPropertyMerge;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CrdtPropertyMergeTest {

    @Test
    void equalLamportUsesClientIdTieBreakForSet() {
        var incomingA = CrdtPropertyMerge.clock(10, "client-a");
        var incomingZ = CrdtPropertyMerge.clock(10, "client-z");

        Assertions.assertTrue(CrdtPropertyMerge.shouldApplySet(incomingA, null, null));
        Assertions.assertTrue(CrdtPropertyMerge.shouldApplySet(incomingZ, incomingA, null));
        Assertions.assertFalse(CrdtPropertyMerge.shouldApplySet(incomingA, incomingZ, null));
    }

    @Test
    void equalLamportDeleteCanBeatSetByClientId() {
        var setClock = CrdtPropertyMerge.clock(20, "client-a");
        var deleteClock = CrdtPropertyMerge.clock(20, "client-z");
        var staleSet = CrdtPropertyMerge.clock(20, "client-y");

        Assertions.assertTrue(CrdtPropertyMerge.shouldApplyUnset(deleteClock, setClock, null));
        Assertions.assertFalse(CrdtPropertyMerge.shouldApplySet(staleSet, null, deleteClock));
    }
}
