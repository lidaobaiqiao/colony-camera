package dev.colony.camera.client.screen;
import javax.annotation.Nonnull;

/** One geometry for drawing, hit tests and scrolling at every GUI scale. */
public final class BuildPanelLayout {
    @Nonnull public final StrategyHudLayout.Rect panel, search, grid, detail, close, available, favorite, choose, refresh, draft;
    public final int columns, rows, cellWidth, cellHeight = 38;
    public BuildPanelLayout(int width, int height, int draftBottom) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("viewport");
        int margin = Math.min(8, Math.min(width, height) / 8);
        int w = Math.min(760, width - margin * 2), h = Math.min(430, height - margin * 2);
        panel = new StrategyHudLayout.Rect((width - w) / 2, (height - h) / 2, w, h);
        int inset = Math.min(8, Math.min(w, h) / 8), inside = Math.max(0, w - 2 * inset);
        close = clipped(w - 24, 3, 20, 18);
        search = clipped(inset, 27, Math.max(0, inside - 88), 18);
        refresh = clipped(w - inset - 82, 27, 38, 18); available = clipped(w - inset - 40, 27, 40, 18);
        int details = w >= 460 ? 180 : 0, gridWidth = Math.max(0, inside - (details == 0 ? 0 : details + 6));
        int footer = details == 0 ? 47 : 10;
        grid = clipped(inset, 71, gridWidth, Math.max(0, h - 71 - footer));
        detail = details == 0 ? clipped(inset, h - 44, inside, 42) : clipped(w - inset - details, 71, details, Math.max(0, h - 81));
        choose = details == 0 ? clipped(w - inset - 102, h - 23, 102, 20) : clipped(w - inset - details + 6, h - 35, details - 12, 22);
        favorite = details == 0 ? clipped(w - inset - 126, h - 23, 20, 20) : clipped(w - inset - 28, 77, 20, 20);
        columns = Math.max(1, grid.width() / 74); cellWidth = grid.width() / columns; rows = grid.height() / cellHeight;
        int dw = Math.min(300, width - margin * 2), dh = Math.min(100, Math.max(0, draftBottom - margin));
        draft = new StrategyHudLayout.Rect(margin, Math.max(margin, draftBottom - dh), dw, dh);
    }
    @Nonnull private StrategyHudLayout.Rect clipped(int x, int y, int width, int height) {
        int left = Math.min(panel.width(), Math.max(0, x)), top = Math.min(panel.height(), Math.max(0, y));
        return new StrategyHudLayout.Rect(panel.x() + left, panel.y() + top, Math.min(Math.max(0, width), panel.width() - left), Math.min(Math.max(0, height), panel.height() - top));
    }
    @Nonnull public StrategyHudLayout.Rect category(int index, int count) {
        int inset = Math.min(8, Math.min(panel.width(), panel.height()) / 8), space = Math.max(0, panel.width() - 2 * inset);
        return clipped(inset + space * index / count, 50, space * (index + 1) / count - space * index / count, 18);
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
