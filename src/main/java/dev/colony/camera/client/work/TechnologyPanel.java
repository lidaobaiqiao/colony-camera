package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.StrategyHudLayout;
import dev.colony.camera.client.screen.UiDraw;
import dev.colony.camera.client.screen.UiTheme;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Inspects existing server technology, and queues the existing research intention. */
@ParametersAreNonnullByDefault
final class TechnologyPanel {
    private TechnologyPanelLayout layout = new TechnologyPanelLayout(1, 1);
    @Nullable private Font font;
    @Nullable private ColonyWorkClient.Station station;
    private boolean open, detailOpen;
    private int selected = 1, scroll, width = 1, height = 1;
    void resize(Font value, int w, int h) { font = value; width = w; height = h; layout = new TechnologyPanelLayout(w, h, detailOpen); clampScroll(); }
    void station(@Nullable ColonyWorkClient.Station value) { station = value; }
    boolean isOpen() { return open; }
    void open() {
        open = true;
        var choice = ColonyWorkClient.techNodes().stream().filter(node -> node.running()).findFirst().orElse(null);
        if (choice == null) choice = ColonyWorkClient.techNodes().stream().filter(node -> node.available()).findFirst().orElse(null);
        selected = choice == null ? Math.max(0, ColonyWorkClient.tech().nextTier()) : choice.tier();
        back(); ColonyWorkClient.refresh();
    }
    void close() { open = false; }
    private void back() { detailOpen = false; layout = new TechnologyPanelLayout(width, height, false); scroll = 0; }
    private void revealDetails() { detailOpen = true; layout = new TechnologyPanelLayout(width, height, true); scroll = 0; }
    boolean keyPressed(int key, int scan, int modifiers) {
        if (!open) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { if (layout.horizontal && detailOpen) back(); else close(); return true; }
        if (layout.horizontal && !detailOpen && key == GLFW.GLFW_KEY_ENTER) { revealDetails(); return true; }
        if (!layout.showTree && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)) {
            scroll += (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN ? Math.max(1, layout.detail.height() / 12) : 1);
        } else if (layout.showTree) {
            if (key == GLFW.GLFW_KEY_UP) selected = selected / 4 * 4 + Math.max(0, selected % 4 - 1);
            if (key == GLFW.GLFW_KEY_DOWN) selected = selected / 4 * 4 + Math.min(3, selected % 4 + 1);
            if (key == GLFW.GLFW_KEY_LEFT) selected %= 4;
            if (key == GLFW.GLFW_KEY_RIGHT) selected = selected % 4 + 4;
            scroll = 0;
        }
        return true;
    }
    boolean click(double x, double y, int button) {
        if (!open) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (layout.close.contains(x, y)) { close(); return true; }
        if (layout.horizontal && detailOpen && layout.back.contains(x, y)) { back(); return true; }
        if (!layout.usable) return true;
        if (layout.showTree) for (int i = 0; i < 8; i++) if (layout.node(i).contains(x, y)) { selected = i; revealDetails(); return true; }
        if (layout.station.contains(x, y)) {
            var values = ColonyWorkClient.stations().stream().filter(value -> Objects.requireNonNull(value).research()).toList();
            if (!values.isEmpty()) station = values.get((values.indexOf(station) + 1) % values.size());
        }
        if (layout.research.contains(x, y) && canResearch()) { var current = station; if (current != null) ColonyWorkClient.research(current.pos(), selected); }
        if (layout.refresh.contains(x, y)) ColonyWorkClient.refresh();
        return true;
    }
    boolean scroll(double x, double y, double delta) {
        if (!open) return false;
        if (layout.detail.contains(x, y) && Double.isFinite(delta)) { scroll -= (int) Math.signum(delta); clampScroll(); }
        return true;
    }
    private boolean canResearch() {
        if (station == null) station = ColonyWorkClient.stations().stream().filter(value -> Objects.requireNonNull(value).research()).findFirst().orElse(null);
        var node = node();
        return node != null && node.available() && !ColonyWorkClient.tech().readOnly()
                && station != null && ColonyWorkClient.stations().contains(station) && running() == null;
    }
    @Nullable private ColonyWorkClient.Job running() { return ColonyWorkClient.jobs().stream().filter(j -> j.kind().equals("RESEARCH") && !j.cancelled() && !j.finished()).findFirst().orElse(null); }
    @Nullable private ColonyWorkClient.TechNode node() { return ColonyWorkClient.techNodes().stream().filter(n -> n.tier() == selected).findFirst().orElse(null); }
    private void clampScroll() { scroll = Math.max(0, scroll); }
    @Nonnull private List<Component> details() {
        List<Component> lines = new ArrayList<>(); var node = node();
        if (node == null) { lines.add(text("loading")); return lines; }
        lines.add(ColonyWorkClient.text(node.name()));
        lines.add(ColonyWorkClient.text(node.effect()));
        lines.add(node.done() ? text("done") : ColonyWorkClient.text(node.reason()));
        lines.add(text("prerequisite", node.requiredTier() < 0 ? text("none") : tier(node.requiredTier())));
        for (var cost : node.costs()) lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.ingredient", ColonyWorkClient.text(cost.name()), cost.count())));
        lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.research_duration", node.researchTicks() / 20)));
        var job = running();
        if (job != null && node.running()) {
            lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.reason." + job.reason())));
            lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.job_row", job.priority(), job.done(), job.total())));
        }
        if (station == null || !ColonyWorkClient.stations().contains(station)) lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.need_research_station")));
        if (ColonyWorkClient.tech().readOnly()) lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.reason.tech_data")));
        return lines;
    }
    void render(GuiGraphics g, int mx, int my) {
        Font current = font; if (!open || current == null) return;
        clampScroll(); g.fill(0, 0, width, height, UiTheme.SCRIM); var r = layout.bounds;
        UiDraw.panel(g, r);
        fitted(g, current, text("title"), r.x() + (layout.horizontal && detailOpen ? 47 : 8), r.y() + 6, r.width() - (layout.horizontal && detailOpen ? 75 : 36), UiTheme.TEXT);
        button(g, current, layout.close, Objects.requireNonNull(Component.literal("×")), true, mx, my);
        if (layout.horizontal && detailOpen) button(g, current, layout.back, Objects.requireNonNull(Component.translatable("colonycamera.ui.back")), true, mx, my);
        if (!layout.usable) { fitted(g, current, Objects.requireNonNull(Component.translatable("colonycamera.work.small_screen")), r.x() + 4, r.y() + 24, r.width() - 8, UiTheme.WARNING); return; }
        if (layout.showTree) for (int i = 0; i < 8; i++) {
            var cell = layout.node(i);
            int id = i; var item = ColonyWorkClient.techNodes().stream().filter(n -> n.tier() == id).findFirst().orElse(null);
            if (item != null && item.requiredTier() >= 0 && item.requiredTier() < 8 && item.requiredTier() / 4 == i / 4) {
                var parent = layout.node(item.requiredTier()); int edge = cell.right() + 2;
                g.fill(parent.right(), parent.y() + parent.height() / 2, edge + 1, parent.y() + parent.height() / 2 + 1, UiTheme.BORDER);
                g.fill(edge, Math.min(parent.y() + parent.height() / 2, cell.y() + cell.height() / 2), edge + 1, cell.y() + cell.height() / 2 + 1, UiTheme.BORDER);
                g.fill(cell.right(), cell.y() + cell.height() / 2, edge + 1, cell.y() + cell.height() / 2 + 1, UiTheme.BORDER);
            }
            boolean done = item != null && item.done(), available = item != null && item.available();
            int ink = done ? UiTheme.ACCENT : available ? UiTheme.WARNING : UiTheme.DISABLED;
            g.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), i == selected ? UiTheme.SELECTED : cell.contains(mx, my) ? UiTheme.RAISED : UiTheme.SURFACE);
            g.fill(cell.x() + 4, cell.y() + 4, cell.x() + 7, cell.y() + 7, 0xFF000000 | ink);
            fitted(g, current, tier(i), cell.x() + 10, cell.y() + 4, cell.width() - 14, ink);
            if (cell.height() >= 24) fitted(g, current, text(done ? "done" : item != null && item.running() ? "running" : available ? "available" : "locked"), cell.x() + 5, cell.y() + 14, cell.width() - 10, ink);
            if (item != null && item.running()) {
                int w = Math.max(0, cell.width() - 8);
                g.fill(cell.x() + 4, cell.bottom() - 2, cell.x() + 4 + (int) Math.round(w * item.progress()), cell.bottom() - 1, UiTheme.WARNING);
            }
        }
        List<Component> lines = details(); List<net.minecraft.util.FormattedCharSequence> wrappedLines = new ArrayList<>();
        if (layout.showDetail) for (Component line : lines) wrappedLines.addAll(current.split(Objects.requireNonNull(line), Math.max(1, layout.detail.width())));
        int visible = Math.max(1, layout.detail.height() / 12);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, wrappedLines.size() - visible))); int y = layout.detail.y();
        if (layout.showDetail) {
        g.enableScissor(layout.detail.x(), layout.detail.y(), layout.detail.right(), layout.detail.bottom());
        for (int i = scroll; i < wrappedLines.size() && y + 9 <= layout.detail.bottom(); i++, y += 12)
            g.drawString(current, Objects.requireNonNull(wrappedLines.get(i)), layout.detail.x(), y, UiTheme.TEXT, false);
        g.disableScissor();
        }
        var facility = station;
        Component stationLabel = facility == null ? Objects.requireNonNull(Component.translatable("colonycamera.work.need_research_station"))
                : Objects.requireNonNull(Component.translatable("colonycamera.work.station", Component.translatable("colonycamera.work.research_station"), facility.pos().getX(), facility.pos().getY(), facility.pos().getZ()));
        button(g, current, layout.station, stationLabel, true, mx, my);
        button(g, current, layout.research, text(running() != null ? "running" : "research"), canResearch(), mx, my);
        button(g, current, layout.refresh, Objects.requireNonNull(Component.translatable("colonycamera.work.refresh")), true, mx, my);
        if (!layout.showDetail) fitted(g, current, Objects.requireNonNull(Component.translatable("colonycamera.ui.select_details")), r.x() + 8, layout.refresh.y() + 5, layout.refresh.x() - r.x() - 16, UiTheme.SECONDARY);
        if (layout.detail.contains(mx, my) || layout.research.contains(mx, my)) {
            List<net.minecraft.util.FormattedCharSequence> wrapped = new ArrayList<>();
            for (Component line : lines) wrapped.addAll(current.split(Objects.requireNonNull(line), Math.max(1, Math.min(260, width - 24))));
            g.renderTooltip(current, wrapped, mx, my);
        } else for (int i = 0; i < 8; i++) if (layout.node(i).contains(mx, my)) g.renderTooltip(current, tier(i), mx, my);
    }
    private static void button(GuiGraphics g, Font font, StrategyHudLayout.Rect r, Component caption, boolean enabled, int mx, int my) {
        UiDraw.button(g, font, r, caption, enabled, false, r.contains(mx, my));
    }
    private static void fitted(GuiGraphics g, Font font, Component caption, int x, int y, int width, int ink) { UiDraw.fitted(g, font, caption, x, y, width, ink); }
    @Nonnull private static Component tier(int stage) {
        return Objects.requireNonNull(ColonyWorkClient.techNodes().stream().filter(node -> node.tier() == stage).map(node -> ColonyWorkClient.text(node.name()))
                .findFirst().orElse(Objects.requireNonNull(Component.translatable("screen.colonycamera.technology.loading"))));
    }
    @Nonnull private static Component text(String key, Object... args) { return Objects.requireNonNull(Component.translatable("screen.colonycamera.technology." + key, args)); }
}
