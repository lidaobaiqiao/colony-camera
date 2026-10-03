package dev.colony.camera.client.selection;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Finds one outermost occupied cell per face-aligned column without blocking a frame. */
public final class SurfaceSelectionJob {
    private final FaceSelection.Face face;
    private final NormalRange search;
    private final Source source;
    private final Iterator<SelectionState.Cell> columns;
    private final Set<SelectionState.Cell> selected = new LinkedHashSet<>();
    private final Set<SelectionState.Cell> readOnlyCells = Collections.unmodifiableSet(selected);
    private SelectionState.Cell column;
    private int nextNormal;
    private int lastNormal;
    private boolean scanning;
    private boolean done;
    private boolean failed;

    public SurfaceSelectionJob(SelectionState.Cuboid footprint, FaceSelection.Face face,
            NormalRange search, Source source) {
        Objects.requireNonNull(footprint, "footprint");
        this.face = Objects.requireNonNull(face, "face");
        this.search = Objects.requireNonNull(search, "search");
        this.source = Objects.requireNonNull(source, "source");
        if (coordinate(footprint.first(), face.axis()) != coordinate(footprint.second(), face.axis())) {
            throw new IllegalArgumentException("A surface footprint must have one normal layer");
        }
        if (!footprint.withinLimit(SelectionState.MAX_BLOCKS)) {
            throw new IllegalArgumentException("Surface footprint exceeds the selection limit");
        }
        columns = footprint.cells().iterator();
    }

    /** Performs at most one world-source call, so callers can budget work between frames. */
    public void step() {
        if (done) {
            return;
        }
        if (!scanning) {
            column = columns.next();
            NormalRange occupied = source.range(column, face, search);
            if (occupied == null) {
                finishColumn();
                return;
            }
            int minimum = Math.max(search.min(), occupied.min());
            int maximum = Math.min(search.max(), occupied.max());
            if (minimum > maximum) {
                finishColumn();
                return;
            }
            nextNormal = face.sign() > 0 ? maximum : minimum;
            lastNormal = face.sign() > 0 ? minimum : maximum;
            scanning = true;
            return;
        }

        SelectionState.Cell candidate = withNormal(column, face.axis(), nextNormal);
        Sample sample = Objects.requireNonNull(source.sample(candidate), "sample");
        if (sample == Sample.UNAVAILABLE) {
            // Partial columns cannot be mistaken for a successfully completed operation.
            selected.clear();
            failed = true;
            done = true;
            return;
        }
        if (sample == Sample.SURFACE) {
            selected.add(candidate);
            finishColumn();
        } else if (nextNormal == lastNormal) {
            finishColumn();
        } else {
            // The end check keeps both integer extremes from wrapping during the scan.
            nextNormal -= face.sign();
        }
    }

    public boolean done() {
        return done;
    }

    public boolean failed() {
        return failed;
    }

    /** Results remain provisional until done is true and failed is false. */
    public Set<SelectionState.Cell> cells() {
        return readOnlyCells;
    }

    private void finishColumn() {
        column = null;
        scanning = false;
        done = !columns.hasNext();
    }

    private static int coordinate(SelectionState.Cell cell, int axis) {
        return switch (axis) {
            case 0 -> cell.x();
            case 1 -> cell.y();
            default -> cell.z();
        };
    }

    private static SelectionState.Cell withNormal(SelectionState.Cell cell, int axis, int normal) {
        return switch (axis) {
            case 0 -> new SelectionState.Cell(normal, cell.y(), cell.z());
            case 1 -> new SelectionState.Cell(cell.x(), normal, cell.z());
            default -> new SelectionState.Cell(cell.x(), cell.y(), normal);
        };
    }

    public record NormalRange(int min, int max) {
        public NormalRange {
            if (min > max) {
                throw new IllegalArgumentException("Normal range minimum exceeds maximum");
            }
        }
    }

    public interface Source {
        /** Returns a conservative occupied range; null means the searched column is known empty. */
        NormalRange range(SelectionState.Cell column, FaceSelection.Face face, NormalRange search);

        Sample sample(SelectionState.Cell cell);
    }

    public enum Sample {
        EMPTY, SURFACE, UNAVAILABLE
    }
}
