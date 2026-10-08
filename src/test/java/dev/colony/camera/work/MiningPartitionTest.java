package dev.colony.camera.work;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

final class MiningPartitionTest {
    @Test void mixedHardnessIsBalancedByWorkerSpeedWithExclusiveSpatialOwnership() {
        var cells = new ArrayList<MiningFrontier.Cell>(); double[] costs = new double[120];
        for (int i = 0; i < costs.length; i++) { cells.add(new MiningFrontier.Cell(i, 0, 0)); costs[i] = i % 4 + 1; }
        double[] speeds = {1, 2, 3}; double[] loads = new double[3];
        int[] owners = MiningPartition.divide(cells, costs, speeds);
        int previous = 0;
        for (int i = 0; i < owners.length; i++) {
            assertTrue(owners[i] >= previous && owners[i] < 3, "Ownership should form contiguous exclusive sectors");
            previous = owners[i]; loads[owners[i]] += costs[i];
        }
        for (int worker = 0; worker < 3; worker++) assertEquals(50, loads[worker] / speeds[worker], 4);
    }

    @Test void airAndCompletedWorkReceiveNoOwner() {
        var cells = Objects.requireNonNull(List.of(new MiningFrontier.Cell(0, 0, 0), new MiningFrontier.Cell(1, 0, 0), new MiningFrontier.Cell(2, 0, 0)));
        assertArrayEquals(new int[]{-1, 0, -1}, MiningPartition.divide(cells, new double[]{0, 4, 0}, new double[]{1}));
        assertArrayEquals(new int[]{-1, -1, -1}, MiningPartition.divide(cells, new double[3], new double[]{1, 2}));
    }

    @Test void longAxisUsesWorldGeometryIncludingNegativeCoordinates() {
        assertFalse(MiningPartition.alongX(Objects.requireNonNull(List.of(new MiningFrontier.Cell(-100, 30, -40), new MiningFrontier.Cell(-99, -30, 40)))));
        assertTrue(MiningPartition.alongX(Objects.requireNonNull(List.of(new MiningFrontier.Cell(-100, 0, -40), new MiningFrontier.Cell(100, 0, -40)))));
    }

    @Test void invalidCostsAndSpeedsCannotCreateAnInvalidPartition() {
        var cells = Objects.requireNonNull(List.of(new MiningFrontier.Cell(0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> MiningPartition.divide(cells, new double[]{-1}, new double[]{1}));
        assertThrows(IllegalArgumentException.class, () -> MiningPartition.divide(cells, new double[]{1}, new double[]{Double.NaN}));
        assertThrows(IllegalArgumentException.class, () -> MiningPartition.divide(cells, new double[]{1}, new double[]{0}));
    }
}
