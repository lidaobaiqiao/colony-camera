package dev.colony.camera.client.screen;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Scaled GUI geometry shared by projection drawing and input ownership. */
public final class ProjectionPanelLayout {
    public enum Action { ROTATE, MIRROR, UP, DOWN, FOLLOW, CONFIRM, CANCEL_DRAFT }
    public record Control(@Nonnull Action action, @Nonnull StrategyHudLayout.Rect bounds) {}
    @Nonnull private final StrategyHudLayout.Rect modal;
    @Nonnull private final StrategyHudLayout.Rect draft;

    public ProjectionPanelLayout(int width, int height, int sceneBottom) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid viewport");
        int w = Math.max(0, Math.min(460, width - 16));
        int h = Math.max(0, Math.min(270, height - 16));
        modal = new StrategyHudLayout.Rect((width - w) / 2, (height - h) / 2, w, h);
        int draftWidth = Math.max(0, Math.min(190, width - 16));
        int top = Math.min(44, Math.max(0, height - 16));
        int draftHeight = Math.max(0, Math.min(170, Math.min(height, sceneBottom) - top - 4));
        draft = new StrategyHudLayout.Rect(Math.max(0, width - draftWidth - 8), top, draftWidth, draftHeight);
    }

    @Nonnull public StrategyHudLayout.Rect modal() { return modal; }
    @Nonnull public StrategyHudLayout.Rect draft() { return draft; }
    public int visibleRows() { return Math.max(1, (modal.height() - 88) / 27); }
    public int listWidth() { return Math.max(1, (modal.width() - 28) * 3 / 5); }

    @Nonnull
    public List<Control> draftControls() {
        if (draft.height() < 54 || draft.width() < 22) return Objects.requireNonNull(List.of());
        int rowHeight = draft.height() >= 108 ? 20 : 12;
        int gap = draft.height() >= 108 ? 2 : 1;
        int rows = draft.height() >= 160 ? 4 : 3;
        int y = draft.bottom() - rows * (rowHeight + gap) - 3;
        int bw = (draft.width() - 18) / 2;
        var result = new ArrayList<Control>();
        add(result, Action.ROTATE, draft.x() + 6, y, bw, rowHeight);
        add(result, Action.MIRROR, draft.x() + 12 + bw, y, bw, rowHeight);
        add(result, Action.UP, draft.x() + 6, y + rowHeight + gap, bw, rowHeight);
        add(result, Action.DOWN, draft.x() + 12 + bw, y + rowHeight + gap, bw, rowHeight);
        if (rows == 4) add(result, Action.FOLLOW, draft.x() + 6, y + 2 * (rowHeight + gap), draft.width() - 12, rowHeight);
        add(result, Action.CONFIRM, draft.x() + 6, draft.bottom() - rowHeight - 3, bw, rowHeight);
        add(result, Action.CANCEL_DRAFT, draft.x() + 12 + bw, draft.bottom() - rowHeight - 3, bw, rowHeight);
        return Objects.requireNonNull(List.copyOf(result));
    }

    private static void add(List<Control> result, Action action, int x, int y, int w, int h) {
        result.add(new Control(Objects.requireNonNull(action), new StrategyHudLayout.Rect(x, y, w, h)));
    }
}
