package dev.colony.camera.worker;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class WorkerNeedsTest {
    @Test void exposureSlowsWorkWithinABoundAndWaterActuallyRestoresIt() {
        WorkerNeeds needs = new WorkerNeeds(); needs.elapsed(72000, true, 100.0 / 72000, 20.0 / 24000);
        assertEquals(0, needs.hydration()); assertEquals(20, needs.social(), 0.001);
        needs.temperature(-50); assertEquals(0.6, needs.workSpeed(1), 0.001);
        needs.drink(1); needs.temperature(20); assertEquals(1, needs.workSpeed(1)); assertEquals(1, needs.drinks());
        needs.temperature(35); assertEquals(0, needs.thermalDiscomfort(2));
    }
    @Test void relationshipsRequireRealTimeAndReturnedSnapshotsCannotBeChanged() {
        WorkerNeeds needs = new WorkerNeeds(); var friend = Objects.requireNonNull(UUID.randomUUID());
        for (int i = 0; i < 100; i++) needs.converse(friend, 20);
        assertEquals(1, needs.relations().get(friend).affinity());
        var snapshot = needs.relations(); needs.converse(friend, 20);
        assertEquals(2000, snapshot.get(friend).togetherTicks());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        for (int i = 0; i < 64; i++) needs.converse(Objects.requireNonNull(UUID.randomUUID()), 20);
        assertEquals(32, needs.relations().size());
        assertFalse(needs.canConverse(Objects.requireNonNull(UUID.randomUUID())));
        assertTrue(needs.canConverse(friend));
    }
    @Test void careRewardsNeedAForwardDayAndRejectClockRewind() {
        WorkerNeeds needs = new WorkerNeeds(); assertTrue(needs.claimCare(0)); assertFalse(needs.claimCare(0));
        assertTrue(needs.claimCare(1)); assertFalse(needs.claimCare(0));
    }
    @Test void invalidNeedsAndUnboundedConversationAreRejected() {
        WorkerNeeds needs = new WorkerNeeds();
        assertThrows(IllegalArgumentException.class, () -> needs.restore(Double.NaN, 80, 0, 20, 0, 0, -1, Objects.requireNonNull(Map.of())));
        assertThrows(IllegalArgumentException.class, () -> needs.converse(Objects.requireNonNull(UUID.randomUUID()), 24000));
        assertThrows(IllegalArgumentException.class, () -> needs.temperature(201));
    }
}
