package dev.colony.camera.work;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Geometry of the accepted targets; exposure always comes from the actual surrounding world. */
@ParametersAreNonnullByDefault
public final class MiningFrontier {
    public record Cell(int x, int y, int z) {
        @Nonnull public Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
    }
    private static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    @Nonnull private final List<Cell> cells;
    @Nonnull private final Map<Cell, Integer> indices = new HashMap<>();
    @Nonnull private final Map<Cell, List<Integer>> buckets = new HashMap<>();
    @Nonnull private final BitSet pending = new BitSet();
    @Nonnull private final BitSet visible = new BitSet();
    @Nonnull private final int[] masks;

    public MiningFrontier(List<Cell> targets) {
        cells = Objects.requireNonNull(List.copyOf(targets)); masks = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = Objects.requireNonNull(cells.get(i));
            if (indices.put(cell, i) != null) throw new IllegalArgumentException("Duplicate mining target");
            buckets.computeIfAbsent(bucket(cell), ignored -> new ArrayList<>()).add(i);
            pending.set(i);
        }
    }
    @Nonnull public Cell cell(int index) { return Objects.requireNonNull(cells.get(index)); }
    public int size() { return cells.size(); }
    public int exposure(int index) { return Integer.bitCount(masks[index]); }
    public int mask(int index) { return masks[index]; }
    public boolean pending(int index) { return pending.get(index); }
    @Nonnull public BitSet visible() { return Objects.requireNonNull((BitSet) visible.clone()); }
    public void refresh(Predicate<Cell> open) {
        for (int i = pending.nextSetBit(0); i >= 0; i = pending.nextSetBit(i + 1)) refresh(i, open);
    }
    public void refresh(int index, Predicate<Cell> open) {
        int mask = 0;
        if (pending.get(index)) for (int face = 0; face < DIRECTIONS.length; face++) {
            int[] direction = DIRECTIONS[face];
            if (open.test(cell(index).offset(direction[0], direction[1], direction[2]))) mask |= 1 << face;
        }
        masks[index] = mask; visible.set(index, mask != 0);
    }
    public void changed(Cell position, Predicate<Cell> open) {
        Integer own = indices.get(position); if (own != null) refresh(own, open);
        for (int[] direction : DIRECTIONS) {
            Integer neighbor = indices.get(position.offset(direction[0], direction[1], direction[2]));
            if (neighbor != null) refresh(neighbor, open);
        }
    }
    public void finish(int index, Predicate<Cell> open) {
        pending.clear(index); masks[index] = 0; visible.clear(index); changed(cell(index), open);
    }
    @Nonnull public List<Integer> nearby(Cell center, int radius) {
        if (radius < 0 || radius > 64) throw new IllegalArgumentException("Invalid local mining radius");
        var result = new ArrayList<Integer>();
        for (int x = (center.x() - radius) >> 3; x <= (center.x() + radius) >> 3; x++) {
            for (int y = (center.y() - radius) >> 3; y <= (center.y() + radius) >> 3; y++) {
                for (int z = (center.z() - radius) >> 3; z <= (center.z() + radius) >> 3; z++) {
                    List<Integer> entries = buckets.get(new Cell(x, y, z)); if (entries == null) continue;
                    for (int index : entries) {
                        Cell cell = cell(index);
                        if (pending.get(index) && Math.abs((long) cell.x() - center.x()) <= radius
                                && Math.abs((long) cell.y() - center.y()) <= radius && Math.abs((long) cell.z() - center.z()) <= radius) result.add(index);
                    }
                }
            }
        }
        return result;
    }
    @Nonnull private static Cell bucket(Cell cell) { return new Cell(cell.x() >> 3, cell.y() >> 3, cell.z() >> 3); }
}
