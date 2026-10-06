package dev.colony.camera.resources;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ReservationLedgerTest {
    @Test void batchReservationsAreAtomicAndShareTheConstructionLedger() {
        var ledger = new ReservationLedger(); var first = UUID.randomUUID(); var second = UUID.randomUUID(); var crafter = UUID.randomUUID(); var project = UUID.randomUUID();
        assertTrue(ledger.reserve(UUID.randomUUID(), project, first, 4, 3));
        assertFalse(ledger.reserveBatch(crafter, project, java.util.Map.of(first, new ReservationLedger.Demand(4, 2), second, new ReservationLedger.Demand(2, 1))));
        assertEquals(0, ledger.reserved(second));
        assertTrue(ledger.reserveBatch(crafter, project, java.util.Map.of(first, new ReservationLedger.Demand(4, 1), second, new ReservationLedger.Demand(2, 2))));
        assertEquals(4, ledger.reserved(first)); assertEquals(2, ledger.reserved(second));
        assertFalse(ledger.reserve(UUID.randomUUID(), project, second, 2, 1));
        ledger.release(crafter); assertEquals(3, ledger.reserved(first)); assertEquals(0, ledger.reserved(second));
    }
    @Test void multipleWorkersCannotClaimTheSameLastItem() {
        var ledger = new ReservationLedger(); var source = UUID.randomUUID(); var project = UUID.randomUUID();
        assertTrue(ledger.reserve(UUID.randomUUID(), project, source, 1, 1));
        assertFalse(ledger.reserve(UUID.randomUUID(), project, source, 1, 1));
        assertEquals(1, ledger.reserved(source));
    }
    @Test void releasingOrReallocatingMakesItemsAvailableWithoutInventingStock() {
        var ledger = new ReservationLedger(); var source = UUID.randomUUID(); var low = UUID.randomUUID(); var worker = UUID.randomUUID();
        assertTrue(ledger.reserve(worker, low, source, 2, 2));
        ledger.release(worker); assertEquals(0, ledger.reserved(source));
        assertTrue(ledger.reserve(worker, UUID.randomUUID(), source, 2, 2));
        ledger.clear(); assertEquals(0, ledger.reserved(source)); assertNull(ledger.claim(worker));
        assertFalse(ledger.reserve(worker, low, source, 0, 1));
    }
    @Test void stockShrinkNeverAllowsAdditionalClaimsAndOneWorkerHasOneClaim() {
        var ledger = new ReservationLedger(); var source = UUID.randomUUID(); var worker = UUID.randomUUID(); var project = UUID.randomUUID();
        assertTrue(ledger.reserve(worker, project, source, 4, 3));
        assertFalse(ledger.reserve(worker, project, UUID.randomUUID(), 10, 1));
        assertFalse(ledger.reserve(UUID.randomUUID(), project, source, 2, 1));
        assertFalse(ledger.reserve(UUID.randomUUID(), project, source, 4, 0));
    }
}
