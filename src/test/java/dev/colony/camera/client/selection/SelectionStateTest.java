package dev.colony.camera.client.selection;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionStateTest {
    private static final SelectionState.Cell FIRST = new SelectionState.Cell(-2, 4, 8);
    private static final SelectionState.Cell SECOND = new SelectionState.Cell(3, 5, -1);
    private static final SelectionState.Cell THIRD = new SelectionState.Cell(7, 6, 2);

    @Test
    void volumeToggleReturnsToTheLastClickTool() {
        SelectionState state = new SelectionState();
        assertEquals(SelectionState.Mode.SINGLE, state.mode());
        state.toggleVolumeMode();
        assertEquals(SelectionState.Mode.VOLUME, state.mode());
        state.toggleVolumeMode();
        assertEquals(SelectionState.Mode.SINGLE, state.mode());

        state.selectMode(SelectionState.Mode.SIMILAR);
        state.toggleVolumeMode();
        state.selectMode(SelectionState.Mode.VOLUME);
        state.toggleVolumeMode();
        assertEquals(SelectionState.Mode.SIMILAR, state.mode());

        state.selectMode(SelectionState.Mode.SINGLE);
        state.toggleVolumeMode();
        state.toggleVolumeMode();
        assertEquals(SelectionState.Mode.SINGLE, state.mode());
    }

    @Test
    void choosingToolsPreservesCommittedCellsAndTheAppendSetting() {
        SelectionState state = new SelectionState();
        state.apply(List.of(FIRST));
        state.toggleAdditive();
        state.selectMode(SelectionState.Mode.SIMILAR);
        state.apply(List.of(SECOND));
        state.selectMode(SelectionState.Mode.VOLUME);
        state.selectMode(SelectionState.Mode.SINGLE);
        state.apply(List.of(THIRD));
        assertEquals(List.of(FIRST, SECOND, THIRD), new ArrayList<>(state.cells()));
        assertTrue(state.additive());

        state.clear();
        assertEquals(SelectionState.Mode.SINGLE, state.mode());
        assertTrue(state.additive());
        state.toggleAdditive();
        state.apply(List.of(FIRST));
        state.apply(List.of(SECOND));
        assertEquals(Set.of(SECOND), state.cells());
    }

    @Test
    void normalSelectionReplacesMembershipAndDeduplicatesInInsertionOrder() {
        SelectionState state = new SelectionState();
        assertEquals(new SelectionState.ApplyResult(true, true), state.apply(List.of(FIRST)));
        assertEquals(new SelectionState.ApplyResult(true, true), state.apply(List.of(SECOND, THIRD, SECOND)));
        assertEquals(List.of(SECOND, THIRD), new ArrayList<>(state.cells()));
        assertFalse(state.contains(FIRST));

        assertEquals(new SelectionState.ApplyResult(true, false), state.apply(List.of(THIRD, SECOND)));
        assertEquals(List.of(SECOND, THIRD), new ArrayList<>(state.cells()));
    }

    @Test
    void additiveSelectionKeepsExistingOrderAndUnionsDistinctCells() {
        SelectionState state = new SelectionState();
        state.apply(List.of(FIRST, SECOND));
        state.toggleAdditive();

        assertEquals(new SelectionState.ApplyResult(true, true), state.apply(List.of(SECOND, THIRD, THIRD)));
        assertEquals(List.of(FIRST, SECOND, THIRD), new ArrayList<>(state.cells()));
        assertEquals(3, state.size());
        assertEquals(new SelectionState.ApplyResult(true, false), state.apply(List.of(FIRST)));
    }

    @Test
    void capturedAppendModeControlsTheOperationWithoutChangingTheCurrentUiMode() {
        SelectionState state = new SelectionState();
        state.apply(List.of(FIRST));
        state.toggleAdditive();
        boolean capturedAppend = state.additive();
        state.toggleAdditive();

        assertEquals(new SelectionState.ApplyResult(true, true), state.apply(List.of(SECOND), capturedAppend));
        assertEquals(List.of(FIRST, SECOND), new ArrayList<>(state.cells()));
        assertFalse(state.additive());

        boolean capturedReplace = state.additive();
        state.toggleAdditive();
        assertEquals(new SelectionState.ApplyResult(true, true), state.apply(List.of(THIRD), capturedReplace));
        assertEquals(Set.of(THIRD), state.cells());
        assertTrue(state.additive());
    }

    @Test
    void emptyInputClearsNormalSelectionButPreservesAdditiveSelection() {
        SelectionState state = new SelectionState();
        state.apply(List.of(FIRST));
        assertEquals(new SelectionState.ApplyResult(true, true), state.apply(List.of()));
        assertEquals(0, state.size());
        assertEquals(new SelectionState.ApplyResult(true, false), state.apply(List.of()));

        state.apply(List.of(FIRST));
        state.toggleAdditive();
        assertEquals(new SelectionState.ApplyResult(true, false), state.apply(List.of()));
        assertEquals(Set.of(FIRST), state.cells());
        state.clear();
        assertEquals(0, state.size());
        assertTrue(state.additive());
    }

    @Test
    void oversizedReplacementRejectsAtomicallyAndKeepsPreviousSelection() {
        SelectionState state = new SelectionState();
        state.apply(List.of(FIRST, SECOND));
        List<SelectionState.Cell> oversized = line(SelectionState.MAX_BLOCKS + 1);

        assertEquals(new SelectionState.ApplyResult(false, false), state.apply(oversized));
        assertEquals(List.of(FIRST, SECOND), new ArrayList<>(state.cells()));
    }

    @Test
    void additiveLimitCountsUniqueCellsAndRejectsOnlyTheOversizedUnion() {
        SelectionState state = new SelectionState();
        List<SelectionState.Cell> maximum = line(SelectionState.MAX_BLOCKS);
        assertEquals(new SelectionState.ApplyResult(true, true), state.apply(maximum));
        state.toggleAdditive();

        assertEquals(new SelectionState.ApplyResult(true, false), state.apply(maximum));
        SelectionState.Cell beyondLimit = new SelectionState.Cell(SelectionState.MAX_BLOCKS, 0, 0);
        assertEquals(new SelectionState.ApplyResult(false, false), state.apply(List.of(beyondLimit)));
        assertEquals(maximum, new ArrayList<>(state.cells()));
        assertFalse(state.contains(beyondLimit));
    }

    @Test
    void resetClearsSelectionAndRestoresNormalMode() {
        SelectionState state = new SelectionState();
        assertFalse(state.additive());
        state.toggleAdditive();
        assertTrue(state.additive());
        state.toggleAdditive();
        assertFalse(state.additive());
        state.toggleAdditive();
        state.apply(List.of(FIRST));
        state.selectMode(SelectionState.Mode.SIMILAR);
        state.toggleVolumeMode();

        state.reset();
        assertEquals(0, state.size());
        assertFalse(state.additive());
        assertEquals(SelectionState.Mode.SINGLE, state.mode());
        state.toggleVolumeMode();
        state.toggleVolumeMode();
        assertEquals(SelectionState.Mode.SINGLE, state.mode());
    }

    @Test
    void removingInvalidCellsReportsMembershipChangesAndPreservesRemainingOrder() {
        SelectionState state = new SelectionState();
        state.apply(List.of(FIRST, SECOND, THIRD));

        assertTrue(state.removeIf(cell -> cell.equals(SECOND)));
        assertEquals(List.of(FIRST, THIRD), new ArrayList<>(state.cells()));
        assertFalse(state.removeIf(cell -> cell.equals(SECOND)));
    }

    @Test
    void exposedCellsCannotMutateTheSelection() {
        SelectionState state = new SelectionState();
        state.apply(List.of(FIRST));
        Set<SelectionState.Cell> view = state.cells();

        assertThrows(UnsupportedOperationException.class, () -> view.add(SECOND));
        assertThrows(UnsupportedOperationException.class, view::clear);
        var iterator = view.iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertEquals(Set.of(FIRST), state.cells());
    }

    @Test
    void cuboidNormalizesReversedNegativeCornersAndIncludesBothEndpoints() {
        SelectionState.Cuboid cuboid = new SelectionState.Cuboid(
                new SelectionState.Cell(-1, 0, 1), new SelectionState.Cell(-2, -1, 0));

        assertEquals(-2, cuboid.minX());
        assertEquals(-1, cuboid.minY());
        assertEquals(0, cuboid.minZ());
        assertEquals(-1, cuboid.maxX());
        assertEquals(0, cuboid.maxY());
        assertEquals(1, cuboid.maxZ());
        assertEquals(8, cuboid.volume());
        assertTrue(cuboid.withinLimit(8));
        assertFalse(cuboid.withinLimit(7));
        assertFalse(cuboid.withinLimit(0));
        List<SelectionState.Cell> cells = new ArrayList<>();
        cuboid.cells().forEach(cells::add);
        assertEquals(List.of(
                new SelectionState.Cell(-2, -1, 0), new SelectionState.Cell(-1, -1, 0),
                new SelectionState.Cell(-2, 0, 0), new SelectionState.Cell(-1, 0, 0),
                new SelectionState.Cell(-2, -1, 1), new SelectionState.Cell(-1, -1, 1),
                new SelectionState.Cell(-2, 0, 1), new SelectionState.Cell(-1, 0, 1)), cells);
    }

    @Test
    void extremeCoordinatesCannotOverflowVolumeLimitsOrIteratorEndpoints() {
        SelectionState.Cuboid enormous = new SelectionState.Cuboid(
                new SelectionState.Cell(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                new SelectionState.Cell(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, enormous.volume());
        assertFalse(enormous.withinLimit(Long.MAX_VALUE));

        SelectionState.Cuboid fullAxis = new SelectionState.Cuboid(
                new SelectionState.Cell(Integer.MIN_VALUE, 0, 0),
                new SelectionState.Cell(Integer.MAX_VALUE, 0, 0));
        assertEquals(4_294_967_296L, fullAxis.volume());
        assertTrue(fullAxis.withinLimit(4_294_967_296L));
        assertFalse(fullAxis.withinLimit(4_294_967_295L));

        SelectionState.Cuboid endpoint = new SelectionState.Cuboid(
                new SelectionState.Cell(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, Integer.MAX_VALUE),
                new SelectionState.Cell(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        var iterator = endpoint.cells().iterator();
        assertEquals(endpoint.first(), iterator.next());
        assertTrue(iterator.hasNext());
        assertEquals(endpoint.second(), iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    private static List<SelectionState.Cell> line(int length) {
        return IntStream.range(0, length).mapToObj(x -> new SelectionState.Cell(x, 0, 0)).toList();
    }
}
