package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.StrategyHudLayout;
import javax.annotation.Nonnull;

/** Independent tool/life lanes; compact screens navigate into a full readable detail area. */
public final class TechnologyPanelLayout {
    @Nonnull public final StrategyHudLayout.Rect bounds, tree, detail, close, back, station, research, refresh;
    public final boolean usable, horizontal, showTree, showDetail;
    public TechnologyPanelLayout(int width, int height) { this(width, height, false); }
    public TechnologyPanelLayout(int width, int height, boolean details) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("viewport");
        int margin = Math.min(8, Math.min(width, height) / 8);
        int w = Math.min(width >= 520 ? 470 : 296, width - 2 * margin), h = Math.min(286, height - 2 * margin);
        bounds = new StrategyHudLayout.Rect((width - w) / 2, (height - h) / 2, w, h);
        usable = w >= 260 && h >= 156; horizontal = width < 520;
        showTree = !horizontal || !details; showDetail = !horizontal || details;
        close = rect(w - 20, 0, 20, 19); back = rect(4, 1, 36, 18);
        int treeWidth = horizontal ? w - 16 : w * 2 / 5;
        tree = usable && showTree ? rect(8, 27, treeWidth, horizontal ? h - 58 : h - 90) : rect(0, 0, 0, 0);
        detail = usable && showDetail ? rect(horizontal ? 8 : treeWidth + 18, 27, horizontal ? w - 16 : w - treeWidth - 26, h - 90) : rect(0, 0, 0, 0);
        station = usable && showDetail ? rect(8, h - 58, w - 16, 18) : rect(0, 0, 0, 0);
        research = usable && showDetail ? rect(8, h - 35, (w - 20) * 3 / 4, 22) : rect(0, 0, 0, 0);
        refresh = usable ? rect(showDetail ? research.width() + 12 : w - 64, h - (showDetail ? 35 : 26), showDetail ? w - research.width() - 20 : 56, showDetail ? 22 : 18) : rect(0, 0, 0, 0);
    }
    @Nonnull private StrategyHudLayout.Rect rect(int x, int y, int w, int h) {
        int left = Math.max(0, Math.min(bounds.width(), x)), top = Math.max(0, Math.min(bounds.height(), y));
        return new StrategyHudLayout.Rect(bounds.x() + left, bounds.y() + top, Math.max(0, Math.min(w, bounds.width() - left)), Math.max(0, Math.min(h, bounds.height() - top)));
    }
    @Nonnull public StrategyHudLayout.Rect node(int index) {
        if (index < 0 || index >= 8) throw new IllegalArgumentException("node");
        if (!usable || !showTree) return rect(0, 0, 0, 0);
        return rect(8 + tree.width() * (index / 4) / 2, 27 + index % 4 * tree.height() / 4,
                tree.width() / 2 - 6, Math.min(38, tree.height() / 4 - 2));
    }
}
