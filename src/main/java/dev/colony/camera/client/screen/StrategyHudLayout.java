package dev.colony.camera.client.screen;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shared drawing/hit geometry in scaled GUI pixels, independent of game APIs. */
public final class StrategyHudLayout {
    public enum Action { SINGLE, SIMILAR, VOLUME, ADDITIVE, CLEAR, SAVE_PRESET, LIBRARY, WORK, SIMILAR_LIMIT, BUILD, ANGLE_LOCK, HELP, EXIT }

    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double px, double py) {
            return Double.isFinite(px) && Double.isFinite(py)
                    && px >= x && px < right() && py >= y && py < bottom();
        }
    }

    public record Control(@Nonnull Action action, @Nonnull Rect bounds) {
        public Control {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(bounds, "bounds");
        }
    }

    private final Rect selection;
    private final Rect dock;
    private final Rect hint;
    private final Rect help;
    private final Rect similarLimitDialog;
    private final List<Control> controls;

    public StrategyHudLayout(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }
        int margin = Math.min(8, Math.min(width, height) / 8);
        int buttonSize = Math.min(22, (width - 2 * margin) / 7);
        int buttonGap = Math.min(4, margin);
        int rightStart = width - margin - 3 * buttonSize - 2 * buttonGap;
        int dockWidth = Math.min(400, width - 2 * margin);
        int dockHeight = Math.min(28, Math.max(0, height - 2 * margin));
        dock = new Rect((width - dockWidth) / 2, height - margin - dockHeight, dockWidth, dockHeight);
        int hintBottom = Math.max(margin, dock.y() - Math.min(4, margin));
        int hintHeight = Math.min(20, Math.max(0, hintBottom - margin));
        int hintWidth = Math.min(480, width - 2 * margin);
        hint = new Rect((width - hintWidth) / 2, hintBottom - hintHeight, hintWidth, hintHeight);
        selection = new Rect(margin, margin, Math.max(0, Math.min(156, rightStart - margin - buttonGap)),
                Math.min(20, Math.max(0, height - 2 * margin)));
        int helpWidth = Math.min(332, width - 2 * margin);
        int helpHeight = Math.min(192, height - 2 * margin);
        help = new Rect((width - helpWidth) / 2, (height - helpHeight) / 2, helpWidth, helpHeight);
        int dialogWidth = Math.min(280, width - 2 * margin);
        int dialogHeight = Math.min(128, height - 2 * margin);
        similarLimitDialog = new Rect((width - dialogWidth) / 2, (height - dialogHeight) / 2, dialogWidth, dialogHeight);
        int inset = Math.min(4, margin);
        int available = Math.max(0, dock.width() - 2 * inset);
        controls = List.of(
                tool(Action.SINGLE, 0, available, inset), tool(Action.SIMILAR, 1, available, inset),
                tool(Action.VOLUME, 2, available, inset), tool(Action.ADDITIVE, 3, available, inset),
                tool(Action.CLEAR, 4, available, inset), tool(Action.SAVE_PRESET, 5, available, inset),
                tool(Action.LIBRARY, 6, available, inset), tool(Action.WORK, 7, available, inset),
                tool(Action.SIMILAR_LIMIT, 8, available, inset), tool(Action.BUILD, 9, available, inset),
                new Control(Action.ANGLE_LOCK, new Rect(rightStart, margin, buttonSize, Math.min(buttonSize, height - margin))),
                new Control(Action.HELP, new Rect(rightStart + buttonSize + buttonGap, margin, buttonSize, Math.min(buttonSize, height - margin))),
                new Control(Action.EXIT, new Rect(rightStart + 2 * (buttonSize + buttonGap), margin, buttonSize, Math.min(buttonSize, height - margin))));
    }

    private Control tool(Action action, int index, int available, int inset) {
        int left = dock.x() + inset + available * index / 10;
        int right = dock.x() + inset + available * (index + 1) / 10;
        return new Control(action, new Rect(left, dock.y() + inset, right - left,
                Math.max(0, dock.height() - 2 * inset)));
    }

    public Rect selection() { return selection; }
    public Rect dock() { return dock; }
    public Rect hint() { return hint; }
    public Rect help() { return help; }
    public Rect similarLimitDialog() { return similarLimitDialog; }
    public List<Control> controls() { return controls; }

    @Nullable
    public Action actionAt(double x, double y) {
        for (Control control : controls) {
            if (control.bounds().contains(x, y)) {
                return control.action();
            }
        }
        return null;
    }

    public boolean blocksWorld(double x, double y, boolean helpOpen) {
        return blocksWorld(x, y, helpOpen, true, true);
    }
    public boolean blocksWorld(double x, double y, boolean helpOpen, boolean selectionVisible, boolean hintVisible) {
        return helpOpen || selectionVisible && selection.contains(x, y)
                || dock.contains(x, y) || hintVisible && hint.contains(x, y) || actionAt(x, y) != null;
    }
}
