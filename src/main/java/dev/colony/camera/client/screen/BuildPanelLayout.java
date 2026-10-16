package dev.colony.camera.client.screen;

import java.util.function.IntUnaryOperator;
import javax.annotation.Nonnull;

/** The same clipped rectangles draw and hit-test a movable palette. */
public final class BuildPanelLayout {
    @Nonnull public final StrategyHudLayout.Rect panel, title, search, category, grid, detail, close, newWindow, resize, available, favorite, choose, refresh, draft;
    public final int columns, rows, cellWidth, cellHeight = 26;
    public final boolean horizontalCategories;
    private final int[] categoryWidths = new int[7];

    public BuildPanelLayout(int width, int height, int draftBottom) {
        this(width, height, draftBottom, defaultBounds(width, height, draftBottom), index -> 24);
    }
    @Nonnull public static StrategyHudLayout.Rect defaultBounds(int width, int height, int bottom) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("viewport");
        int margin = Math.min(8, Math.min(width, height) / 8);
        return WindowGeometry.clamp(new StrategyHudLayout.Rect(margin, margin,
                Math.min(248, Math.max(120, (width - 2 * margin) * 2 / 5)),
                Math.min(420, Math.max(0, bottom - margin))), width, Math.min(height, Math.max(1, bottom)));
    }
    public BuildPanelLayout(int width, int height, int bottom, StrategyHudLayout.Rect bounds, IntUnaryOperator categoryWidth) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("viewport");
        panel = WindowGeometry.clamp(bounds, width, Math.min(height, Math.max(1, bottom)));
        int w = panel.width(), h = panel.height();
        int inset = Math.min(6, Math.min(w, h) / 8), inside = Math.max(0, w - 2 * inset);
        close = clipped(w - 23, 3, 20, 18);
        newWindow = clipped(w - 46, 3, 20, 18);
        title = clipped(1, 1, Math.max(0, w - 48), 22);
        resize = clipped(w - 9, h - 9, 9, 9);
        search = clipped(inset, 25, inside, 16);
        category = clipped(inset, 45, Math.max(0, inside - 58), 16);
        available = clipped(w - inset - 54, 45, 28, 16);
        refresh = clipped(w - inset - 22, 45, 22, 16);
        int total = 0;
        for (int i = 0; i < 7; i++) { categoryWidths[i] = Math.max(20, categoryWidth.applyAsInt(i) + 10); total += categoryWidths[i]; }
        horizontalCategories = total <= category.width();
        grid = clipped(inset, 63, inside, Math.max(0, h - 63 - 24));
        detail = clipped(inset, h - 24, Math.max(0, inside - 10), 23);
        favorite = clipped(w - inset - 30, h - 24, 20, 20);
        choose = clipped(0, 0, 0, 0);
        columns = Math.max(1, grid.width() / 26); cellWidth = grid.width() / columns; rows = grid.height() / cellHeight;
        draft = new StrategyHudLayout.Rect(0, 0, 0, 0);
    }
    @Nonnull private StrategyHudLayout.Rect clipped(int x, int y, int width, int height) {
        int left = Math.min(panel.width(), Math.max(0, x)), top = Math.min(panel.height(), Math.max(0, y));
        return new StrategyHudLayout.Rect(panel.x() + left, panel.y() + top, Math.min(Math.max(0, width), panel.width() - left), Math.min(Math.max(0, height), panel.height() - top));
    }
    @Nonnull public StrategyHudLayout.Rect category(int index, int count) {
        if (index < 0 || index >= count || count < 1) throw new IllegalArgumentException("category");
        if (horizontalCategories) {
            int x = category.x(); for (int i = 0; i < index; i++) x += categoryWidths[i];
            return new StrategyHudLayout.Rect(x, category.y(), categoryWidths[index] - 1, category.height());
        }
        int optionHeight = Math.min(18, Math.max(0, panel.bottom() - category.bottom()) / count);
        return new StrategyHudLayout.Rect(search.x(), category.bottom() + index * optionHeight, search.width(), optionHeight);
    }
    @Nonnull public StrategyHudLayout.Rect cell(int visibleIndex) {
        return new StrategyHudLayout.Rect(grid.x() + visibleIndex % columns * cellWidth, grid.y() + visibleIndex / columns * cellHeight, cellWidth, cellHeight);
    }
    public int rowAt(double x, double y, int scrollRow) {
        if (!grid.contains(x, y) || cellWidth == 0 || y >= grid.y() + rows * cellHeight) return -1;
        int column = Math.min(columns - 1, (int) (x - grid.x()) / cellWidth);
        return (scrollRow + (int) (y - grid.y()) / cellHeight) * columns + column;
    }
    public int maxScroll(int count) { return Math.max(0, (count + columns - 1) / columns - Math.max(1, rows)); }
}
