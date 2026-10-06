package dev.colony.camera.client.input;

/** Keeps a drag classified as a drag even if the pointer returns to its origin. */
public final class PointerGesture {
    private double startX, startY;
    private boolean active, dragged;
    public void begin(double x, double y) { startX = x; startY = y; active = true; dragged = false; }
    public boolean move(double x, double y, double scale) {
        if (active && Double.isFinite(scale) && scale > 0) dragged |= Math.hypot(x - startX, y - startY) * scale >= 4;
        return active && dragged;
    }
    public boolean release(double x, double y, double scale) {
        move(x, y, scale);
        boolean click = active && !dragged;
        cancel();
        return click;
    }
    public void cancel() { active = dragged = false; }
}
