package dev.colony.camera.work;

import javax.annotation.Nonnull;
import java.util.Arrays;

/** One accumulator per accepted target; a server tick can advance a target at most once. */
public final class MiningProgress {
    public static final int COMPLETE = 10_000;
    @Nonnull private final int[] values;
    @Nonnull private final long[] lastTicks;
    public MiningProgress(int size) {
        if (size < 0 || size > 8192) throw new IllegalArgumentException("Invalid mining progress size");
        values = new int[size]; lastTicks = new long[size]; Arrays.fill(lastTicks, Long.MIN_VALUE);
    }
    public int size() { return values.length; }
    public int get(int index) { return values[index]; }
    public void remember(int index, int checkpoint) {
        if (checkpoint < 0 || checkpoint > COMPLETE) throw new IllegalArgumentException("Invalid mining checkpoint");
        values[index] = Math.max(values[index], checkpoint);
    }
    public int advance(int index, long amount, long tick) {
        if (amount < 0) throw new IllegalArgumentException("Negative mining progress");
        if (lastTicks[index] != tick) {
            lastTicks[index] = tick;
            values[index] += (int) Math.min(COMPLETE - values[index], amount);
        }
        return values[index];
    }
    public void clear(int index) { values[index] = 0; lastTicks[index] = Long.MIN_VALUE; }
    @Nonnull public int[] snapshot() {
        int count = 0; for (int value : values) if (value > 0) count++;
        int[] pairs = new int[count * 2]; int offset = 0;
        for (int i = 0; i < values.length; i++) if (values[i] > 0) { pairs[offset++] = i; pairs[offset++] = values[i]; }
        return pairs;
    }
    public void restore(@Nonnull int[] pairs) {
        if (pairs.length % 2 != 0 || pairs.length > values.length * 2) throw new IllegalArgumentException("Invalid mining progress data");
        boolean[] seen = new boolean[values.length];
        for (int i = 0; i < pairs.length; i += 2) {
            int index = pairs[i], value = pairs[i + 1];
            if (index < 0 || index >= values.length || seen[index] || value < 1 || value > COMPLETE) throw new IllegalArgumentException("Invalid mining progress entry");
            seen[index] = true;
        }
        Arrays.fill(values, 0); Arrays.fill(lastTicks, Long.MIN_VALUE);
        for (int i = 0; i < pairs.length; i += 2) values[pairs[i]] = pairs[i + 1];
    }
}
