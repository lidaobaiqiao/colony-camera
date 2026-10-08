package dev.colony.camera.work;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Stable spatial preferences, weighted by remaining work and worker speed; ownership is never a hard wall. */
@ParametersAreNonnullByDefault
public final class MiningPartition {
    private MiningPartition() {}
    @Nonnull public static int[] divide(List<MiningFrontier.Cell> cells, double[] costs, double[] speeds) {
        if (cells.size() != costs.length || speeds.length == 0) throw new IllegalArgumentException("Invalid mining partition");
        double speedSum = 0, total = 0;
        for (double speed : speeds) {
            if (!Double.isFinite(speed) || speed <= 0) throw new IllegalArgumentException("Invalid mining speed");
            speedSum += speed;
        }
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < costs.length; i++) {
            if (!Double.isFinite(costs[i]) || costs[i] < 0) throw new IllegalArgumentException("Invalid mining cost");
            if (costs[i] > 0) { indices.add(i); total += costs[i]; }
        }
        int[] owners = new int[cells.size()]; Arrays.fill(owners, -1);
        if (indices.isEmpty()) return owners;
        boolean alongX = alongX(cells);
        indices.sort(Comparator.<Integer>comparingInt(index -> alongX ? cells.get(index).x() : cells.get(index).z())
                .thenComparingInt(index -> alongX ? cells.get(index).z() : cells.get(index).x())
                .thenComparing(Comparator.<Integer>comparingInt(index -> cells.get(index).y()).reversed()));
        int worker = 0; double threshold = total * speeds[0] / speedSum, consumed = 0;
        for (int index : indices) {
            double midpoint = consumed + costs[index] / 2;
            while (worker + 1 < speeds.length && midpoint >= threshold) threshold += total * speeds[++worker] / speedSum;
            owners[index] = worker; consumed += costs[index];
        }
        return owners;
    }
    public static boolean alongX(List<MiningFrontier.Cell> cells) {
        if (cells.isEmpty()) return true;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (MiningFrontier.Cell entry : cells) {
            MiningFrontier.Cell cell = Objects.requireNonNull(entry);
            minX = Math.min(minX, cell.x()); maxX = Math.max(maxX, cell.x()); minZ = Math.min(minZ, cell.z()); maxZ = Math.max(maxZ, cell.z());
        }
        return (long) maxX - minX >= (long) maxZ - minZ;
    }
}
