package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.UiTheme;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/** Real room inspection and explicit resident selection; an empty house is never a print gate. */
@ParametersAreNonnullByDefault
public final class ResidencePanel {
    @Nullable private Font font;
    @Nullable private EditBox name;
    @Nullable private BlockPos selected;
    @Nullable private UUID residentChoice;
    private boolean open;
    private int x, y, width, height, scroll;
    public boolean isOpen() { return open; }
    public void resize(Font value, int w, int h) {
        font = value; width = Math.max(1, Math.min(390, w - 16)); height = Math.max(1, Math.min(234, h - 16)); x = (w - width) / 2; y = (h - height) / 2;
        String previous = name == null ? "" : Objects.requireNonNull(name.getValue()); EditBox field = new EditBox(value, detailX(), y + 30, Math.max(1, detailWidth() - 53), 18, text("name"));
        field.setMaxLength(64); field.setValue(previous); name = field;
    }
    public void open(@Nullable BlockPos marker) { open = true; selected = null; scroll = 0; choose(marker); ColonyWorkClient.refresh(); }
    public void close() { open = false; }
    public void tick() { EditBox field = name; if (open && field != null) field.tick(); if (open) ensureSelected(); }
    public boolean keyPressed(int key, int scan, int modifiers) { if (!open) return false; if (key == GLFW.GLFW_KEY_ESCAPE) close(); else { EditBox field = name; if (field != null) field.keyPressed(key, scan, modifiers); } return true; }
    public boolean charTyped(char ch, int modifiers) { if (!open) return false; EditBox field = name; if (field != null) field.charTyped(ch, modifiers); return true; }
    public boolean scroll(double delta) { if (!open) return false; if (Double.isFinite(delta)) scroll = Math.max(0, Math.min(Math.max(0, rooms().size() - rows()), scroll - (int) Math.signum(delta))); return true; }
    private int detailX() { return x + 8 + Math.max(0, width - 24) / 3 + 8; }
    private int detailWidth() { return Math.max(1, x + width - 8 - detailX()); }
    private int rows() { return Math.max(1, (height - 81) / 26); }
    private int choiceTop() { return y + (height < 190 ? 93 : 107); }
    @Nonnull private List<ColonyWorkClient.Facility> rooms() { return Objects.requireNonNull(ColonyWorkClient.facilities().stream().filter(facility -> facility.kind().equals("RESIDENCE")).toList()); }
    @Nullable private ColonyWorkClient.Facility current() { return rooms().stream().filter(facility -> facility.pos().equals(selected)).findFirst().orElse(null); }
    private void ensureSelected() { if (current() == null && !rooms().isEmpty()) choose(rooms().get(0).pos()); }
    private void choose(@Nullable BlockPos pos) {
        selected = pos; var current = current(); EditBox field = name;
        if (field != null) field.setValue(current == null ? "" : word(current.detail(), "name", ""));
        residentChoice = current != null && current.detail().has("resident") ? UUID.fromString(word(current.detail(), "resident", "")) : null;
    }
    public boolean click(double mx, double my, int button) {
        if (!open) return false; if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true; ensureSelected();
        if (mx >= x + width - 24 && mx < x + width && my >= y && my < y + 24) { close(); return true; }
        if (width < 250 || height < 168) return true;
        if (mx >= x + 8 && mx < detailX() - 8 && my >= y + 30 && my < y + 30 + rows() * 26) {
            int row = scroll + (int) ((my - y - 30) / 26); var list = rooms(); if (row < list.size()) choose(list.get(row).pos()); return true;
        }
        var facility = current(); if (facility == null) return true;
        EditBox field = name; if (field != null && field.mouseClicked(mx, my, button)) return true;
        if (mx < detailX() || mx >= x + width - 8) return true;
        boolean writable = !flag(facility.detail(), "readOnly");
        if (my >= y + 30 && my < y + 49 && mx >= x + width - 57 && writable) { JsonObject request = intent("name"); request.addProperty("name", field == null ? "" : field.getValue()); ColonyWorkClient.request("residence", request); }
        else if (my >= choiceTop() && my < choiceTop() + 19) {
            var workers = ColonyWorkClient.workers(); if (!workers.isEmpty()) { int index = -1; for (int i = 0; i < workers.size(); i++) if (workers.get(i).id().equals(residentChoice)) index = i; residentChoice = workers.get((index + 1) % workers.size()).id(); }
        } else if (my >= y + height - 48 && my < y + height - 29 && writable) {
            JsonObject request = intent("automatic"); request.addProperty("enabled", !flag(facility.detail(), "automatic")); ColonyWorkClient.request("residence", request);
        } else if (my >= y + height - 25 && my < y + height - 6 && writable) {
            UUID chosenResident = residentChoice;
            if (mx < detailX() + detailWidth() / 2 && facility.valid() && chosenResident != null) { JsonObject request = intent("assign"); request.addProperty("worker", chosenResident.toString()); ColonyWorkClient.request("residence", request); }
            else if (mx >= detailX() + detailWidth() / 2 && facility.occupied()) ColonyWorkClient.request("residence", intent("release"));
        }
        return true;
    }
    @Nonnull private JsonObject intent(String operation) { JsonObject value = new JsonObject(); value.addProperty("pos", Objects.requireNonNull(selected).asLong()); value.addProperty("operation", operation); return value; }
    public void render(GuiGraphics g, int mx, int my) {
        Font currentFont = font; if (!open || currentFont == null) return; ensureSelected();
        g.fill(x, y, x + width, y + height, 0xFF182D39); g.fill(x, y, x + width, y + 2, UiTheme.ACCENT);
        draw(g, currentFont, text("title"), x + 8, y + 10, width - 40, UiTheme.TEXT); g.drawString(currentFont, "×", x + width - 17, y + 9, UiTheme.SECONDARY, false);
        if (width < 250 || height < 168) { draw(g, currentFont, text("small"), x + 8, y + 32, width - 16, UiTheme.WARNING); return; }
        var list = rooms();
        for (int row = 0; row < rows() && row + scroll < list.size(); row++) {
            var facility = Objects.requireNonNull(list.get(row + scroll)); int top = y + 30 + row * 26;
            g.fill(x + 8, top, detailX() - 8, top + 24, facility.pos().equals(selected) ? 0xFF28564E : 0xFF263D4C);
            String title = word(facility.detail(), "name", ""); draw(g, currentFont, title.isBlank() ? text("unnamed") : Objects.requireNonNull(Component.literal(title)), x + 12, top + 3, detailX() - x - 24, UiTheme.TEXT);
            draw(g, currentFont, position(facility.pos()), x + 12, top + 13, detailX() - x - 24, facility.valid() ? UiTheme.ACCENT : UiTheme.WARNING);
        }
        draw(g, currentFont, ColonyWorkClient.notice(), x + 8, y + height - 40, Math.max(1, detailX() - x - 24), UiTheme.WARNING);
        var facility = current(); if (facility == null) { draw(g, currentFont, text("empty"), detailX(), y + 35, detailWidth(), UiTheme.SECONDARY); return; }
        JsonObject detail = facility.detail(); boolean writable = !flag(detail, "readOnly");
        EditBox field = name; if (field != null) { field.setEditable(writable); field.render(g, mx, my, 0); }
        button(g, currentFont, text("save"), x + width - 53, y + 30, 45, writable);
        draw(g, currentFont, text("reason." + word(detail, "reason", "unloaded")), detailX(), y + 54, detailWidth(), facility.valid() ? UiTheme.ACCENT : UiTheme.WARNING);
        draw(g, currentFont, text("area", detail.has("area") ? detail.get("area").getAsInt() : 0), detailX(), y + 68, detailWidth(), UiTheme.SECONDARY);
        if (height >= 190) draw(g, currentFont, text("bed", detail.has("bed") ? position(Objects.requireNonNull(BlockPos.of(detail.get("bed").getAsLong()))) : text("none")), detailX(), y + 81, detailWidth(), UiTheme.SECONDARY);
        draw(g, currentFont, text("resident", detail.has("resident") ? shortId(word(detail, "resident", "")) : text("none")), detailX(), y + (height < 190 ? 80 : 94), detailWidth(), UiTheme.TEXT);
        UUID chosenResident = residentChoice;
        button(g, currentFont, text("choose", chosenResident == null ? text("none") : shortId(Objects.requireNonNull(chosenResident.toString()))), detailX(), choiceTop(), detailWidth(), !ColonyWorkClient.workers().isEmpty());
        button(g, currentFont, text("automatic", text(flag(detail, "automatic") ? "on" : "off")), detailX(), y + height - 48, detailWidth(), writable);
        int half = detailWidth() / 2; button(g, currentFont, text("assign"), detailX(), y + height - 25, half - 2, writable && facility.valid() && residentChoice != null);
        button(g, currentFont, text("release"), detailX() + half + 2, y + height - 25, detailWidth() - half - 2, writable && facility.occupied());
        if (mx >= detailX() && my >= y + 54 && my < y + 80) g.renderTooltip(currentFont, text("rules"), mx, my);
        if (mx >= x && mx < x + width && my >= y + height - 25 && my < y + height) g.renderTooltip(currentFont, ColonyWorkClient.notice(), mx, my);
    }
    @Nonnull private static Component shortId(String id) { return Objects.requireNonNull(Component.literal(Objects.requireNonNull(id.substring(0, Math.min(8, id.length()))))); }
    @Nonnull private static Component position(BlockPos pos) { return Objects.requireNonNull(Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())); }
    private static boolean flag(JsonObject value, String key) { return value.has(key) && value.get(key).getAsBoolean(); }
    @Nonnull private static String word(JsonObject value, String key, String fallback) { return value.has(key) ? Objects.requireNonNull(value.get(key).getAsString()) : fallback; }
    @Nonnull private static Component text(String key, Object... args) { return Objects.requireNonNull(Component.translatable("colonycamera.residence.ui." + key, args)); }
    private static void button(GuiGraphics g, Font font, Component label, int x, int y, int width, boolean enabled) { if (width <= 0) return; g.fill(x, y, x + width, y + 19, enabled ? 0xFF28564E : 0xFF243844); draw(g, font, label, x + 4, y + 6, width - 8, enabled ? UiTheme.TEXT : 0x617B8D); }
    private static void draw(GuiGraphics g, Font font, Component value, int x, int y, int width, int color) { if (width > 0) g.drawString(font, Objects.requireNonNull(font.plainSubstrByWidth(Objects.requireNonNull(value.getString()), width)), x, y, color, false); }
}
