package dev.colony.camera.client.selection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionBoxesTest {
    @Test
    void fullRectangleBecomesOneBoxRegardlessOfInputOrder() {
        SelectionState.Cuboid rectangle = box(-3, 5, 2, 6, 5, 8);
        List<SelectionState.Cell> reversed = new ArrayList<>(cells(rectangle));
        Collections.reverse(reversed);
        Set<SelectionState.Cell> input = new LinkedHashSet<>(reversed);

        assertEquals(List.of(rectangle), SelectionBoxes.merge(input));
        assertExactCoverage(input, SelectionBoxes.merge(input));
    }

    @Test
    void lShapeDoesNotFillItsMissingCorner() {
        Set<SelectionState.Cell> input = Set.of(new SelectionState.Cell(0, 0, 0),
                new SelectionState.Cell(1, 0, 0), new SelectionState.Cell(0, 0, 1));

        List<SelectionState.Cuboid> boxes = SelectionBoxes.merge(input);

        assertEquals(2, boxes.size());
        assertExactCoverage(input, boxes);
    }

    @Test
    void disconnectedNegativeBlocksRemainSeparate() {
        Set<SelectionState.Cell> input = new LinkedHashSet<>();
        input.addAll(cells(box(-20, -4, -10, -18, -4, -10)));
        input.addAll(cells(box(-3, -4, -10, -2, -4, -10)));

        List<SelectionState.Cuboid> boxes = SelectionBoxes.merge(input);

        assertEquals(2, boxes.size());
        assertExactCoverage(input, boxes);
    }

    @Test
    void fullCuboidAndAnInteriorHoleAreCoveredWithoutDuplicates() {
        SelectionState.Cuboid volume = box(-8, -2, -8, 7, 13, 7);
        Set<SelectionState.Cell> input = cells(volume);
        assertEquals(List.of(volume), SelectionBoxes.merge(input));
        input.remove(new SelectionState.Cell(0, 5, 0));

        List<SelectionState.Cuboid> boxes = SelectionBoxes.merge(input);

        assertTrue(boxes.size() > 1);
        assertExactCoverage(input, boxes);
    }

    @Test
    void integerEndpointsDoNotWrapAndMergeAdjacentExtremeCells() {
        SelectionState.Cuboid high = box(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1,
                Integer.MAX_VALUE - 1, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        SelectionState.Cuboid low = box(Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 1);
        Set<SelectionState.Cell> input = cells(high);
        input.addAll(cells(low));

        List<SelectionState.Cuboid> boxes = SelectionBoxes.merge(input);

        assertEquals(List.of(low, high), boxes);
        assertExactCoverage(input, boxes);
    }

    @Test
    void emptyAndNonemptyResultsAreImmutableAndDoNotMutateInput() {
        assertTrue(SelectionBoxes.merge(Set.of()).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> SelectionBoxes.merge(Set.of()).add(box(0, 0, 0, 0, 0, 0)));
        Set<SelectionState.Cell> input = cells(box(0, 0, 0, 1, 1, 1));
        Set<SelectionState.Cell> original = Set.copyOf(input);
        List<SelectionState.Cuboid> result = SelectionBoxes.merge(input);

        assertThrows(UnsupportedOperationException.class, result::clear);
        assertEquals(original, input);
        input.clear();
        assertEquals(List.of(box(0, 0, 0, 1, 1, 1)), result);
    }

    @Test
    void fullSelectionLimitCompressesToOneBox() {
        SelectionState.Cuboid volume = box(0, 0, 0, 31, 7, 31);
        Set<SelectionState.Cell> input = cells(volume);

        assertEquals(SelectionState.MAX_BLOCKS, input.size());
        assertEquals(List.of(volume), SelectionBoxes.merge(input));
        assertExactCoverage(input, SelectionBoxes.merge(input));
    }

    private static SelectionState.Cuboid box(int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        return new SelectionState.Cuboid(new SelectionState.Cell(minX, minY, minZ),
                new SelectionState.Cell(maxX, maxY, maxZ));
    }

    private static Set<SelectionState.Cell> cells(SelectionState.Cuboid box) {
        Set<SelectionState.Cell> cells = new LinkedHashSet<>();
        for (SelectionState.Cell cell : box.cells()) {
            cells.add(cell);
        }
        return cells;
    }

    private static void assertExactCoverage(Set<SelectionState.Cell> input,
            List<SelectionState.Cuboid> boxes) {
        Set<SelectionState.Cell> covered = new HashSet<>();
        for (SelectionState.Cuboid box : boxes) {
            assertTrue(box.withinLimit(SelectionState.MAX_BLOCKS));
            for (SelectionState.Cell cell : box.cells()) {
                assertTrue(covered.add(cell), "Boxes overlap at " + cell);
            }
        }
        assertEquals(input, covered);
    }
}
