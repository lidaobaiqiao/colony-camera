package dev.colony.camera.client.selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Partitions full selected blocks into boxes without filling unselected gaps. */
public final class SelectionBoxes {
    private static final Comparator<SelectionState.Cell> ORDER = Comparator
            .comparingInt(SelectionState.Cell::y)
            .thenComparingInt(SelectionState.Cell::z)
            .thenComparingInt(SelectionState.Cell::x);

    private SelectionBoxes() {}

    public static List<SelectionState.Cuboid> merge(Set<SelectionState.Cell> fullBlocks) {
        Objects.requireNonNull(fullBlocks, "fullBlocks");
        List<SelectionState.Cell> ordered = new ArrayList<>(fullBlocks);
        ordered.sort(ORDER);
        LinkedHashSet<SelectionState.Cell> remaining = new LinkedHashSet<>(ordered);
        List<SelectionState.Cuboid> boxes = new ArrayList<>();

        while (!remaining.isEmpty()) {
            SelectionState.Cell first = remaining.iterator().next();
            int maxX = first.x();
            int maxY = first.y();
            int maxZ = first.z();
            while (maxX < Integer.MAX_VALUE
                    && remaining.contains(new SelectionState.Cell(maxX + 1, first.y(), first.z()))) {
                maxX++;
            }
            while (maxZ < Integer.MAX_VALUE
                    && containsRow(remaining, first.x(), maxX, first.y(), maxZ + 1)) {
                maxZ++;
            }
            while (maxY < Integer.MAX_VALUE
                    && containsLayer(remaining, first.x(), maxX, maxY + 1, first.z(), maxZ)) {
                maxY++;
            }

            SelectionState.Cuboid box = new SelectionState.Cuboid(first,
                    new SelectionState.Cell(maxX, maxY, maxZ));
            for (SelectionState.Cell cell : box.cells()) {
                remaining.remove(cell);
            }
            boxes.add(box);
        }
        return List.copyOf(boxes);
    }

    private static boolean containsRow(Set<SelectionState.Cell> remaining,
            int minX, int maxX, int y, int z) {
        // Long counters also terminate when an inclusive endpoint is Integer.MAX_VALUE.
        for (long x = minX; x <= maxX; x++) {
            if (!remaining.contains(new SelectionState.Cell((int) x, y, z))) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsLayer(Set<SelectionState.Cell> remaining,
            int minX, int maxX, int y, int minZ, int maxZ) {
        for (long z = minZ; z <= maxZ; z++) {
            if (!containsRow(remaining, minX, maxX, y, (int) z)) {
                return false;
            }
        }
        return true;
    }
}
