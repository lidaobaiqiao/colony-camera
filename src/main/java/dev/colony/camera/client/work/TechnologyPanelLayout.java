package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.StrategyHudLayout;
import javax.annotation.Nonnull;

/** Four real tool stages, with scrollable details on a narrow viewport. */
public final class TechnologyPanelLayout {
    @Nonnull public final StrategyHudLayout.Rect bounds, tree, detail, close, station, research, refresh;
    public final boolean usable, horizontal;
    public TechnologyPanelLayout(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("viewport");
        int margin = Math.min(8, Math.min(width, height) / 8);
        int w = Math.min(width >= 520 ? 438 : 296, width - 2 * margin);
        int h = Math.min(240, height - 2 * margin);
        bounds = new StrategyHudLayout.Rect((width - w) / 2, (height - h) / 2, w, h);
        usable = w >= 260 && h >= 156; horizontal = width < 520;
        close = rect(Math.max(0, w - 20), 0, 20, 19);
        tree = usable ? rect(8, 27, horizontal ? w - 16 : w * 2 / 5, horizontal ? 28 : h - 90) : rect(0, 0, 0, 0);
        detail = usable ? rect(horizontal ? 8 : tree.width() + 18, horizontal ? 60 : 27,
                horizontal ? w - 16 : w - tree.width() - 26, horizontal ? h - 103 : h - 90) : rect(0, 0, 0, 0);
        station = usable ? rect(8, h - (horizontal ? 40 : 58), w - 16, horizontal ? 16 : 18) : rect(0, 0, 0, 0);
        research = usable ? rect(8, h - (horizontal ? 21 : 35), (w - 20) * 3 / 4, horizontal ? 18 : 22) : rect(0, 0, 0, 0);
        refresh = usable ? rect(research.width() + 12, research.y() - bounds.y(), w - research.width() - 20, research.height()) : rect(0, 0, 0, 0);
    }
    @Nonnull private StrategyHudLayout.Rect rect(int x, int y, int w, int h) {
        int left = Math.max(0, Math.min(bounds.width(), x)), top = Math.max(0, Math.min(bounds.height(), y));
        return new StrategyHudLayout.Rect(bounds.x() + left, bounds.y() + top, Math.min(Math.max(0, w), bounds.width() - left), Math.min(Math.max(0, h), bounds.height() - top));
    }
    @Nonnull public StrategyHudLayout.Rect node(int index) {
        if (index < 0 || index >= 4) throw new IllegalArgumentException("node");
        if (!usable) return rect(0, 0, 0, 0);
        return horizontal ? rect(8 + tree.width() * index / 4, 27, tree.width() / 4 - 4, 28)
                : rect(8, 27 + index * tree.height() / 4, tree.width(), Math.min(28, tree.height() / 4 - 3));
    }
}
