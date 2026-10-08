package dev.colony.camera.work;

import org.junit.jupiter.api.Test;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

final class MiningProgressTest {
    @Test void reassignmentCannotLoseProgressOrCountOneTickTwice() {
        MiningProgress progress = new MiningProgress(1);
        assertEquals(3_000, progress.advance(0, 3_000, 10));
        progress.remember(0, 0); // A new task or worker has no private checkpoint.
        assertEquals(3_000, progress.advance(0, 3_000, 10));
        assertEquals(6_000, progress.advance(0, 3_000, 11));
        assertEquals(10_000, progress.advance(0, Long.MAX_VALUE, 12));
    }

    @Test void saveReloadPreservesPartialWorkAndClearedTargetsStayEmpty() {
        MiningProgress progress = new MiningProgress(3); progress.remember(0, 6_000); progress.remember(2, 9_000);
        MiningProgress restored = new MiningProgress(3); restored.restore(progress.snapshot());
        assertEquals(6_000, restored.get(0)); assertEquals(0, restored.get(1)); assertEquals(9_000, restored.get(2));
        restored.clear(0); assertArrayEquals(new int[]{2, 9_000}, restored.snapshot());
    }

    @Test void badSaveEntriesAreRejectedBeforeExistingDataIsChanged() {
        MiningProgress progress = new MiningProgress(2); progress.remember(1, 7_000);
        for (int[] malformed : new int[][]{{0}, {0, 1, 0, 2}, {-1, 1}, {2, 1}, {0, 0}, {0, 10_001}}) {
            assertThrows(IllegalArgumentException.class, () -> progress.restore(Objects.requireNonNull(malformed)));
            assertArrayEquals(new int[]{1, 7_000}, progress.snapshot());
        }
    }

    @Test void invalidUpdatesCannotWrapProgress() {
        MiningProgress progress = new MiningProgress(1);
        assertThrows(IllegalArgumentException.class, () -> progress.advance(0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> progress.remember(0, 10_001));
        assertEquals(0, progress.get(0));
    }
}
