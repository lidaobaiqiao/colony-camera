package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.StrategyHudLayout;
import javax.annotation.Nonnull;

/** Shared visible geometry: compact views show a list or details, never two unreadable columns. */
public final class WorkPanelLayout {
    public static final int ROW_HEIGHT = 26;
    @Nonnull private final StrategyHudLayout.Rect bounds, list, detail, close, back;
    private final boolean usable, compact, detailOpen;
    public WorkPanelLayout(int width, int height) { this(width, height, 250, false); }
    public WorkPanelLayout(int width, int height, int desiredHeight) { this(width, height, desiredHeight, false); }
    public WorkPanelLayout(int width, int height, int desiredHeight, boolean detailOpen) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("viewport");
        int margin = Math.min(8, Math.min(width, height) / 8);
        int w = Math.min(500, width - 2 * margin), h = Math.min(desiredHeight, height - 2 * margin);
        bounds = new StrategyHudLayout.Rect((width - w) / 2, (height - h) / 2, w, h);
        usable = w >= 260 && h >= 156; compact = width < 520; this.detailOpen = detailOpen;
        close = rect(w - 20, 0, 20, 19); back = rect(4, 1, 36, 18);
        int top = compact ? 46 : 68, contentHeight = Math.max(0, h - top - 37);
        int lw = compact ? w - 16 : (w - 26) * 11 / 20;
        list = usable && (!compact || !detailOpen) ? rect(8, top, lw, contentHeight) : rect(0, 0, 0, 0);
        detail = usable && (!compact || detailOpen) ? rect(compact ? 8 : lw + 18, top, compact ? w - 16 : w - lw - 26, contentHeight) : rect(0, 0, 0, 0);
    }
    public boolean usable() { return usable; }
    public boolean compact() { return compact; }
    public boolean detailOpen() { return detailOpen; }
    public boolean showList() { return usable && (!compact || !detailOpen); }
    public boolean showDetail() { return usable && (!compact || detailOpen); }
    @Nonnull public StrategyHudLayout.Rect bounds() { return bounds; }
    @Nonnull public StrategyHudLayout.Rect list() { return list; }
    @Nonnull public StrategyHudLayout.Rect detail() { return detail; }
    @Nonnull public StrategyHudLayout.Rect close() { return close; }
    @Nonnull public StrategyHudLayout.Rect back() { return back; }
    public int rows() { return list.height() / ROW_HEIGHT; }
    @Nonnull public StrategyHudLayout.Rect tab(int index) { return divided(index, 5, 24, 18); }
    @Nonnull public StrategyHudLayout.Rect creation(int index) { return compact ? rect(0, 0, 0, 0) : divided(index, 2, 46, 18); }
    @Nonnull public StrategyHudLayout.Rect action(int index, int count) { return divided(index, count, bounds.height() - 30, 18); }
    @Nonnull public StrategyHudLayout.Rect station() { return creation(0); }
    @Nonnull public StrategyHudLayout.Rect row(int index) {
        if (index < 0 || index >= rows()) throw new IllegalArgumentException("Row outside list");
        return new StrategyHudLayout.Rect(list.x(), list.y() + index * ROW_HEIGHT, list.width(), ROW_HEIGHT - 1);
    }
    public int rowAt(double x, double y) {
        if (!showList() || !list.contains(x, y)) return -1;
        int index = ((int) y - list.y()) / ROW_HEIGHT;
        return index < rows() && row(index).contains(x, y) ? index : -1;
    }
    @Nonnull private StrategyHudLayout.Rect divided(int index, int count, int y, int h) {
        if (count < 1 || index < 0 || index >= count) throw new IllegalArgumentException("control");
        if (!usable) return rect(0, 0, 0, 0);
        int available = bounds.width() - 16, left = 8 + available * index / count, right = 8 + available * (index + 1) / count;
        return rect(left, y, right - left - 2, h);
    }
    @Nonnull private StrategyHudLayout.Rect rect(int x, int y, int w, int h) {
        int left = Math.max(0, Math.min(bounds.width(), x)), top = Math.max(0, Math.min(bounds.height(), y));
        return new StrategyHudLayout.Rect(bounds.x() + left, bounds.y() + top, Math.max(0, Math.min(w, bounds.width() - left)), Math.max(0, Math.min(h, bounds.height() - top)));
    }
}
