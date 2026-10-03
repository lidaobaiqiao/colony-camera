package dev.colony.camera.client.selection;

/** Mouse-driven extrusion with a fixed footprint and no camera or scroll state. */
public final class HeightSelection {
    private static final int MAX_OFFSET = SelectionState.MAX_BLOCKS - 1;
    private static final double MIN_PIXELS_PER_BLOCK = 4;
    private boolean active;
    private double pointerY;
    private double exactOffset;
    private int offset;

    public void begin(double pointerY) {
        if (!Double.isFinite(pointerY)) {
            throw new IllegalArgumentException("Pointer coordinate must be finite");
        }
        reset();
        active = true;
        this.pointerY = pointerY;
    }

    /** Only pointer motion changes depth; a new camera scale alone cannot do so. */
    public boolean move(double pointerY, double pixelsPerBlock) {
        if (!active || !Double.isFinite(pointerY)) {
            return false;
        }
        double movement = this.pointerY - pointerY;
        this.pointerY = pointerY;
        if (!Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0) {
            return false;
        }
        double change = movement / Math.max(MIN_PIXELS_PER_BLOCK, pixelsPerBlock);
        exactOffset = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, exactOffset + change));
        int next = (int) Math.copySign(Math.floor(Math.abs(exactOffset) + 0.5), exactOffset);
        boolean changed = next != offset;
        offset = next;
        return changed;
    }

    /** Camera dragging consumes this motion and resumes depth at the current pointer. */
    public void rebase(double pointerY) {
        if (active && Double.isFinite(pointerY)) {
            this.pointerY = pointerY;
        }
    }

    public int offset() {
        return offset;
    }

    public int signedLayers() {
        return offset >= 0 ? offset + 1 : offset - 1;
    }

    public void reset() {
        active = false;
        pointerY = 0;
        exactOffset = 0;
        offset = 0;
    }
}
