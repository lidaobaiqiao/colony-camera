package dev.colony.camera.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

/** Small shared drawing operations; callers own layout, focus and server intents. */
@ParametersAreNonnullByDefault
public final class UiDraw {
    private UiDraw() {}
    public static void panel(GuiGraphics g, StrategyHudLayout.Rect r) {
        if (r.width() <= 0 || r.height() <= 0) return;
        g.fill(r.x(), r.y(), r.right(), r.bottom(), UiTheme.BORDER);
        if (r.width() > 2 && r.height() > 2) g.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, UiTheme.SURFACE);
    }
    public static void fitted(GuiGraphics g, Font font, Component label, int x, int y, int width, int color) {
        if (width <= 0) return;
        String value = Objects.requireNonNull(label.getString());
        if (font.width(value) > width) value = font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
        g.drawString(font, value, x, y, color, false);
    }
    public static void button(GuiGraphics g, Font font, StrategyHudLayout.Rect r, Component label,
                              boolean enabled, boolean selected, boolean hovered) {
        if (r.width() <= 0 || r.height() <= 0) return;
        g.fill(r.x(), r.y(), r.right(), r.bottom(), selected ? UiTheme.SELECTED : enabled && hovered ? UiTheme.RAISED : UiTheme.SURFACE);
        if (selected && r.height() >= 3) g.fill(r.x() + 2, r.bottom() - 1, Math.max(r.x() + 2, r.right() - 2), r.bottom(), UiTheme.ACCENT);
        fitted(g, font, label, r.x() + 4, r.y() + Math.max(0, (r.height() - 9) / 2), r.width() - 8,
                !enabled ? UiTheme.DISABLED : selected ? UiTheme.ACCENT : UiTheme.TEXT);
    }
    public static void progress(GuiGraphics g, int x, int y, int width, double fraction, int color) {
        if (width <= 0) return;
        double value = Double.isFinite(fraction) ? Math.max(0, Math.min(1, fraction)) : 0;
        g.fill(x, y, x + width, y + 2, UiTheme.RAISED);
        g.fill(x, y, x + (int) Math.round(width * value), y + 2, color);
    }
}
