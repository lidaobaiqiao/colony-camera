package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.StrategyHudLayout;
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
    private boolean open;
    private int selected = 1, scroll, width = 1, height = 1;
    void resize(Font value, int w, int h) { font = value; width = w; height = h; layout = new TechnologyPanelLayout(w, h); clampScroll(); }
    void station(@Nullable ColonyWorkClient.Station value) { station = value; }
    boolean isOpen() { return open; }
    void open() { open = true; selected = Math.max(0, ColonyWorkClient.tech().nextTier()); scroll = 0; ColonyWorkClient.refresh(); }
    void close() { open = false; }
    boolean keyPressed(int key, int scan, int modifiers) {
        if (!open) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) close();
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_LEFT) { selected = Math.max(0, selected - 1); scroll = 0; }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_RIGHT) { selected = Math.min(3, selected + 1); scroll = 0; }
        return true;
    }
    boolean click(double x, double y, int button) {
        if (!open) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (layout.close.contains(x, y)) close();
        if (!layout.usable) return true;
        for (int i = 0; i < 4; i++) if (layout.node(i).contains(x, y)) { selected = i; scroll = 0; return true; }
        if (layout.station.contains(x, y)) {
            var values = ColonyWorkClient.stations().stream().filter(value -> Objects.requireNonNull(value).research()).toList();
            if (!values.isEmpty()) station = values.get((values.indexOf(station) + 1) % values.size());
        }
        if (layout.research.contains(x, y) && canResearch()) { var current = station; if (current != null) ColonyWorkClient.research(current.pos()); }
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
        return selected == ColonyWorkClient.tech().nextTier() && !ColonyWorkClient.tech().readOnly()
                && station != null && ColonyWorkClient.stations().contains(station) && running() == null && node() != null;
    }
    @Nullable private ColonyWorkClient.Job running() { return ColonyWorkClient.jobs().stream().filter(j -> j.kind().equals("RESEARCH") && !j.cancelled() && !j.finished()).findFirst().orElse(null); }
    @Nullable private ColonyWorkClient.TechNode node() { return ColonyWorkClient.techNodes().stream().filter(n -> n.tier() == selected).findFirst().orElse(null); }
    private void clampScroll() { scroll = Math.max(0, Math.min(scroll, Math.max(0, details().size() - Math.max(1, layout.detail.height() / 12)))); }
    @Nonnull private List<Component> details() {
        List<Component> lines = new ArrayList<>(); var node = node();
        if (node == null) { lines.add(text("loading")); return lines; }
        lines.add(ColonyWorkClient.text(node.name()));
        lines.add(text("prerequisite", node.requiredTier() < 0 ? text("none") : tier(node.requiredTier())));
        for (var cost : node.costs()) lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.ingredient", ColonyWorkClient.text(cost.name()), cost.count())));
        lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.research_duration", node.researchTicks() / 20)));
        lines.add(text("unlocks", tier(node.tier())));
        if (node.tier() <= ColonyWorkClient.tech().tier()) lines.add(text("done"));
        else if (node.tier() != ColonyWorkClient.tech().nextTier()) lines.add(text("locked"));
        var job = running();
        if (job != null && node.tier() == ColonyWorkClient.tech().nextTier()) {
            lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.reason." + job.reason())));
            lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.job_row", job.priority(), job.done(), job.total())));
        }
        if (station == null || !ColonyWorkClient.stations().contains(station)) lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.need_research_station")));
        if (ColonyWorkClient.tech().readOnly()) lines.add(Objects.requireNonNull(Component.translatable("colonycamera.work.reason.tech_data")));
        return lines;
    }
    void render(GuiGraphics g, int mx, int my) {
        Font current = font; if (!open || current == null) return;
        clampScroll(); g.fill(0, 0, width, height, 0x60071019); var r = layout.bounds;
        g.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFF334958);
        g.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.bottom() - 1, 0xF0101C28);
        fitted(g, current, text("title"), r.x() + 8, r.y() + 8, r.width() - 36, 0xE8F1F5);
        button(g, current, layout.close, Objects.requireNonNull(Component.literal("×")), true, mx, my);
        if (!layout.usable) { fitted(g, current, Objects.requireNonNull(Component.translatable("colonycamera.work.small_screen")), r.x() + 4, r.y() + 24, r.width() - 8, 0xFFD379); return; }
        var tech = ColonyWorkClient.tech(); var job = running();
        for (int i = 0; i < 4; i++) {
            var cell = layout.node(i);
            if (i < 3) {
                var next = layout.node(i + 1);
                if (layout.horizontal) g.fill(cell.right(), cell.y() + cell.height() / 2, next.x(), cell.y() + cell.height() / 2 + 1, 0xFF334958);
                else g.fill(cell.x() + 9, cell.bottom(), cell.x() + 10, next.y(), 0xFF334958);
            }
            boolean done = i <= tech.tier(), available = i == tech.nextTier();
            int ink = done ? 0x67DEC2 : available ? 0xFFD379 : 0x586C79;
            g.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), i == selected ? 0xFF234B4B : cell.contains(mx, my) ? 0xFF263B4B : 0xFF192D39);
            g.fill(cell.x() + 4, cell.y() + 4, cell.x() + 7, cell.y() + 7, 0xFF000000 | ink);
            fitted(g, current, tier(i), cell.x() + 10, cell.y() + 4, cell.width() - 14, ink);
            if (cell.height() >= 24) fitted(g, current, text(done ? "done" : available && job != null ? "running" : available ? "available" : "locked"), cell.x() + 5, cell.y() + 16, cell.width() - 10, ink);
            if (available && job != null) {
                int w = Math.max(0, cell.width() - 8);
                g.fill(cell.x() + 4, cell.bottom() - 2, cell.x() + 4 + (int) Math.round(w * StrategyHudLayout.progress(job.done(), job.total())), cell.bottom() - 1, 0xFFFFD379);
            }
        }
        List<Component> lines = details(); int y = layout.detail.y();
        g.enableScissor(layout.detail.x(), layout.detail.y(), layout.detail.right(), layout.detail.bottom());
        for (int i = scroll; i < lines.size() && y + 9 <= layout.detail.bottom(); i++, y += 12)
            fitted(g, current, Objects.requireNonNull(lines.get(i)), layout.detail.x(), y, layout.detail.width(), 0xE8F1F5);
        g.disableScissor();
        var facility = station;
        Component stationLabel = facility == null ? Objects.requireNonNull(Component.translatable("colonycamera.work.need_research_station"))
                : Objects.requireNonNull(Component.translatable("colonycamera.work.station", Component.translatable("colonycamera.work.research_station"), facility.pos().getX(), facility.pos().getY(), facility.pos().getZ()));
        button(g, current, layout.station, stationLabel, true, mx, my);
        button(g, current, layout.research, text(job != null ? "running" : "research"), canResearch(), mx, my);
        button(g, current, layout.refresh, Objects.requireNonNull(Component.translatable("colonycamera.work.refresh")), true, mx, my);
        if (layout.detail.contains(mx, my) || layout.research.contains(mx, my)) {
            List<net.minecraft.util.FormattedCharSequence> wrapped = new ArrayList<>();
            for (Component line : lines) wrapped.addAll(current.split(Objects.requireNonNull(line), Math.max(1, Math.min(260, width - 24))));
            g.renderTooltip(current, wrapped, mx, my);
        } else for (int i = 0; i < 4; i++) if (layout.node(i).contains(mx, my)) g.renderTooltip(current, tier(i), mx, my);
    }
    private static void button(GuiGraphics g, Font font, StrategyHudLayout.Rect r, Component caption, boolean enabled, int mx, int my) {
        g.fill(r.x(), r.y(), r.right(), r.bottom(), enabled && r.contains(mx, my) ? 0xFF263B4B : 0xFF192D39);
        fitted(g, font, caption, r.x() + 4, r.y() + Math.max(0, (r.height() - 9) / 2), r.width() - 8, enabled ? 0xE8F1F5 : 0x586C79);
    }
    private static void fitted(GuiGraphics g, Font font, Component caption, int x, int y, int width, int ink) {
        if (width <= 0) return; String value = Objects.requireNonNull(caption.getString());
        if (font.width(value) > width) value = font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
        g.drawString(font, value, x, y, ink, false);
    }
    @Nonnull private static Component tier(int stage) { return Objects.requireNonNull(Component.translatable("colonycamera.work.tier." + stage)); }
    @Nonnull private static Component text(String key, Object... args) { return Objects.requireNonNull(Component.translatable("screen.colonycamera.technology." + key, args)); }
}
