package dev.colony.camera.client.camera;

/** Measures render-frame time without accumulating a jump after a stall or focus change. */
public final class CameraFrameClock {
    private static final long MAX_FRAME_NANOS = 50_000_000;
    private boolean initialized;
    private long previousNanos;

    public double step(long nowNanos) {
        if (!initialized) {
            initialized = true;
            previousNanos = nowNanos;
            return 0;
        }
        long elapsed = nowNanos - previousNanos;
        previousNanos = nowNanos;
        return Math.max(0, Math.min(MAX_FRAME_NANOS, elapsed)) / 1_000_000_000.0;
    }

    public void reset() {
        initialized = false;
    }
}
