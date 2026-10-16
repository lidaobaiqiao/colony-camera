package dev.colony.camera.client.screen;

import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.construction.ConstructionClient;
import dev.colony.camera.client.input.CameraKeyMappings;
import dev.colony.camera.client.planning.ProjectionController;
import dev.colony.camera.client.selection.BlockSelectionController;
import dev.colony.camera.client.work.ColonyWorkClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

/** Anchored command groups with transient feedback and upward menus. */
final class StrategyHud {
    private static final int TEXT = UiTheme.TEXT, MUTED = UiTheme.SECONDARY, ACCENT = UiTheme.ACCENT, GOLD = UiTheme.WARNING;
    private StrategyHudLayout layout = new StrategyHudLayout(1, 1);
    private StrategyHudLayout.Menu menu = StrategyHudLayout.Menu.NONE;
    private boolean helpOpen, buildOpen;
    private List<HelpRow> helpRows = List.of();

    void resize(@Nonnull Font font, int width, int height) {
        layout = new StrategyHudLayout(width, height, action -> font.width(label(action))
                + (action == StrategyHudLayout.Action.PROJECTION_MENU || action == StrategyHudLayout.Action.COLONY_MENU ? 11 : 0));
        helpRows = List.of(
                new HelpRow(text("mouse.left"), text("help.selection_all")),
                new HelpRow(text("mouse.right"), text("help.pan")),
                new HelpRow(text("mouse.middle"), text("help.middle")),
                new HelpRow(CameraKeyMappings.angleLockKeyName(), text("help.angle_lock")),
                new HelpRow(text("mouse.wheel"), text("help.zoom")),
                new HelpRow(literal("W / A / S / D"), text("help.move")),
                new HelpRow(Objects.requireNonNull(Component.empty().append(CameraKeyMappings.moveUpKeyName()).append(" / ").append(CameraKeyMappings.moveDownKeyName())), text("help.elevation")),
                new HelpRow(literal("Shift"), text("help.speed")),
                new HelpRow(CameraKeyMappings.addSelectionKeyName(), text("help.additive")),
                new HelpRow(CameraKeyMappings.volumeSelectionKeyName(), text("help.mode")),
                new HelpRow(Objects.requireNonNull(Component.empty().append(text("mouse.right_click")).append(" / ").append(CameraKeyMappings.clearSelectionKeyName())), text("help.clear")),
                new HelpRow(literal("N / P / B / O"), text("help.panels")),
                new HelpRow(Objects.requireNonNull(Component.empty().append(CameraKeyMappings.toggleKeyName()).append(" / Esc")), text("help.exit")));
    }
    StrategyHudLayout layout() { return layout; }
    boolean helpOpen() { return helpOpen; }
    boolean menuOpen() { return menu != StrategyHudLayout.Menu.NONE; }
    void toggleHelp() { helpOpen = !helpOpen; closeMenu(); }
    void closeHelp() { helpOpen = false; }
    boolean closeMenu() { boolean wasOpen = menuOpen(); menu = StrategyHudLayout.Menu.NONE; return wasOpen; }
    void toggleMenu(StrategyHudLayout.Menu next) {
        menu = menu == next ? StrategyHudLayout.Menu.NONE : next;
        if (menu == StrategyHudLayout.Menu.COLONY) { ColonyWorkClient.refresh(); ConstructionClient.refresh(); }
    }
    StrategyHudLayout.Action actionAt(double x, double y) { return layout.actionAt(x, y, menu); }
    boolean menuContains(double x, double y) { return menuOpen() && layout.menu(menu).contains(x, y); }
    boolean blocksWorld(double x, double y, StrategyCameraController controller, boolean sidebarOpen) {
        return menuOpen() || layout.blocksWorld(x, y, helpOpen, !sidebarOpen && selectionVisible(controller.selection()), hintVisible(controller.selection()));
    }
    private boolean selectionVisible(BlockSelectionController selection) { return selection.selectionCount() > 0 && ProjectionController.instance().draft() == null; }
    private boolean hintVisible(BlockSelectionController selection) {
        var p = ProjectionController.instance();
        return ColonyWorkClient.recentNotice() || ConstructionClient.recentNotice() || !p.blockDraft()
                && (p.draft() != null || p.recentStatus() || selection.recentStatus() || selection.working() || selection.selectingHeight());
    }
    void render(GuiGraphics g, @Nonnull Font font, StrategyCameraController controller, int mx, int my, int width, int height, boolean sidebarOpen) {
        buildOpen = sidebarOpen;
        var selection = controller.selection();
        if (!sidebarOpen && selectionVisible(selection)) {
            panel(g, layout.selection());
            drawFitted(g, font, text("selection_count", modeName(selection), selection.selectionCount()), layout.selection().x() + 7, layout.selection().y() + 6, layout.selection().width() - 14, GOLD);
        }
        panel(g, layout.leftDock()); panel(g, layout.rightDock());
        for (var c : layout.controls()) drawControl(g, font, c, controller, c.bounds().contains(mx, my), false);
        if (hintVisible(selection)) {
            var r = layout.hint(); panel(g, r);
            drawFitted(g, font, hint(selection), r.x() + 8, r.y() + 6, r.width() - 16, GOLD);
        }
    }
    void renderOverlay(GuiGraphics g, @Nonnull Font font, StrategyCameraController controller, int mx, int my, int width, int height) {
        if (helpOpen) { drawHelp(g, font, mx, my, width, height); return; }
        if (menuOpen()) {
            var r = layout.menu(menu); panel(g, r);
            if (menu == StrategyHudLayout.Menu.COLONY && r.height() >= 70) {
                drawFitted(g, font, text("overview.workers", ColonyWorkClient.workers().size(), ConstructionClient.cores()), r.x() + 8, r.y() + 6, r.width() - 16, ACCENT);
                long projects = ConstructionClient.projects().stream().filter(p -> !p.cancelled() && p.completed() < p.total()).count();
                long jobs = ColonyWorkClient.jobs().stream().filter(j -> !j.cancelled() && !j.finished()).count();
                drawFitted(g, font, text("overview.orders", projects, jobs), r.x() + 8, r.y() + 17, r.width() - 16, TEXT);
                drawFitted(g, font, text("overview.technology", ColonyWorkClient.text(ColonyWorkClient.tech().name())), r.x() + 8, r.y() + 28, r.width() - 16, MUTED);
            }
            for (var c : layout.menuControls(menu)) drawControl(g, font, c, controller, c.bounds().contains(mx, my), true);
        }
        var action = actionAt(mx, my);
        if (action != null) {
            Component tip = action == StrategyHudLayout.Action.ANGLE_LOCK
                    ? text(controller.angleLocked() ? "tip.unlock" : "tip.lock", CameraKeyMappings.angleLockKeyName())
                    : text("tip." + action.name().toLowerCase(Locale.ROOT));
            List<Component> tips = new ArrayList<>(); tips.add(tip);
            if (!enabled(action, controller.selection())) tips.add(text(controller.selection().working() || controller.selection().selectingHeight() ? "disabled.pending" : "disabled.selection"));
            drawTooltip(g, font, tips, mx, my, width);
        } else if (layout.hint().contains(mx, my) && hintVisible(controller.selection())) {
            drawTooltip(g, font, List.of(hint(controller.selection())), mx, my, width);
        }
    }
    private static boolean enabled(StrategyHudLayout.Action action, BlockSelectionController selection) {
        return switch (action) {
            case MINE, SAVE_PRESET -> selection.selectionCount() > 0 && !selection.working() && !selection.selectingHeight();
            default -> true;
        };
    }
    boolean enabled(StrategyHudLayout.Action action, StrategyCameraController controller) { return enabled(action, controller.selection()); }
    private void drawControl(GuiGraphics g, @Nonnull Font font, StrategyHudLayout.Control c, StrategyCameraController controller, boolean hover, boolean menuItem) {
        var r = c.bounds(); if (r.height() < 9 || r.width() < 4) return;
        var a = c.action();
        boolean active = a == StrategyHudLayout.Action.ANGLE_LOCK && controller.angleLocked()
                || a == StrategyHudLayout.Action.HELP && helpOpen || a == StrategyHudLayout.Action.BUILD && buildOpen
                || a == StrategyHudLayout.Action.PROJECTION_MENU && menu == StrategyHudLayout.Menu.PROJECTION
                || a == StrategyHudLayout.Action.COLONY_MENU && menu == StrategyHudLayout.Menu.COLONY;
        boolean enabled = enabled(a, controller.selection());
        if (active || hover && enabled) g.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, active ? UiTheme.SELECTED : UiTheme.RAISED);
        boolean top = a == StrategyHudLayout.Action.ANGLE_LOCK || a == StrategyHudLayout.Action.HELP || a == StrategyHudLayout.Action.EXIT;
        if (top) panel(g, r);
        Component caption = top ? literal(a == StrategyHudLayout.Action.HELP ? "?" : a == StrategyHudLayout.Action.EXIT ? "×" : controller.angleLocked() ? "⊙" : "↻") : label(a);
        int x = top ? r.x() + Math.max(0, (r.width() - font.width(caption)) / 2) : r.x() + (menuItem ? 7 : 4);
        boolean anchor = a == StrategyHudLayout.Action.PROJECTION_MENU || a == StrategyHudLayout.Action.COLONY_MENU;
        int ink = !enabled ? UiTheme.DISABLED : active ? ACCENT : hover ? TEXT : MUTED;
        drawFitted(g, font, caption, x, r.y() + Math.max(0, (r.height() - 9) / 2), r.right() - x - (anchor ? 13 : 3), ink);
        if (anchor) {
            int ax = r.right() - 8, ay = r.y() + r.height() / 2;
            for (int i = 0; i < 3; i++) g.fill(ax - i, ay + i - 1, ax + i + 1, ay + i, 0xFF000000 | ink);
        }
        if (active && !top) g.fill(r.x() + 4, r.bottom() - 3, r.right() - 4, r.bottom() - 2, UiTheme.ACCENT);
    }
    private void drawHelp(GuiGraphics g, @Nonnull Font font, int mx, int my, int width, int height) {
        g.fill(0, 0, width, height, 0x80071019); var r = layout.help(); panel(g, r);
        drawFitted(g, font, text("help.title"), r.x() + 12, r.y() + 10, r.width() - 36, TEXT);
        g.drawString(font, "×", r.right() - 17, r.y() + 10, MUTED, false);
        int rh = Math.max(9, Math.min(12, (r.height() - 42) / helpRows.size()));
        int kw = Math.min(104, (r.width() - 24) / 3), y = r.y() + 29;
        g.enableScissor(r.x(), r.y() + 25, r.right(), Math.max(r.y() + 25, r.bottom() - 15));
        HelpRow hovered = null;
        for (var row : helpRows) {
            drawFitted(g, font, row.key(), r.x() + 12, y, kw - 8, ACCENT);
            drawFitted(g, font, row.description(), r.x() + 12 + kw, y, r.width() - kw - 24, TEXT);
            if (r.contains(mx, my) && my >= y && my < y + rh) hovered = row;
            y += rh;
        }
        g.disableScissor();
        drawFitted(g, font, text("help.close"), r.x() + 12, r.bottom() - 12, r.width() - 24, MUTED);
        if (hovered != null) drawTooltip(g, font, List.of(hovered.key(), hovered.description()), mx, my, width);
    }
    @Nonnull private static Component modeName(BlockSelectionController s) {
        if (s.volumeMode() && !s.volumeFilter().snapshot().isEmpty()) return text("tool.volume_filtered", s.volumeFilter().snapshot().size());
        return text(s.additive() ? "tool.additive" : "tool." + s.mode().name().toLowerCase(Locale.ROOT));
    }
    @Nonnull private static Component hint(BlockSelectionController s) {
        if (ColonyWorkClient.recentNotice()) return ColonyWorkClient.notice();
        if (ConstructionClient.recentNotice()) return ConstructionClient.notice();
        var p = ProjectionController.instance();
        if (p.draft() != null || p.recentStatus()) return p.status();
        if (s.recentStatus()) return s.statusMessage();
        if (s.working()) return Objects.requireNonNull(Component.translatable("screen.colonycamera.selection.working"));
        if (s.selectingHeight()) return text("hint.height", s.heightLayers(), Component.translatable(s.heightOutward() ? "screen.colonycamera.selection.outward" : "screen.colonycamera.selection.inward"));
        return text("hint." + s.mode().name().toLowerCase(Locale.ROOT));
    }
    @Nonnull private static Component label(StrategyHudLayout.Action a) { return text("tool." + a.name().toLowerCase(Locale.ROOT)); }
    @Nonnull private static Component text(@Nonnull String suffix, @Nonnull Object... args) { return Objects.requireNonNull(Component.translatable("screen.colonycamera.ui." + suffix, args)); }
    @Nonnull private static Component literal(@Nonnull String value) { return Objects.requireNonNull(Component.literal(value)); }
    static void drawFitted(GuiGraphics g, @Nonnull Font font, @Nonnull Component text, int x, int y, int width, int color) {
        if (width <= 0) return;
        String value = Objects.requireNonNull(text.getString());
        if (font.width(value) > width) value = font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
        g.drawString(font, value, x, y, color, false);
    }
    private static void drawTooltip(GuiGraphics g, @Nonnull Font font, List<Component> lines, int mx, int my, int width) {
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (Component line : lines) wrapped.addAll(font.split(Objects.requireNonNull(line), Math.max(1, Math.min(300, width - 24))));
        g.renderTooltip(font, wrapped, mx, my);
    }
    static void panel(GuiGraphics g, StrategyHudLayout.Rect r) {
        UiDraw.panel(Objects.requireNonNull(g), Objects.requireNonNull(r));
    }
    private record HelpRow(@Nonnull Component key, @Nonnull Component description) {}
}
