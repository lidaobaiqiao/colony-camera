package dev.colony.camera.work;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class MiningFrontierTest {
    @Test void incrementalExcavationMatchesFreshWorldExposure() {
        var cells = new ArrayList<MiningFrontier.Cell>();
        for (int x = -2; x <= 2; x++) for (int y = 0; y < 3; y++) for (int z = -2; z <= 2; z++) {
            cells.add(new MiningFrontier.Cell(x, y, z));
        }
        var solid = new HashSet<>(cells);
        solid.add(new MiningFrontier.Cell(0, 3, 0)); // Unselected roof is still an obstruction.
        MiningFrontier incremental = new MiningFrontier(cells);
        incremental.refresh(cell -> !solid.contains(cell));
        var completed = new HashSet<Integer>();
        for (int step = 0; step < cells.size(); step++) {
            int index = step * 17 % cells.size();
            solid.remove(cells.get(index)); completed.add(index);
            incremental.finish(index, cell -> !solid.contains(cell));
            MiningFrontier fresh = new MiningFrontier(cells);
            for (int done : completed) fresh.finish(done, cell -> !solid.contains(cell));
            fresh.refresh(cell -> !solid.contains(cell));
            assertEquals(fresh.visible(), incremental.visible());
            for (int i = 0; i < cells.size(); i++) assertEquals(fresh.mask(i), incremental.mask(i));
        }
    }

    @Test void unselectedWallAndUnloadedNeighborsAreNotOpenFaces() {
        var target = new MiningFrontier.Cell(0, 0, 0);
        MiningFrontier frontier = new MiningFrontier(Objects.requireNonNull(List.of(target)));
        Set<MiningFrontier.Cell> open = new HashSet<>();
        frontier.refresh(open::contains);
        assertEquals(0, frontier.exposure(0));
        open.add(target.offset(0, 1, 0)); frontier.changed(target.offset(0, 1, 0), open::contains);
        assertEquals(1, frontier.exposure(0));
        open.clear(); frontier.changed(target.offset(0, 1, 0), open::contains);
        assertTrue(frontier.visible().isEmpty());
    }

    @Test void localQueryWorksAcrossNegativeBucketBoundariesAndExcludesCompletedBlocks() {
        var center = new MiningFrontier.Cell(-8, -8, -8);
        MiningFrontier frontier = new MiningFrontier(Objects.requireNonNull(List.of(center, center.offset(1, 0, 0), center.offset(-1, 0, 0), center.offset(2, 0, 0))));
        assertEquals(Set.of(0, 1, 2), new HashSet<>(frontier.nearby(center, 1)));
        frontier.finish(1, cell -> true);
        assertEquals(Set.of(0, 2), new HashSet<>(frontier.nearby(center, 1)));
        assertThrows(IllegalArgumentException.class, () -> frontier.nearby(center, -1));
    }

    @Test void duplicateTargetsAreRejectedAndVisibleSnapshotCannotMutatePlanner() {
        var cell = new MiningFrontier.Cell(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new MiningFrontier(Objects.requireNonNull(List.of(cell, cell))));
        MiningFrontier frontier = new MiningFrontier(Objects.requireNonNull(List.of(cell)));
        frontier.refresh(position -> true);
        frontier.visible().clear();
        assertEquals(6, frontier.exposure(0)); assertTrue(frontier.visible().get(0));
    }
}
