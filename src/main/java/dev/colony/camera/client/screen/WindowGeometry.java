package dev.colony.camera.client.screen;

import javax.annotation.Nonnull;

/** Clamps client window preferences to the viewport after resize or GUI-scale changes. */
public final class WindowGeometry {
    private WindowGeometry() {}
    @Nonnull public static StrategyHudLayout.Rect clamp(StrategyHudLayout.Rect rectangle, int width, int bottom) {
        int spaceW = Math.max(1, width), spaceH = Math.max(1, bottom);
        int w = Math.min(spaceW, Math.max(Math.min(120, spaceW), rectangle.width()));
        int h = Math.min(spaceH, Math.max(Math.min(110, spaceH), rectangle.height()));
        return new StrategyHudLayout.Rect(Math.max(0, Math.min(spaceW - w, rectangle.x())), Math.max(0, Math.min(spaceH - h, rectangle.y())), w, h);
    }
    @Nonnull public static String encode(StrategyHudLayout.Rect rectangle, int width, int bottom) {
        var r = clamp(rectangle, width, bottom);
        return normalized(r.x(), width) + "," + normalized(r.y(), bottom) + "," + normalized(r.width(), width) + "," + normalized(r.height(), bottom);
    }
    private static int normalized(int value, int size) { return (int) Math.round(value * 10000.0 / Math.max(1, size)); }
    @Nonnull public static StrategyHudLayout.Rect decode(String value, int width, int bottom) {
        String[] parts = value.split(",", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Window rectangle");
        int[] values = new int[4];
        for (int i = 0; i < values.length; i++) {
            int n = Integer.parseInt(parts[i]); if (n < 0 || n > 10000) throw new IllegalArgumentException("Window coordinate");
            values[i] = (int) Math.round(n * (double) (i % 2 == 0 ? width : bottom) / 10000);
        }
        return clamp(new StrategyHudLayout.Rect(values[0], values[1], values[2], values[3]), width, bottom);
    }
}
