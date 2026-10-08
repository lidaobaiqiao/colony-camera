package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.StrategyHudLayout.Rect;
import javax.annotation.Nonnull;

/** Recipe-book geometry in GUI pixels, including a useful one-row layout on short screens. */
public final class CraftingPanelLayout {
    @Nonnull public final Rect bounds, close, station, search, filter, categories, grid, previous, next, down, up, queue;
    public final int columns = 5, rows;
    public final boolean usable;
    public final int detailX, detailWidth;
    public CraftingPanelLayout(int width, int height) {
        int margin = Math.min(6, Math.min(width, height) / 8);
        int w = Math.min(344, Math.max(1, width - 2 * margin)), h = Math.min(240, Math.max(1, height - 2 * margin));
        bounds = new Rect((width - w) / 2, (height - h) / 2, w, h);
        close = rect(w - 18, 0, 18, 18); usable = w >= 282 && h >= 168;
        rows = usable ? Math.max(1, Math.min(4, (h - 136) / 25)) : 0;
        detailX = bounds.x() + 151; detailWidth = Math.max(0, w - 159);
        categories = rect(8, 26, 125, 20); station = rect(8, 49, w - 16, 18);
        search = rect(8, 73, 102, 18); filter = rect(113, 73, 20, 18);
        grid = rect(8, 97, 125, rows * 25);
        previous = rect(8, h - 26, 22, 18); next = rect(111, h - 26, 22, 18);
        down = rect(151, h - 26, 20, 18); up = rect(174, h - 26, 20, 18); queue = rect(198, h - 26, w - 206, 18);
    }
    public int pageSize() { return columns * rows; }
    @Nonnull public Rect cell(int index) {
        if (index < 0 || index >= pageSize()) throw new IllegalArgumentException("Recipe outside page");
        return new Rect(grid.x() + index % columns * 25, grid.y() + index / columns * 25, 22, 22);
    }
    public int cellAt(double x, double y) {
        if (!usable || !grid.contains(x, y)) return -1;
        int index = ((int) y - grid.y()) / 25 * columns + ((int) x - grid.x()) / 25;
        return index < pageSize() && cell(index).contains(x, y) ? index : -1;
    }
    @Nonnull private Rect rect(int x, int y, int w, int h) {
        int left = Math.max(0, Math.min(bounds.width(), x)), top = Math.max(0, Math.min(bounds.height(), y));
        return new Rect(bounds.x() + left, bounds.y() + top, Math.min(Math.max(0, w), bounds.width() - left), Math.min(Math.max(0, h), bounds.height() - top));
    }
}
