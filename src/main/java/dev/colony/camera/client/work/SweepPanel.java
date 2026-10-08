package dev.colony.camera.client.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.screen.RegistryPicker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class SweepPanel {
    @Nonnull private final RegistryPicker picker = new RegistryPicker();
    @Nonnull private Set<String> types = Objects.requireNonNull(Set.of());
    @Nullable private Font font;
    private int x, y, width, height;
    private boolean open;
    public boolean isOpen() { return open; }
    public void resize(Font value, int w, int h) { font = value; width = Math.max(1, Math.min(290, w - 16)); height = Math.max(1, Math.min(130, h - 16)); x = (w - width) / 2; y = (h - height) / 2; picker.resize(value, w, h); }
    public void open() { open = true; picker.close(); }
    public void close() { open = false; picker.close(); }
    public void tick() { picker.tick(); }
    public boolean keyPressed(int key, int scan, int modifiers) { if (!open) return false; if (picker.keyPressed(key, scan, modifiers)) return true; if (key == GLFW.GLFW_KEY_ESCAPE) close(); return true; }
    public boolean charTyped(char ch, int modifiers) { if (!open) return false; picker.charTyped(ch, modifiers); return true; }
    public boolean scroll(double delta) { if (!open) return false; picker.scroll(delta); return true; }
    private static boolean selected() { var selection = StrategyCameraController.instance().selection(); return selection.selectionCount() > 0 && !selection.working() && !selection.selectingHeight(); }
    public boolean click(double mx, double my, int button) {
        if (!open) return false; if (picker.click(mx, my, button)) return true; if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (mx >= x + width - 24 && mx < x + width && my >= y && my < y + 24) { close(); return true; }
        if (width < 180 || height < 115 || mx < x + 8 || mx >= x + width - 8) return true;
        if (my >= y + 33 && my < y + 57 && selected()) { ColonyWorkClient.sweep(); close(); }
        else if (my >= y + 63 && my < y + 87) picker.items(types, chosen -> {
            types = Objects.requireNonNull(Set.copyOf(chosen)); JsonArray items = new JsonArray(); chosen.stream().sorted().forEach(items::add);
            JsonObject payload = new JsonObject(); payload.add("items", items); ColonyWorkClient.request("sweep_items", payload); open = false;
        });
        return true;
    }
    public void render(GuiGraphics g, int mx, int my) {
        Font current = font; if (!open || current == null) return;
        if (picker.isOpen()) { picker.render(g, mx, my); return; }
        g.fill(x, y, x + width, y + height, 0xFF182D39); g.fill(x, y, x + width, y + 2, 0xFF67DEC2);
        draw(g, current, text("title"), x + 8, y + 10, width - 40, 0xE8F1F5); g.drawString(current, "×", x + width - 17, y + 9, 0x90A8B8, false);
        if (width < 180 || height < 115) return;
        for (int row = 0; row < 2; row++) { int top = y + 33 + row * 30; boolean enabled = row == 1 || selected(); g.fill(x + 8, top, x + width - 8, top + 24, enabled ? 0xFF28564E : 0xFF263844); draw(g, current, text(row == 0 ? "surface" : "items"), x + 14, top + 8, width - 28, enabled ? 0xE8F1F5 : 0x617B8D); }
        draw(g, current, text("scope"), x + 8, y + 99, width - 16, 0x90A8B8);
    }
    private static void draw(GuiGraphics g, Font font, Component value, int x, int y, int width, int color) { if (width > 0) g.drawString(font, Objects.requireNonNull(font.plainSubstrByWidth(Objects.requireNonNull(value.getString()), width)), x, y, color, false); }
    @Nonnull private static Component text(String key) { return Objects.requireNonNull(Component.translatable("colonycamera.sweep.ui." + key)); }
}
