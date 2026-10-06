package dev.colony.camera.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Drawing and hit geometry, measured in scaled GUI pixels. */
public final class StrategyHudLayout {
    public enum Action { BUILD, PROJECTION_MENU, COLONY_MENU, MINE, SWEEP, SAVE_PRESET, LIBRARY,
        SIMILAR_LIMIT, WORK, PEOPLE, CRAFT, TECHNOLOGY, PROJECTS, ANGLE_LOCK, HELP, EXIT }
    public enum Menu { NONE, PROJECTION, COLONY }

    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double px, double py) {
            return Double.isFinite(px) && Double.isFinite(py)
                    && px >= x && px < right() && py >= y && py < bottom();
        }
    }
    public record Control(@Nonnull Action action, @Nonnull Rect bounds) {
        public Control { Objects.requireNonNull(action); Objects.requireNonNull(bounds); }
    }

    @Nonnull private final Rect selection, leftDock, rightDock, hint, help, similarLimitDialog, placement;
    @Nonnull private final Rect projectionMenu, colonyMenu;
    @Nonnull private final List<Control> controls, projectionControls, colonyControls;

    public StrategyHudLayout(int width, int height) {
        this(width, height, action -> switch (action) {
            case PROJECTION_MENU -> 27;
            case COLONY_MENU -> 36;
            default -> 18;
        });
    }
    public StrategyHudLayout(int width, int height, ToIntFunction<Action> textWidth) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Viewport must be positive");
        int margin = Math.min(8, Math.min(width, height) / 8);
        int inset = Math.min(4, margin), gap = Math.min(4, margin);
        int space = width - 2 * margin;
        Action[] left = {Action.BUILD, Action.PROJECTION_MENU};
        Action[] right = {Action.MINE, Action.SWEEP, Action.COLONY_MENU};
        int requestedLeft = groupWidth(left, textWidth, inset, gap);
        int requestedRight = groupWidth(right, textWidth, inset, gap);
        int reserved = Math.min(64, space / 5);
        double scale = Math.min(1, (double) Math.max(0, space - reserved) / (requestedLeft + requestedRight));
        int lh = Math.min(28, height - 2 * margin);
        int lw = (int) (requestedLeft * scale), rw = (int) (requestedRight * scale);
        leftDock = new Rect(margin, height - margin - lh, lw, lh);
        rightDock = new Rect(width - margin - rw, leftDock.y(), rw, lh);
        List<Control> all = new ArrayList<>();
        group(all, left, leftDock, textWidth, inset, gap);
        group(all, right, rightDock, textWidth, inset, gap);
        int buttonSize = Math.min(22, space / 7);
        int rightStart = width - margin - 3 * buttonSize - 2 * gap;
        for (int i = 0; i < 3; i++) all.add(new Control(Objects.requireNonNull(
                new Action[]{Action.ANGLE_LOCK, Action.HELP, Action.EXIT}[i]),
                new Rect(rightStart + i * (buttonSize + gap), margin, buttonSize, Math.min(buttonSize, height - margin))));
        controls = Objects.requireNonNull(List.copyOf(all));
        selection = new Rect(margin, margin, Math.max(0, Math.min(156, rightStart - margin - gap)), Math.min(20, height - 2 * margin));
        int hintBottom = Math.max(margin, leftDock.y() - gap);
        hint = centered(width, Math.min(480, space), hintBottom - Math.min(20, hintBottom - margin), Math.min(20, hintBottom - margin));
        help = centered(width, Math.min(350, space), (height - Math.min(208, height - 2 * margin)) / 2, Math.min(208, height - 2 * margin));
        similarLimitDialog = centered(width, Math.min(280, space), (height - Math.min(128, height - 2 * margin)) / 2, Math.min(128, height - 2 * margin));
        int middleLeft = leftDock.right() + gap, middleRight = rightDock.x() - gap;
        int pw = Math.min(248, Math.max(0, middleRight - middleLeft));
        placement = new Rect(middleLeft + (Math.max(0, middleRight - middleLeft) - pw) / 2, leftDock.y(), pw, lh);
        projectionMenu = menu(width, margin, leftDock, false, 4, 0, textWidth);
        colonyMenu = menu(width, margin, rightDock, true, 5, 38, textWidth);
        projectionControls = menuControls(projectionMenu, 0, new Action[]{Action.SAVE_PRESET, Action.LIBRARY, Action.BUILD, Action.SIMILAR_LIMIT});
        colonyControls = menuControls(colonyMenu, Math.min(38, colonyMenu.height() / 3), new Action[]{Action.PEOPLE, Action.WORK, Action.CRAFT, Action.TECHNOLOGY, Action.PROJECTS});
    }
    private static int groupWidth(Action[] actions, ToIntFunction<Action> measure, int inset, int gap) {
        int width = 2 * inset + (actions.length - 1) * gap;
        for (Action action : actions) width += Math.max(24, measure.applyAsInt(action) + 14);
        return width;
    }
    private static void group(List<Control> result, Action[] actions, Rect dock, ToIntFunction<Action> measure, int inset, int gap) {
        double ratio = (double) dock.width() / Math.max(1, groupWidth(actions, measure, inset, gap));
        int offset = inset;
        for (Action action : actions) {
            int size = Math.max(24, measure.applyAsInt(action) + 14);
            int x = dock.x() + (int) (offset * ratio);
            int right = dock.x() + (int) ((offset + size) * ratio);
            result.add(new Control(Objects.requireNonNull(action), new Rect(x, dock.y() + Math.min(inset, dock.height() / 2), right - x, Math.max(0, dock.height() - 2 * inset))));
            offset += size + gap;
        }
    }
    @Nonnull private static Rect centered(int width, int size, int y, int height) { return new Rect((width - size) / 2, y, size, height); }
    @Nonnull private static Rect menu(int width, int margin, Rect dock, boolean right, int count, int header, ToIntFunction<Action> measure) {
        int requested = 150;
        Action[] entries = right ? new Action[]{Action.PEOPLE, Action.WORK, Action.CRAFT, Action.TECHNOLOGY, Action.PROJECTS}
                : new Action[]{Action.SAVE_PRESET, Action.LIBRARY, Action.BUILD, Action.SIMILAR_LIMIT};
        for (Action action : entries) requested = Math.max(requested, measure.applyAsInt(action) + 20);
        int w = Math.min(requested, width - 2 * margin), h = Math.min(header + count * 22 + 8, Math.max(0, dock.y() - margin - 4));
        int x = right ? Math.max(margin, dock.right() - w) : margin;
        return new Rect(x, Math.max(margin, dock.y() - 4 - h), w, h);
    }
    @Nonnull private static List<Control> menuControls(Rect menu, int header, Action[] actions) {
        List<Control> result = new ArrayList<>();
        int available = Math.max(0, menu.height() - header - 8);
        for (int i = 0; i < actions.length; i++) {
            int y = menu.y() + 4 + header + available * i / actions.length;
            int bottom = menu.y() + 4 + header + available * (i + 1) / actions.length;
            result.add(new Control(Objects.requireNonNull(actions[i]), new Rect(menu.x() + Math.min(4, menu.width()), Math.min(menu.bottom(), y),
                    Math.max(0, menu.width() - 8), Math.max(0, bottom - y))));
        }
        return Objects.requireNonNull(List.copyOf(result));
    }
    @Nonnull public Rect selection() { return selection; }
    @Nonnull public Rect dock() { return leftDock; }
    @Nonnull public Rect leftDock() { return leftDock; }
    @Nonnull public Rect rightDock() { return rightDock; }
    @Nonnull public Rect placement() { return placement; }
    @Nonnull public Rect hint() { return hint; }
    @Nonnull public Rect help() { return help; }
    @Nonnull public Rect similarLimitDialog() { return similarLimitDialog; }
    @Nonnull public List<Control> controls() { return controls; }
    @Nonnull public Rect menu(Menu menu) { return menu == Menu.COLONY ? colonyMenu : projectionMenu; }
    @Nonnull public List<Control> menuControls(Menu menu) { return menu == Menu.COLONY ? colonyControls : menu == Menu.PROJECTION ? projectionControls : Objects.requireNonNull(List.of()); }
    @Nullable public Action actionAt(double x, double y) { return actionAt(x, y, Menu.NONE); }
    @Nullable public Action actionAt(double x, double y, Menu menu) {
        for (Control c : menuControls(menu)) if (c.bounds().contains(x, y)) return c.action();
        if (menu != Menu.NONE && menu(menu).contains(x, y)) return null;
        for (Control c : controls) if (c.bounds().contains(x, y)) return c.action();
        return null;
    }
    public boolean blocksWorld(double x, double y, boolean helpOpen) { return blocksWorld(x, y, helpOpen, true, true); }
    public boolean blocksWorld(double x, double y, boolean helpOpen, boolean selectionVisible, boolean hintVisible) {
        return helpOpen || selectionVisible && selection.contains(x, y) || hintVisible && hint.contains(x, y)
                || leftDock.contains(x, y) || rightDock.contains(x, y) || actionAt(x, y) != null;
    }
    public static double progress(int done, int total) { return total <= 0 ? 0 : Math.max(0, Math.min(1, (double) done / total)); }
}
