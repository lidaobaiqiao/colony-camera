package dev.colony.camera.client.screen;
import javax.annotation.Nonnull;

/** One geometry for drawing, hit tests and scrolling at every GUI scale. */
public final class BuildPanelLayout {
    @Nonnull public final StrategyHudLayout.Rect panel, search, category, grid, detail, close, available, favorite, choose, refresh, draft;
    public final int columns, rows, cellWidth, cellHeight = 26;
    public BuildPanelLayout(int width, int height, int draftBottom) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("viewport");
        int margin = Math.min(8, Math.min(width, height) / 8);
        int availableWidth = width - margin * 2;
        int w = Math.min(248, availableWidth * 2 / 5);
        int h = Math.min(420, Math.max(0, height - margin * 2 - 32));
        panel = new StrategyHudLayout.Rect(margin, margin, w, h);
        int inset = Math.min(6, Math.min(w, h) / 8), inside = Math.max(0, w - 2 * inset);
        close = clipped(w - 24, 3, 20, 18);
        search = clipped(inset, 25, inside, 18);
        category = clipped(inset, 47, Math.max(0, inside - 58), 18);
        available = clipped(w - inset - 54, 47, 28, 18);
        refresh = clipped(w - inset - 22, 47, 22, 18);
        grid = clipped(inset, 67, inside, Math.max(0, h - 67 - 36));
        detail = clipped(inset, h - 34, inside, 16);
        favorite = clipped(w - inset - 20, h - 34, 20, 16);
        choose = clipped(inset, h - 19, inside, 17);
        columns = Math.max(1, grid.width() / 26); cellWidth = grid.width() / columns; rows = grid.height() / cellHeight;
        int bottom = Math.min(height - margin, Math.max(margin, draftBottom));
        int dw = Math.min(248, availableWidth), dh = Math.min(26, Math.max(0, bottom - margin));
        draft = new StrategyHudLayout.Rect(margin, bottom - dh, dw, dh);
    }
    @Nonnull private StrategyHudLayout.Rect clipped(int x, int y, int width, int height) {
        int left = Math.min(panel.width(), Math.max(0, x)), top = Math.min(panel.height(), Math.max(0, y));
        return new StrategyHudLayout.Rect(panel.x() + left, panel.y() + top, Math.min(Math.max(0, width), panel.width() - left), Math.min(Math.max(0, height), panel.height() - top));
    }
    @Nonnull public StrategyHudLayout.Rect category(int index, int count) {
        int optionHeight = Math.min(18, Math.max(0, panel.bottom() - category.bottom()) / count);
        return new StrategyHudLayout.Rect(search.x(), category.bottom() + index * optionHeight, search.width(), optionHeight);
    }
    @Nonnull public StrategyHudLayout.Rect draftControl(int index) {
        int buttonWidth = Math.min(22, draft.width() / 5);
        int inset = Math.min(2, draft.height() / 2);
        return new StrategyHudLayout.Rect(draft.right() - (5 - index) * buttonWidth, draft.y() + inset,
                buttonWidth, draft.height() - 2 * inset);
    }
    @Nonnull public StrategyHudLayout.Rect cell(int visibleIndex) {
        return new StrategyHudLayout.Rect(grid.x() + visibleIndex % columns * cellWidth, grid.y() + visibleIndex / columns * cellHeight, cellWidth, cellHeight);
    }
    public int rowAt(double x, double y, int scrollRow) {
        if (!grid.contains(x, y) || cellWidth == 0 || y >= grid.y() + rows * cellHeight) return -1;
        int column = Math.min(columns - 1, (int) (x - grid.x()) / cellWidth);
        return (scrollRow + (int) (y - grid.y()) / cellHeight) * columns + column;
    }
    public int maxScroll(int count) { return Math.max(0, (count + columns - 1) / columns - rows); }
}
