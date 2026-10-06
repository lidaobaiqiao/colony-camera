package dev.colony.camera.client.selection;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SimilarSelectionJobTest {
    private static final SelectionState.Cell SEED = new SelectionState.Cell(0, 0, 0);

    @Test void reachesAllTwentySixNeighborDirections() {
        Set<SelectionState.Cell> component = new HashSet<>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            component.add(new SelectionState.Cell(x, y, z));
        }
        var job = job(component, 64);
        finish(job);
        assertEquals(component, job.cells());
        assertFalse(job.limitReached());
    }

    @Test void cornerOnlyChainConnectsWhileAnAirGapDoesNot() {
        Set<SelectionState.Cell> component = Set.of(SEED, new SelectionState.Cell(1, 1, 1), new SelectionState.Cell(2, 2, 2));
        var blocks = new HashSet<>(component);
        blocks.add(new SelectionState.Cell(4, 4, 4));
        var job = job(blocks, 64);
        finish(job);
        assertEquals(component, job.cells());
    }

    @Test void nonmatchingBlocksDoNotBridgeSeparatedGroups() {
        var job = job(Set.of(SEED, new SelectionState.Cell(2, 0, 0)), 64);
        finish(job);
        assertEquals(Set.of(SEED), job.cells());
    }

    @Test void stopsAnInfiniteComponentAtTheConfiguredMaximumAndKeepsSeed() {
        var job = new SimilarSelectionJob(SEED, 64, cell -> SimilarSelectionJob.Sample.MATCH);
        finish(job);
        assertEquals(64, job.cells().size());
        assertTrue(job.cells().contains(SEED));
        assertTrue(job.limitReached());
        assertTrue(job.cells().stream().allMatch(c -> Math.max(Math.abs(c.x()), Math.max(Math.abs(c.y()), Math.abs(c.z()))) <= 2));
    }

    @Test void limitOneQueriesOnlyTheSeedAndItsRevalidation() {
        AtomicInteger queries = new AtomicInteger();
        var job = new SimilarSelectionJob(SEED, 1, cell -> { queries.incrementAndGet(); return SimilarSelectionJob.Sample.MATCH; });
        finish(job);
        assertEquals(Set.of(SEED), job.cells());
        assertEquals(2, queries.get());
        job.step();
        assertEquals(2, queries.get());
    }

    @Test void eachStepPerformsAtMostOneQuery() {
        AtomicInteger queries = new AtomicInteger();
        var job = new SimilarSelectionJob(SEED, 17, cell -> { queries.incrementAndGet(); return SimilarSelectionJob.Sample.MATCH; });
        while (!job.done()) {
            int previous = queries.get(); job.step();
            assertTrue(queries.get() - previous <= 1);
        }
        assertEquals(17, job.cells().size());
    }

    @Test void unavailableNeighborRejectsTheWholeResult() {
        var job = new SimilarSelectionJob(SEED, 64, cell -> cell.equals(SEED)
                ? SimilarSelectionJob.Sample.MATCH : SimilarSelectionJob.Sample.UNAVAILABLE);
        finish(job);
        assertEquals(SimilarSelectionJob.Failure.UNAVAILABLE, job.failure());
        assertTrue(job.cells().isEmpty());
    }

    @Test void aChunkBecomingUnavailableBeforeCommitRejectsTheResult() {
        AtomicInteger queries = new AtomicInteger();
        var job = new SimilarSelectionJob(SEED, 1, cell -> queries.incrementAndGet() == 1
                ? SimilarSelectionJob.Sample.MATCH : SimilarSelectionJob.Sample.UNAVAILABLE);
        finish(job);
        assertEquals(SimilarSelectionJob.Failure.UNAVAILABLE, job.failure());
        assertTrue(job.cells().isEmpty());
    }

    @Test void changingMembershipBeforeCommitRejectsTheResult() {
        AtomicInteger queries = new AtomicInteger();
        var job = new SimilarSelectionJob(SEED, 1, cell -> queries.incrementAndGet() == 1
                ? SimilarSelectionJob.Sample.MATCH : SimilarSelectionJob.Sample.OTHER);
        finish(job);
        assertEquals(SimilarSelectionJob.Failure.CHANGED, job.failure());
    }

    @Test void coordinateBoundariesDoNotWrapToTheOppositeEdge() {
        var seed = new SelectionState.Cell(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        var opposite = new SelectionState.Cell(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE);
        var job = new SimilarSelectionJob(seed, 64, cell -> cell.equals(seed) || cell.equals(opposite)
                ? SimilarSelectionJob.Sample.MATCH : SimilarSelectionJob.Sample.OTHER);
        finish(job);
        assertEquals(Set.of(seed), job.cells());
    }

    @Test void additiveClicksMergeWithoutDuplicatingMembership() {
        var job = job(Set.of(SEED, new SelectionState.Cell(1, 0, 0)), 64); finish(job);
        SelectionState selection = new SelectionState(); selection.apply(Set.of(SEED, new SelectionState.Cell(10, 0, 0)));
        assertTrue(selection.apply(job.cells(), true).accepted());
        assertEquals(Set.of(SEED, new SelectionState.Cell(1, 0, 0), new SelectionState.Cell(10, 0, 0)), selection.cells());
    }

    @Test void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> job(Set.of(SEED), 0));
        assertThrows(IllegalArgumentException.class, () -> job(Set.of(SEED), SelectionState.MAX_BLOCKS + 1));
    }

    private static SimilarSelectionJob job(Set<SelectionState.Cell> cells, int limit) {
        return new SimilarSelectionJob(SEED, limit, cell -> cells.contains(cell)
                ? SimilarSelectionJob.Sample.MATCH : SimilarSelectionJob.Sample.OTHER);
    }

    private static void finish(SimilarSelectionJob job) {
        for (int step = 0; step < 100_000 && !job.done(); step++) job.step();
        assertTrue(job.done(), "Selection did not finish within its bounded query count");
    }
}
