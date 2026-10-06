package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.StrategyHudLayout;

import javax.annotation.Nonnull;
import java.util.Objects;

/** Geometry shared by the embedded and station screens, in scaled GUI pixels. */
public final class WorkPanelLayout {
    public static final int ROW_HEIGHT = 22;
    @Nonnull private final StrategyHudLayout.Rect bounds;
    @Nonnull private final StrategyHudLayout.Rect list;
    @Nonnull private final StrategyHudLayout.Rect detail;
    @Nonnull private final StrategyHudLayout.Rect close;
    private final boolean usable;

    public WorkPanelLayout(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Viewport must be positive");
        int margin = Math.min(8, Math.min(width, height) / 8);
        int panelWidth = Math.min(540, width - margin * 2);
        int panelHeight = Math.min(300, height - margin * 2);
        bounds = new StrategyHudLayout.Rect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
        usable = panelWidth >= 260 && panelHeight >= 156;
        int closeSize = Math.min(18, Math.min(panelWidth, panelHeight));
        close = new StrategyHudLayout.Rect(bounds.right() - closeSize, bounds.y(), closeSize, closeSize);
        int contentTop = usable ? bounds.y() + 70 : bounds.y();
        int contentHeight = usable ? Math.max(0, bounds.height() - 118) : 0;
        int listWidth = usable ? (bounds.width() - 26) * 11 / 20 : 0;
        list = new StrategyHudLayout.Rect(bounds.x() + (usable ? 8 : 0), contentTop, listWidth, contentHeight);
        detail = new StrategyHudLayout.Rect(list.right() + (usable ? 10 : 0), contentTop,
                usable ? Math.max(0, bounds.right() - list.right() - 18) : 0, contentHeight);
    }

    public boolean usable() { return usable; }
    @Nonnull public StrategyHudLayout.Rect bounds() { return bounds; }
    @Nonnull public StrategyHudLayout.Rect list() { return list; }
    @Nonnull public StrategyHudLayout.Rect detail() { return detail; }
    @Nonnull public StrategyHudLayout.Rect close() { return close; }
    public int rows() { return list.height() / ROW_HEIGHT; }

    @Nonnull public StrategyHudLayout.Rect tab(int index) { return divided(index, 3, bounds.y() + 25, 18); }
    @Nonnull public StrategyHudLayout.Rect creation(int index) { return divided(index, 2, bounds.y() + 47, 18); }
    @Nonnull public StrategyHudLayout.Rect action(int index, int count) { return divided(index, count, bounds.bottom() - 39, 21); }
    @Nonnull public StrategyHudLayout.Rect station() { return divided(0, 1, bounds.y() + 47, 18); }
    @Nonnull public StrategyHudLayout.Rect row(int visibleIndex) {
        if (visibleIndex < 0 || visibleIndex >= rows()) throw new IllegalArgumentException("Row outside list");
        return new StrategyHudLayout.Rect(list.x(), list.y() + visibleIndex * ROW_HEIGHT, list.width(), ROW_HEIGHT - 2);
    }

    public int rowAt(double x, double y) {
        if (!list.contains(x, y)) return -1;
        int index = ((int) y - list.y()) / ROW_HEIGHT;
        return index < rows() && row(index).contains(x, y) ? index : -1;
    }

    @Nonnull private StrategyHudLayout.Rect divided(int index, int count, int y, int height) {
        if (count < 1 || index < 0 || index >= count) throw new IllegalArgumentException("Invalid control index");
        if (!usable) return new StrategyHudLayout.Rect(bounds.x(), bounds.y(), 0, 0);
        int available = bounds.width() - 16;
        int start = bounds.x() + 8 + available * index / count;
        int end = bounds.x() + 8 + available * (index + 1) / count;
        return Objects.requireNonNull(new StrategyHudLayout.Rect(start, y, Math.max(0, end - start - 3), height));
    }
}
