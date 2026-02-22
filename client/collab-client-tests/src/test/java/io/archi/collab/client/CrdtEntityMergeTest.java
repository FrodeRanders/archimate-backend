package io.archi.collab.client;

import com.archimatetool.collab.emf.CrdtEntityMerge;
import com.archimatetool.collab.emf.CrdtPropertyMerge;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CrdtEntityMergeTest {

    @Test
    void staleRecreateRejectedWhenTombstoneWins() {
        var tombstone = CrdtPropertyMerge.clock(10, "client-z");
        var staleRecreate = CrdtPropertyMerge.clock(10, "client-a");

        Assertions.assertFalse(CrdtEntityMerge.shouldApplyCreate(staleRecreate, tombstone));
    }

    @Test
    void recreateAllowedWhenIncomingWinsOverTombstone() {
        var tombstone = CrdtPropertyMerge.clock(10, "client-z");
        var newerLamport = CrdtPropertyMerge.clock(11, "client-a");
        var equalLamportHigherClient = CrdtPropertyMerge.clock(10, "client-zz");

        Assertions.assertTrue(CrdtEntityMerge.shouldApplyCreate(newerLamport, tombstone));
        Assertions.assertTrue(CrdtEntityMerge.shouldApplyCreate(equalLamportHigherClient, tombstone));
    }

    @Test
    void tombstoneAdvancesOnlyWhenIncomingWins() {
        var current = CrdtPropertyMerge.clock(20, "client-z");
        var stale = CrdtPropertyMerge.clock(19, "client-a");
        var tieLoser = CrdtPropertyMerge.clock(20, "client-y");
        var tieWinner = CrdtPropertyMerge.clock(20, "client-zz");

        Assertions.assertFalse(CrdtEntityMerge.shouldAdvanceTombstone(stale, current));
        Assertions.assertFalse(CrdtEntityMerge.shouldAdvanceTombstone(tieLoser, current));
        Assertions.assertTrue(CrdtEntityMerge.shouldAdvanceTombstone(tieWinner, current));
    }
}
