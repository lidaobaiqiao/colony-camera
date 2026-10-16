package dev.colony.camera.client.screen;

import javax.annotation.Nonnull;

/** Right anchored details with a compact page switcher; printing remains an explicit modal. */
public final class InspectorLayout {
    public static final int ROW_HEIGHT = 18;
    @Nonnull public final StrategyHudLayout.Rect bounds, close, back, previous, next, tabs, content, notice;
    public final boolean usable, compact, modal;
    public InspectorLayout(int width, int height, boolean modal, int requestedRows) {
        if (width < 1 || height < 1 || requestedRows < 0) throw new IllegalArgumentException("viewport");
        this.modal = modal;
        int margin = Math.min(8, Math.min(width, height) / 8), space = width - 2 * margin;
        int w = modal ? Math.min(320, space) : Math.min(space, width >= 520 ? Math.max(196, Math.min(248, width / 3)) : Math.max(128, Math.min(176, width * 9 / 20)));
        compact = w < 184;
        int tabHeight = modal ? 0 : compact ? 18 : 36;
        int limit = modal ? height - 2 * margin : Math.max(1, height - 64);
        int h = Math.min(limit, 20 + tabHeight + 20 + Math.max(3, requestedRows) * ROW_HEIGHT);
        int y = modal ? (height - h) / 2 : Math.min(24, Math.max(0, height - h));
        bounds = new StrategyHudLayout.Rect(modal ? (width - w) / 2 : width - margin - w, y, w, h);
        close = rect(w - 20, 0, 20, 19); back = rect(1, 1, 18, 18);
        tabs = rect(6, 20, w - 12, tabHeight);
        previous = rect(6, 20, 18, tabHeight); next = rect(w - 24, 20, 18, tabHeight);
        notice = rect(6, h - 12, w - 12, 9);
        content = rect(6, 24 + tabHeight, w - 12, h - tabHeight - 40);
        usable = w >= 112 && h >= 84 && content.height() >= ROW_HEIGHT;
    }
    public int rows() { return content.height() / ROW_HEIGHT; }
    @Nonnull public StrategyHudLayout.Rect tab(int index) {
        if (index < 0 || index >= 6) throw new IllegalArgumentException("tab");
        if (compact) return rect(26, 20, bounds.width() - 52, 18);
        int available = Math.max(0, bounds.width() - 12), col = index % 3;
        int left = 6 + available * col / 3, right = 6 + available * (col + 1) / 3;
        return rect(left, 20 + index / 3 * 18, right - left - 1, 17);
    }
    @Nonnull public StrategyHudLayout.Rect row(int index) {
        if (index < 0 || index >= rows()) throw new IllegalArgumentException("row");
        return new StrategyHudLayout.Rect(content.x(), content.y() + index * ROW_HEIGHT, content.width(), ROW_HEIGHT - 1);
    }
    public int rowAt(double x, double y) {
        if (!usable || !content.contains(x, y)) return -1;
        int index = ((int) y - content.y()) / ROW_HEIGHT;
        return index < rows() && row(index).contains(x, y) ? index : -1;
    }
    @Nonnull private StrategyHudLayout.Rect rect(int x, int y, int w, int h) {
        int left = Math.max(0, Math.min(bounds.width(), x)), top = Math.max(0, Math.min(bounds.height(), y));
        return new StrategyHudLayout.Rect(bounds.x() + left, bounds.y() + top, Math.max(0, Math.min(w, bounds.width() - left)), Math.max(0, Math.min(h, bounds.height() - top)));
    }
}
