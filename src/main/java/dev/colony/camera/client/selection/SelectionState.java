package dev.colony.camera.client.selection;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Owns selection membership independently of Minecraft rendering and input. */
public final class SelectionState {
    public static final int MAX_BLOCKS = 8192;

    private final LinkedHashSet<Cell> selected = new LinkedHashSet<>();
    private final Set<Cell> readOnlyCells = Collections.unmodifiableSet(selected);
    private boolean additive;

    public void toggleAdditive() {
        additive = !additive;
    }

    public boolean additive() {
        return additive;
    }

    public void reset() {
        clear();
        additive = false;
    }

    public void clear() {
        selected.clear();
    }

    /** Rejects an oversized result before altering the existing selection. */
    public ApplyResult apply(Collection<Cell> cells) {
        return apply(cells, additive);
    }

    /** Uses the mode captured when an operation began, even if the UI mode changed. */
    public ApplyResult apply(Collection<Cell> cells, boolean append) {
        Objects.requireNonNull(cells, "cells");
        LinkedHashSet<Cell> next = append ? new LinkedHashSet<>(selected) : new LinkedHashSet<>();
        for (Cell cell : cells) {
            next.add(Objects.requireNonNull(cell, "cell"));
            if (next.size() > MAX_BLOCKS) {
                return new ApplyResult(false, false);
            }
        }

        boolean changed = !selected.equals(next);
        if (changed) {
            selected.clear();
            selected.addAll(next);
        }
        return new ApplyResult(true, changed);
    }

    public Set<Cell> cells() {
        return readOnlyCells;
    }

    public int size() {
        return selected.size();
    }

    public boolean contains(Cell cell) {
        return selected.contains(cell);
    }

    public boolean removeIf(Predicate<Cell> predicate) {
        return selected.removeIf(predicate);
    }

    public record Cell(int x, int y, int z) {}

    public record ApplyResult(boolean accepted, boolean changed) {}

    public record Cuboid(Cell first, Cell second) {
        public Cuboid {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
        }

        public int minX() {
            return Math.min(first.x(), second.x());
        }

        public int minY() {
            return Math.min(first.y(), second.y());
        }

        public int minZ() {
            return Math.min(first.z(), second.z());
        }

        public int maxX() {
            return Math.max(first.x(), second.x());
        }

        public int maxY() {
            return Math.max(first.y(), second.y());
        }

        public int maxZ() {
            return Math.max(first.z(), second.z());
        }

        /** Includes both corners; volumes beyond long's range saturate. */
        public long volume() {
            return saturatedMultiply(saturatedMultiply(spanX(), spanY()), spanZ());
        }

        public boolean withinLimit(long limit) {
            if (limit < 1 || spanX() > limit / spanY()) {
                return false;
            }
            return spanX() * spanY() <= limit / spanZ();
        }

        /** Callers must check withinLimit before iterating a large region. */
        public Iterable<Cell> cells() {
            return () -> new Iterator<>() {
                private int x = minX();
                private int y = minY();
                private int z = minZ();
                private boolean available = true;

                @Override
                public boolean hasNext() {
                    return available;
                }

                @Override
                public Cell next() {
                    if (!available) {
                        throw new NoSuchElementException();
                    }
                    Cell result = new Cell(x, y, z);
                    if (x < maxX()) {
                        x++;
                    } else {
                        x = minX();
                        if (y < maxY()) {
                            y++;
                        } else {
                            y = minY();
                            if (z < maxZ()) {
                                z++;
                            } else {
                                available = false;
                            }
                        }
                    }
                    return result;
                }
            };
        }

        private long spanX() {
            return (long) maxX() - minX() + 1;
        }

        private long spanY() {
            return (long) maxY() - minY() + 1;
        }

        private long spanZ() {
            return (long) maxZ() - minZ() + 1;
        }

        private static long saturatedMultiply(long first, long second) {
            return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
        }
    }
}
