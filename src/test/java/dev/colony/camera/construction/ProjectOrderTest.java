package dev.colony.camera.construction;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProjectOrderTest {
    @Test void higherPriorityAlwaysPrecedesEarlierLowPriority() {
        var low = new ProjectOrder(Objects.requireNonNull(UUID.randomUUID()), 1, 0); var high = new ProjectOrder(Objects.requireNonNull(UUID.randomUUID()), 9, 1000);
        assertEquals(List.of(high, low), List.of(low, high).stream().sorted(ProjectOrder.COMPARATOR).toList());
    }
    @Test void equalPriorityUsesCreationSequence() {
        var first = new ProjectOrder(Objects.requireNonNull(UUID.randomUUID()), 5, 1); var second = new ProjectOrder(Objects.requireNonNull(UUID.randomUUID()), 5, 2);
        assertTrue(ProjectOrder.COMPARATOR.compare(first, second) < 0);
    }
    @Test void invalidPrioritiesAndSequenceAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectOrder(Objects.requireNonNull(UUID.randomUUID()), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectOrder(Objects.requireNonNull(UUID.randomUUID()), 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProjectOrder(Objects.requireNonNull(UUID.randomUUID()), 5, -1));
    }
}
