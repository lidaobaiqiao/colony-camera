package dev.colony.camera.client.selection;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded 26-neighbor flood fill; each step makes at most one world query. */
public final class SimilarSelectionJob {
    public enum Sample { MATCH, OTHER, UNAVAILABLE }
    public enum Failure { NONE, UNAVAILABLE, CHANGED }
    public interface Source { Sample sample(SelectionState.Cell cell); }

    private final Source source;
    private final int limit;
    private final ArrayDeque<SelectionState.Cell> pending = new ArrayDeque<>();
    private final Set<SelectionState.Cell> discovered = new HashSet<>();
    private final LinkedHashSet<SelectionState.Cell> selected = new LinkedHashSet<>();
    private final Set<SelectionState.Cell> readOnlySelected = Collections.unmodifiableSet(selected);
    private final Map<SelectionState.Cell, Sample> samples = new LinkedHashMap<>();
    private Iterator<Map.Entry<SelectionState.Cell, Sample>> validation;
    private boolean done;
    private boolean limitReached;
    private Failure failure = Failure.NONE;

    public SimilarSelectionJob(SelectionState.Cell seed, int limit, Source source) {
        if (limit < 1 || limit > SelectionState.MAX_BLOCKS) throw new IllegalArgumentException("Invalid similar selection limit");
        this.source = Objects.requireNonNull(source, "source");
        this.limit = limit;
        pending.add(Objects.requireNonNull(seed, "seed"));
        discovered.add(seed);
    }

    public void step() {
        if (done) return;
        if (validation != null) {
            if (!validation.hasNext()) { done = true; return; }
            var entry = validation.next();
            Sample current = Objects.requireNonNull(source.sample(entry.getKey()));
            if (current == Sample.UNAVAILABLE) fail(Failure.UNAVAILABLE);
            else if (current != entry.getValue()) fail(Failure.CHANGED);
            else if (!validation.hasNext()) done = true;
            return;
        }
        SelectionState.Cell cell = pending.removeFirst();
        Sample sample = Objects.requireNonNull(source.sample(cell));
        samples.put(cell, sample);
        if (sample == Sample.UNAVAILABLE) { fail(Failure.UNAVAILABLE); return; }
        if (sample == Sample.MATCH) {
            selected.add(cell);
            if (selected.size() == limit) {
                limitReached = true;
                pending.clear();
            } else {
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    long x = (long) cell.x() + dx, y = (long) cell.y() + dy, z = (long) cell.z() + dz;
                    if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                            || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                    var neighbor = new SelectionState.Cell((int) x, (int) y, (int) z);
                    if (discovered.add(neighbor)) pending.addLast(neighbor);
                }
            }
        }
        if (pending.isEmpty()) validation = samples.entrySet().iterator();
    }

    private void fail(Failure reason) {
        failure = reason;
        selected.clear();
        pending.clear();
        done = true;
    }

    public boolean done() { return done; }
    public Failure failure() { return failure; }
    public boolean limitReached() { return limitReached; }
    public Set<SelectionState.Cell> cells() { return readOnlySelected; }
}
