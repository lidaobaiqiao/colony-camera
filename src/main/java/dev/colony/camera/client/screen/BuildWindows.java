package dev.colony.camera.client.screen;

import dev.colony.camera.client.construction.BuildingClient;
import dev.colony.camera.client.planning.ProjectionController;
import dev.colony.camera.config.SelectionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

/** Owns palette z-order, pointer capture and the window-to-scene draft handoff. */
@ParametersAreNonnullByDefault
final class BuildWindows {
    private static final int MAX_WINDOWS = 8;
    private final List<BuildPanel> windows = new ArrayList<>();
    @Nullable private BuildPanel active, owner, gesture;
    @Nullable private Font font;
    @Nullable private ClientLevel draftLevel;
    private int width = 1, height = 1, bottom = 1;
    private double startX, startY;
    private boolean resizing, visible, preferencesLoaded;
    private StrategyHudLayout.Rect startBounds = new StrategyHudLayout.Rect(0, 0, 1, 1);
    private StrategyHudLayout.Rect strip = new StrategyHudLayout.Rect(0, 0, 0, 0);

    void resize(Font value, int w, int h, int bottom, StrategyHudLayout.Rect placement) {
        cancelGesture(); font = value; strip = placement;
        int oldWidth = width, oldBottom = this.bottom;
        width = w; height = h; this.bottom = Math.max(1, bottom);
        for (var panel : windows) {
            var r = WindowGeometry.decode(WindowGeometry.encode(panel.layout().panel, oldWidth, oldBottom), width, this.bottom);
            panel.resize(value, w, h, this.bottom, r);
        }
        ProjectionController.instance().blockPlacedListener(this::accepted);
    }
    boolean isOpen() { return visible; }
    boolean capturesKeyboard() { return visible && active != null && active.capturesKeyboard(); }
    boolean dragging() { return gesture != null; }
    void toggle() { if (visible) close(); else open(); }
    void open() {
        checkLevel();
        if (windows.isEmpty()) {
            preferencesLoaded = true;
            var saved = SelectionConfig.buildWindows();
            for (String rectangle : saved.stream().limit(MAX_WINDOWS).toList()) {
                try { addWindow(WindowGeometry.decode(Objects.requireNonNull(rectangle), width, bottom)); }
                catch (IllegalArgumentException invalid) { /* Ignore only the invalid layout preference. */ }
            }
            if (windows.isEmpty()) addWindow(BuildPanelLayout.defaultBounds(width, height, bottom));
        }
        visible = true;
        for (var panel : windows) panel.open();
        BuildPanel selected = active;
        if (selected != null) { activate(selected); selected.blur(); }
    }
    void close() {
        releaseDraft(); cancelGesture(); visible = false;
        for (var panel : windows) panel.close();
        persist();
    }
    void dispose() { releaseDraft(); cancelGesture(); persist(); ProjectionController.instance().blockPlacedListener(null); }
    void releaseDraft() {
        var p = ProjectionController.instance();
        BuildPanel previous = owner; if (previous != null) previous.savedDraft = p.blockDraftState();
        owner = null;
        if (p.blockDraft()) p.restoreBlockDraft(null);
        blur();
    }
    void blur() { for (var panel : windows) panel.blur(); }
    void cancelGesture() { gesture = null; }
    private void addWindow(StrategyHudLayout.Rect rectangle) {
        Font current = font; if (current == null || windows.size() >= MAX_WINDOWS) return;
        BuildPanel panel = new BuildPanel(); panel.resize(current, width, height, bottom, rectangle); panel.open();
        windows.add(panel); activate(panel); visible = true;
    }
    private void activate(BuildPanel panel) {
        checkLevel();
        blur();
        if (owner != panel) {
            var p = ProjectionController.instance();
            BuildPanel previous = owner; if (previous != null) previous.savedDraft = p.blockDraftState();
            p.cancel(); p.restoreBlockDraft(panel.savedDraft); owner = panel;
        }
        active = panel; windows.remove(panel); windows.add(panel);
    }
    private void accepted(UUID id) {
        for (var panel : windows) {
            var state = panel.savedDraft;
            if (panel == owner) panel.savedDraft = ProjectionController.instance().blockDraftState();
            else if (state != null) panel.savedDraft = state.accepted(Objects.requireNonNull(id));
        }
    }
    private void checkLevel() {
        var current = Minecraft.getInstance().level;
        if (current == draftLevel) return;
        for (var panel : windows) panel.savedDraft = null;
        owner = null; draftLevel = current;
        if (ProjectionController.instance().blockDraft()) ProjectionController.instance().cancel();
    }
    void tick() { checkLevel(); if (visible) for (var panel : windows) panel.tick(); }
    boolean blocks(double x, double y) {
        if (gesture != null) return true;
        if (visible) for (var panel : windows) if (panel.blocks(x, y)) return true;
        return ProjectionController.instance().blockDraft() && strip.contains(x, y);
    }
    boolean keyPressed(int key, int scan, int modifiers) {
        BuildPanel panel = active;
        if (!visible || panel == null) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        // An unfocused palette must not steal the active draft's placement keys.
        return panel.capturesKeyboard() && panel.keyPressed(key, scan, modifiers);
    }
    boolean charTyped(char value, int modifiers) { return visible && active != null && active.charTyped(value, modifiers); }
    boolean click(double x, double y, int button) {
        if (ProjectionController.instance().blockDraft() && strip.contains(x, y)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) for (int i = 0; i < 5; i++) if (stripControl(i).contains(x, y)) {
                var p = ProjectionController.instance();
                switch (i) { case 0 -> toggle(); case 1 -> p.rotate(); case 2 -> p.move(0, 1, 0); case 3 -> p.move(0, -1, 0); case 4 -> { p.cancel(); BuildPanel current = owner; if (current != null) current.savedDraft = null; } default -> { } }
                break;
            }
            return true;
        }
        if (!visible) return false;
        for (int i = windows.size() - 1; i >= 0; i--) {
            BuildPanel panel = windows.get(i); if (!panel.blocks(x, y)) continue;
            activate(panel);
            var layout = panel.layout();
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (layout.newWindow.contains(x, y)) {
                    addWindow(WindowGeometry.clamp(new StrategyHudLayout.Rect(layout.panel.x() + 22, layout.panel.y() + 18, layout.panel.width(), layout.panel.height()), width, bottom));
                    persist(); return true;
                }
                if (layout.close.contains(x, y)) {
                    if (windows.size() == 1) { close(); return true; }
                    releaseDraft(); panel.close(); windows.remove(panel);
                    active = windows.isEmpty() ? null : windows.get(windows.size() - 1);
                    if (active != null) activate(active); else visible = false;
                    persist(); return true;
                }
                if (layout.resize.contains(x, y) || layout.title.contains(x, y)) {
                    gesture = panel; resizing = layout.resize.contains(x, y);
                    startX = x; startY = y; startBounds = layout.panel; return true;
                }
            }
            return panel.click(x, y, button);
        }
        blur(); return false;
    }
    boolean drag(double x, double y, int button) {
        BuildPanel panel = gesture;
        if (panel == null) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int dx = (int) Math.round(x - startX), dy = (int) Math.round(y - startY);
            panel.bounds(WindowGeometry.clamp(new StrategyHudLayout.Rect(startBounds.x() + (resizing ? 0 : dx),
                    startBounds.y() + (resizing ? 0 : dy), startBounds.width() + (resizing ? dx : 0),
                    startBounds.height() + (resizing ? dy : 0)), width, bottom));
        }
        return true;
    }
    boolean release(int button) {
        if (gesture == null) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) { cancelGesture(); persist(); }
        return true;
    }
    boolean scroll(double x, double y, double delta) {
        if (!visible) return false;
        for (int i = windows.size() - 1; i >= 0; i--) if (windows.get(i).scroll(x, y, delta)) return true;
        return false;
    }
    private void persist() {
        if (!preferencesLoaded) return;
        try {
            SelectionConfig.buildWindows(windows.stream().map(panel -> WindowGeometry.encode(panel.layout().panel, width, bottom)).toList());
        } catch (RuntimeException unavailable) { /* Geometry remains usable for this session if the config cannot be saved. */ }
    }
    private StrategyHudLayout.Rect stripControl(int index) {
        int size = Math.min(20, strip.width() / 5), inset = Math.min(4, strip.height() / 2);
        return new StrategyHudLayout.Rect(strip.right() - (5 - index) * size, strip.y() + inset, size, Math.max(0, strip.height() - 2 * inset));
    }
    void render(GuiGraphics g, int mx, int my, float partialTick) {
        if (visible) for (int i = 0; i < windows.size(); i++) {
            var panel = windows.get(i);
            boolean covered = false;
            for (int j = i + 1; j < windows.size(); j++) covered |= windows.get(j).blocks(mx, my);
            panel.render(g, covered ? -10000 : mx, covered ? -10000 : my, partialTick);
        }
        Font current = font; var p = ProjectionController.instance();
        if (current == null || !p.blockDraft()) return;
        StrategyHud.panel(g, strip);
        String id = p.blockItem();
        var resource = id == null ? null : ResourceLocation.tryParse(id);
        var item = resource == null ? null : ForgeRegistries.ITEMS.getValue(resource);
        Component name = Objects.requireNonNull(item == null ? Component.literal(id == null ? "" : id) : item.getDescription());
        Component caption = Objects.requireNonNull(Component.translatable("screen.colonycamera.build.placing", name));
        StrategyHud.drawFitted(g, current, BuildingClient.recentNotice() ? BuildingClient.notice() : caption,
                strip.x() + 5, strip.y() + 9, stripControl(0).x() - strip.x() - 9, UiTheme.ACCENT);
        String[] symbols = {"▦", "↻", "+", "−", "×"};
        String[] labels = {"palette", "rotate", "up", "down", "cancel"};
        for (int i = 0; i < 5; i++) {
            var r = stripControl(i);
            if (r.contains(mx, my)) g.fill(r.x(), r.y(), r.right(), r.bottom(), UiTheme.RAISED);
            StrategyHud.drawFitted(g, current, Objects.requireNonNull(Component.literal(Objects.requireNonNull(symbols[i]))), r.x() + 4, r.y() + 5, r.width() - 4, UiTheme.TEXT);
            if (r.contains(mx, my)) {
                List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
                lines.addAll(current.split(caption, Math.max(1, Math.min(260, width - 24))));
                lines.addAll(current.split(Objects.requireNonNull(Component.translatable("screen.colonycamera.build." + labels[i])), Math.max(1, Math.min(260, width - 24))));
                if (BuildingClient.recentNotice()) lines.addAll(current.split(BuildingClient.notice(), Math.max(1, Math.min(260, width - 24))));
                g.renderTooltip(current, lines, mx, my);
            }
        }
    }
}
