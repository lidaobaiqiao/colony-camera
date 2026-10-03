package dev.colony.camera.client.selection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceSelectionJobTest {
    @Test
    void upwardSelectionFollowsUnevenHeightsAndDoesNotSelectBuriedBlocks() {
        RecordingSource source = new RecordingSource();
        source.surfaces.addAll(Set.of(cell(0, 6, -1), cell(0, 1, -1),
                cell(1, -2, -1), cell(1, -4, -1)));
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(0, 0, -1, 2, 0, -1),
                FaceSelection.Face.UP, range(-4, 7), source);

        finish(job);

        assertEquals(Set.of(cell(0, 6, -1), cell(1, -2, -1)), job.cells());
        assertFalse(source.sampled.contains(cell(0, 1, -1)));
        assertFalse(source.sampled.contains(cell(1, -4, -1)));
        assertFalse(job.failed());
    }

    @Test
    void downwardSelectionTakesTheLowestSurfaceOfEachColumn() {
        RecordingSource source = new RecordingSource();
        source.surfaces.addAll(Set.of(cell(-2, 5, -1), cell(-2, -3, -1),
                cell(-1, 4, -1), cell(-1, 2, -1)));
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(-2, 0, -1, -1, 0, -1),
                FaceSelection.Face.DOWN, range(-4, 6), source);

        finish(job);

        assertEquals(Set.of(cell(-2, -3, -1), cell(-1, 2, -1)), job.cells());
        assertFalse(source.sampled.contains(cell(-2, 5, -1)));
        assertFalse(source.sampled.contains(cell(-1, 4, -1)));
    }

    @Test
    void allSixFacesScanFromTheirOutwardBoundaryTowardTheInterior() {
        SelectionState.Cell anchor = cell(7, 8, 9);
        for (FaceSelection.Face face : FaceSelection.Face.values()) {
            RecordingSource source = new RecordingSource();
            source.surfaces.add(withNormal(anchor, face.axis(), -2));
            source.surfaces.add(withNormal(anchor, face.axis(), 4));
            SurfaceSelectionJob job = new SurfaceSelectionJob(new SelectionState.Cuboid(anchor, anchor),
                    face, range(-3, 5), source);

            finish(job);

            int expected = face.sign() > 0 ? 4 : -2;
            assertEquals(Set.of(withNormal(anchor, face.axis(), expected)), job.cells(), face.toString());
            assertEquals(withNormal(anchor, face.axis(), face.sign() > 0 ? 5 : -3), source.sampled.get(0));
            assertEquals(2, source.sampleCalls);
        }
    }

    @Test
    void sideFacesPreserveBothTangentCoordinatesAcrossUnevenWallDepths() {
        for (FaceSelection.Face face : new FaceSelection.Face[] {
                FaceSelection.Face.WEST, FaceSelection.Face.EAST,
                FaceSelection.Face.NORTH, FaceSelection.Face.SOUTH}) {
            SelectionState.Cell first = withNormal(cell(-4, -3, -2), face.axis(), 0);
            SelectionState.Cell second = withNormal(cell(-2, -1, 0), face.axis(), 0);
            SelectionState.Cuboid footprint = new SelectionState.Cuboid(first, second);
            RecordingSource source = new RecordingSource();
            Set<SelectionState.Cell> expected = new HashSet<>();
            int normal = -2;
            for (SelectionState.Cell column : footprint.cells()) {
                SelectionState.Cell surface = withNormal(column, face.axis(), normal);
                expected.add(surface);
                source.surfaces.add(surface);
                normal = normal == 2 ? -2 : normal + 1;
            }
            SurfaceSelectionJob job = new SurfaceSelectionJob(footprint, face, range(-2, 2), source);

            finish(job);

            assertEquals(expected, job.cells(), face.toString());
            assertEquals(footprint.volume(), job.cells().size());
        }
    }

    @Test
    void negativeCoordinatesAndReversedFootprintCornersKeepTheSameColumns() {
        RecordingSource source = new RecordingSource();
        source.surfaces.addAll(Set.of(cell(-4, -8, -7), cell(-3, -5, -7), cell(-4, -6, -6)));
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(-3, 1, -6, -4, 1, -7),
                FaceSelection.Face.UP, range(-9, 2), source);

        finish(job);

        assertEquals(source.surfaces, job.cells());
        assertEquals(4, source.rangeCalls);
    }

    @Test
    void emptyColumnsFinishWithoutCreatingPlaceholderCells() {
        RecordingSource source = new RecordingSource();
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(0, 0, 0, 1, 0, 1),
                FaceSelection.Face.UP, range(-1, 1), source);

        finish(job);

        assertTrue(job.cells().isEmpty());
        assertFalse(job.failed());
        assertEquals(4, source.rangeCalls);
        assertEquals(12, source.sampleCalls);
    }

    @Test
    void knownEmptyRangeSkipsAllSamplesForThatColumn() {
        RecordingSource source = new RecordingSource();
        source.ranges.put(cell(0, 0, 0), null);
        source.ranges.put(cell(1, 0, 0), range(4, 4));
        source.surfaces.add(cell(1, 4, 0));
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(0, 0, 0, 1, 0, 0),
                FaceSelection.Face.UP, range(-64, 319), source);

        job.step();
        assertEquals(1, source.rangeCalls);
        assertEquals(0, source.sampleCalls);
        assertFalse(job.done());
        finish(job);

        assertEquals(Set.of(cell(1, 4, 0)), job.cells());
        assertEquals(1, source.sampleCalls);
    }

    @Test
    void optimizedRangesAreIntersectedWithTheRequestedSearch() {
        RecordingSource source = new RecordingSource();
        source.ranges.put(cell(0, 0, 0), range(-100, 3));
        source.ranges.put(cell(1, 0, 0), range(2, 100));
        source.surfaces.addAll(Set.of(cell(0, 3, 0), cell(0, -5, 0),
                cell(1, 4, 0), cell(1, 99, 0)));
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(0, 0, 0, 1, 0, 0),
                FaceSelection.Face.UP, range(1, 4), source);

        finish(job);

        assertEquals(Set.of(cell(0, 3, 0), cell(1, 4, 0)), job.cells());
        assertEquals(List.of(cell(0, 3, 0), cell(1, 4, 0)), source.sampled);
        assertEquals(List.of(range(1, 4), range(1, 4)), source.searches);
    }

    @Test
    void optimizedRangesOutsideTheSearchDoNotQueryOutOfBoundsCells() {
        RecordingSource source = new RecordingSource();
        source.ranges.put(cell(0, 0, 0), range(-20, -10));
        source.ranges.put(cell(1, 0, 0), range(10, 20));
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(0, 0, 0, 1, 0, 0),
                FaceSelection.Face.DOWN, range(-2, 2), source);

        finish(job);

        assertEquals(2, source.rangeCalls);
        assertEquals(0, source.sampleCalls);
        assertTrue(job.cells().isEmpty());
    }

    @Test
    void eachStepMakesAtMostOneSourceCallAndFinishedJobsMakeNone() {
        RecordingSource source = new RecordingSource();
        source.surfaces.add(cell(1, 1, 0));
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(0, 0, 0, 1, 0, 0),
                FaceSelection.Face.UP, range(0, 2), source);
        assertEquals(0, source.calls());
        assertFalse(job.done());

        for (int step = 0; !job.done() && step < 20; step++) {
            int calls = source.calls();
            job.step();
            assertEquals(1, source.calls() - calls);
        }
        assertTrue(job.done());
        int calls = source.calls();
        job.step();
        job.step();
        assertEquals(calls, source.calls());
    }

    @Test
    void unavailableColumnDiscardsEarlierResultsAndStopsFurtherWork() {
        RecordingSource source = new RecordingSource();
        source.surfaces.add(cell(0, 1, 0));
        source.unavailable.add(cell(1, 1, 0));
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(0, 0, 0, 2, 0, 0),
                FaceSelection.Face.UP, range(0, 1), source);
        Set<SelectionState.Cell> result = job.cells();
        job.step();
        job.step();
        assertEquals(Set.of(cell(0, 1, 0)), result);
        job.step();
        job.step();

        assertTrue(job.done());
        assertTrue(job.failed());
        assertTrue(result.isEmpty());
        assertEquals(2, source.rangeCalls);
        assertEquals(2, source.sampleCalls);
        int calls = source.calls();
        job.step();
        assertEquals(calls, source.calls());
    }

    @Test
    void resultsAreReadOnlyDuringAndAfterTheJob() {
        RecordingSource source = new RecordingSource();
        source.surfaces.add(cell(0, 1, 0));
        SurfaceSelectionJob job = new SurfaceSelectionJob(box(0, 0, 0, 0, 0, 0),
                FaceSelection.Face.UP, range(1, 1), source);

        assertThrows(UnsupportedOperationException.class, () -> job.cells().add(cell(1, 1, 1)));
        finish(job);
        assertThrows(UnsupportedOperationException.class, () -> job.cells().clear());
        assertEquals(Set.of(cell(0, 1, 0)), job.cells());
    }

    @Test
    void scanAndFootprintIterationDoNotWrapAtIntegerEndpoints() {
        for (FaceSelection.Face face : new FaceSelection.Face[] {
                FaceSelection.Face.UP, FaceSelection.Face.DOWN}) {
            for (SurfaceSelectionJob.NormalRange search : new SurfaceSelectionJob.NormalRange[] {
                    range(Integer.MIN_VALUE, Integer.MIN_VALUE + 1),
                    range(Integer.MAX_VALUE - 1, Integer.MAX_VALUE)}) {
                RecordingSource source = new RecordingSource();
                SurfaceSelectionJob job = new SurfaceSelectionJob(box(Integer.MAX_VALUE - 1, 0,
                        Integer.MIN_VALUE, Integer.MAX_VALUE, 0, Integer.MIN_VALUE), face, search, source);

                finish(job);

                assertEquals(2, source.rangeCalls);
                assertEquals(4, source.sampleCalls);
                assertTrue(source.sampled.stream().allMatch(cell -> cell.y() >= search.min()
                        && cell.y() <= search.max()));
                assertTrue(job.cells().isEmpty());
            }
        }
    }

    @Test
    void fullIntegerSearchSpanCanStopAtEitherOutermostEndpoint() {
        SelectionState.Cell anchor = cell(0, 0, 0);
        for (FaceSelection.Face face : FaceSelection.Face.values()) {
            RecordingSource source = new RecordingSource();
            int normal = face.sign() > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
            SelectionState.Cell surface = withNormal(anchor, face.axis(), normal);
            source.surfaces.add(surface);
            SurfaceSelectionJob job = new SurfaceSelectionJob(new SelectionState.Cuboid(anchor, anchor),
                    face, range(Integer.MIN_VALUE, Integer.MAX_VALUE), source);

            finish(job);

            assertEquals(Set.of(surface), job.cells(), face.toString());
            assertEquals(1, source.sampleCalls);
        }
    }

    @Test
    void exactSelectionLimitRemainsOneSurfacePerColumn() {
        RecordingSource source = new RecordingSource();
        SelectionState.Cuboid footprint = box(-64, 5, -32, 63, 5, 31);
        footprint.cells().forEach(source.surfaces::add);
        SurfaceSelectionJob job = new SurfaceSelectionJob(footprint,
                FaceSelection.Face.UP, range(5, 5), source);

        finish(job);

        assertEquals(SelectionState.MAX_BLOCKS, job.cells().size());
        assertEquals(SelectionState.MAX_BLOCKS, source.rangeCalls);
        assertEquals(SelectionState.MAX_BLOCKS, source.sampleCalls);
    }

    @Test
    void invalidFootprintDepthAndOversizedAreasAreRejectedBeforeSourceCalls() {
        RecordingSource source = new RecordingSource();
        for (FaceSelection.Face face : FaceSelection.Face.values()) {
            assertThrows(IllegalArgumentException.class, () -> new SurfaceSelectionJob(
                    box(0, 0, 0, 1, 1, 1), face, range(0, 10), source));
        }
        assertThrows(IllegalArgumentException.class, () -> new SurfaceSelectionJob(
                box(0, 0, 0, 8192, 0, 0), FaceSelection.Face.UP, range(0, 10), source));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceSelectionJob(
                box(Integer.MIN_VALUE, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, Integer.MAX_VALUE),
                FaceSelection.Face.UP, range(0, 10), source));
        assertEquals(0, source.calls());
    }

    @Test
    void constructorRejectsMissingInputsAndReversedSearchRanges() {
        SelectionState.Cuboid footprint = box(0, 0, 0, 0, 0, 0);
        RecordingSource source = new RecordingSource();
        assertThrows(NullPointerException.class,
                () -> new SurfaceSelectionJob(null, FaceSelection.Face.UP, range(0, 1), source));
        assertThrows(NullPointerException.class,
                () -> new SurfaceSelectionJob(footprint, null, range(0, 1), source));
        assertThrows(NullPointerException.class,
                () -> new SurfaceSelectionJob(footprint, FaceSelection.Face.UP, null, source));
        assertThrows(NullPointerException.class,
                () -> new SurfaceSelectionJob(footprint, FaceSelection.Face.UP, range(0, 1), null));
        assertThrows(IllegalArgumentException.class, () -> range(5, -5));
    }

    private static void finish(SurfaceSelectionJob job) {
        int steps = 0;
        while (!job.done() && steps < 100_000) {
            job.step();
            steps++;
        }
        assertTrue(job.done(), "Job failed to finish within its expected work budget");
    }

    private static SelectionState.Cell cell(int x, int y, int z) {
        return new SelectionState.Cell(x, y, z);
    }

    private static SelectionState.Cuboid box(int firstX, int firstY, int firstZ,
            int secondX, int secondY, int secondZ) {
        return new SelectionState.Cuboid(cell(firstX, firstY, firstZ), cell(secondX, secondY, secondZ));
    }

    private static SurfaceSelectionJob.NormalRange range(int minimum, int maximum) {
        return new SurfaceSelectionJob.NormalRange(minimum, maximum);
    }

    private static SelectionState.Cell withNormal(SelectionState.Cell cell, int axis, int normal) {
        return switch (axis) {
            case 0 -> cell(normal, cell.y(), cell.z());
            case 1 -> cell(cell.x(), normal, cell.z());
            default -> cell(cell.x(), cell.y(), normal);
        };
    }

    private static final class RecordingSource implements SurfaceSelectionJob.Source {
        private final Set<SelectionState.Cell> surfaces = new HashSet<>();
        private final Set<SelectionState.Cell> unavailable = new HashSet<>();
        private final Map<SelectionState.Cell, SurfaceSelectionJob.NormalRange> ranges = new HashMap<>();
        private final List<SelectionState.Cell> sampled = new ArrayList<>();
        private final List<SurfaceSelectionJob.NormalRange> searches = new ArrayList<>();
        private int rangeCalls;
        private int sampleCalls;

        @Override
        public SurfaceSelectionJob.NormalRange range(SelectionState.Cell column,
                FaceSelection.Face face, SurfaceSelectionJob.NormalRange search) {
            rangeCalls++;
            searches.add(search);
            return ranges.containsKey(column) ? ranges.get(column) : search;
        }

        @Override
        public SurfaceSelectionJob.Sample sample(SelectionState.Cell cell) {
            sampleCalls++;
            sampled.add(cell);
            if (unavailable.contains(cell)) {
                return SurfaceSelectionJob.Sample.UNAVAILABLE;
            }
            return surfaces.contains(cell) ? SurfaceSelectionJob.Sample.SURFACE : SurfaceSelectionJob.Sample.EMPTY;
        }

        private int calls() {
            return rangeCalls + sampleCalls;
        }
    }
}
