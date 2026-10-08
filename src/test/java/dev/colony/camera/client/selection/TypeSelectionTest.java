package dev.colony.camera.client.selection;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class TypeSelectionTest {
    @Test void emptyFilterPreservesTheOriginalSelectionAndRestrictsOnlyExplicitTypes() {
        var filter = new TypeSelection(); assertTrue(filter.matches("minecraft:stone"));
        filter.set(Objects.requireNonNull(Set.of("minecraft:stone", "minecraft:oak_log")));
        assertTrue(filter.matches("minecraft:oak_log")); assertTrue(filter.matches("minecraft:stone")); assertFalse(filter.matches("minecraft:dirt"));
        filter.set(Objects.requireNonNull(Set.of())); assertTrue(filter.matches("minecraft:dirt"));
    }
    @Test void queuedSelectionOwnsAnImmutableSnapshotDespiteEditingTheLiveFilter() {
        var filter = new TypeSelection(); var types = new ArrayList<>(List.of("minecraft:stone")); filter.set(types);
        var captured = filter.snapshot(); types.add("minecraft:dirt"); filter.set(Objects.requireNonNull(Set.of("minecraft:oak_log")));
        assertEquals(Set.of("minecraft:stone"), captured); assertThrows(UnsupportedOperationException.class, () -> captured.add("minecraft:dirt"));
    }
    @Test void invalidOrOversizedInputLeavesThePreviousFilterIntact() {
        var filter = new TypeSelection(); filter.set(Objects.requireNonNull(Set.of("minecraft:stone")));
        assertThrows(IllegalArgumentException.class, () -> filter.set(Objects.requireNonNull(IntStream.range(0, 65).mapToObj(index -> "example:block_" + index).toList())));
        assertThrows(IllegalArgumentException.class, () -> filter.set(Objects.requireNonNull(List.of(""))));
        assertEquals(Set.of("minecraft:stone"), filter.snapshot());
    }
}
