package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.StrategyHudLayout.Rect;
import javax.annotation.Nonnull;

/** Recipe-book list/detail navigation at small GUI sizes, with a stable page capacity. */
public final class CraftingPanelLayout {
    @Nonnull public final Rect bounds, close, back, station, search, filter, categories, grid, detail, previous, next, down, up, queue;
    public final int columns, rows, detailX, detailWidth;
    public final boolean usable, compact, showList, showDetail;
    public CraftingPanelLayout(int width, int height) { this(width, height, false); }
    public CraftingPanelLayout(int width, int height, boolean details) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("viewport");
        int margin = Math.min(6, Math.min(width, height) / 8);
        int w = Math.min(480, width - 2 * margin), h = Math.min(width < 520 && !details ? 168 : 286, height - 2 * margin);
        bounds = new Rect((width - w) / 2, (height - h) / 2, w, h);
        compact = width < 520; usable = w >= 282 && h >= 168;
        showList = !compact || !details; showDetail = !compact || details;
        close = rect(w - 18, 0, 18, 18); back = rect(4, 1, 36, 18);
        columns = compact ? Math.max(5, Math.min(10, (w - 16) / 25)) : 5;
        rows = usable ? Math.max(1, Math.min(20 / columns, (h - (compact ? 104 : 128)) / 25)) : 0;
        search = rect(8, compact ? 26 : 49, compact ? w - 166 : 102, 18);
        filter = rect(search.right() - bounds.x() + 3, compact ? 26 : 49, 20, 18);
        categories = rect(compact ? w - 133 : 8, 26, 125, 20);
        station = rect(8, compact ? details ? 26 : 50 : 73, w - 16, 18);
        grid = showList && usable ? rect(8, compact ? 74 : 98, columns * 25, rows * 25) : rect(0, 0, 0, 0);
        detailX = bounds.x() + (compact ? 8 : 159); detailWidth = Math.max(0, w - (compact ? 16 : 167));
        detail = showDetail && usable ? rect(detailX - bounds.x(), compact ? 50 : 98, detailWidth, h - (compact ? 81 : 129)) : rect(0, 0, 0, 0);
        previous = showList ? rect(8, h - 26, 22, 18) : rect(0, 0, 0, 0);
        next = showList ? rect(compact ? w - 30 : 111, h - 26, 22, 18) : rect(0, 0, 0, 0);
        down = showDetail ? rect(compact ? 8 : 159, h - 26, 20, 18) : rect(0, 0, 0, 0);
        up = showDetail ? rect(compact ? 31 : 182, h - 26, 20, 18) : rect(0, 0, 0, 0);
        queue = showDetail ? rect(compact ? 54 : 205, h - 26, w - (compact ? 62 : 213), 18) : rect(0, 0, 0, 0);
    }
    public int pageSize() { return columns * rows; }
    @Nonnull public Rect cell(int index) {
        if (index < 0 || index >= pageSize()) throw new IllegalArgumentException("Recipe outside page");
        return new Rect(grid.x() + index % columns * 25, grid.y() + index / columns * 25, 22, 22);
    }
    public int cellAt(double x, double y) {
        if (!usable || !showList || !grid.contains(x, y)) return -1;
        int index = ((int) y - grid.y()) / 25 * columns + ((int) x - grid.x()) / 25;
        return index < pageSize() && cell(index).contains(x, y) ? index : -1;
    }
    @Nonnull private Rect rect(int x, int y, int w, int h) {
        int left = Math.max(0, Math.min(bounds.width(), x)), top = Math.max(0, Math.min(bounds.height(), y));
        return new Rect(bounds.x() + left, bounds.y() + top, Math.max(0, Math.min(w, bounds.width() - left)), Math.max(0, Math.min(h, bounds.height() - top)));
    }
}
