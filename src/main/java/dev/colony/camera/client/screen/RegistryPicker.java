package dev.colony.camera.client.screen;

import dev.colony.camera.client.selection.TypeSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/** Shared, bounded modal picker. Names and icons use the native registry and font. */
@ParametersAreNonnullByDefault
public final class RegistryPicker {
    public record Entry(@Nonnull String id, @Nonnull Component name, @Nonnull ItemStack icon, int inventorySlot) {}
    @Nonnull private final List<Entry> entries = new ArrayList<>();
    @Nonnull private List<Entry> visible = Objects.requireNonNull(List.of());
    @Nonnull private final Set<String> selected = new LinkedHashSet<>();
    @Nullable private Consumer<Set<String>> onApply;
    @Nullable private Consumer<Entry> onPick;
    @Nullable private Font font;
    @Nullable private EditBox search;
    @Nonnull private String lastQuery = "", title = "";
    private int x, y, width, height, page;
    private boolean open, requireSelection;
    public boolean isOpen() { return open; }
    public void resize(Font newFont, int w, int h) {
        font = newFont; width = Math.max(1, Math.min(332, w - 16)); height = Math.max(1, Math.min(240, h - 16));
        x = Math.max(0, (w - width) / 2); y = Math.max(0, (h - height) / 2);
        String query = search == null ? "" : Objects.requireNonNull(search.getValue());
        EditBox field = new EditBox(newFont, x + 8, y + 29, Math.max(1, width - 16), 18, text("search"));
        field.setMaxLength(128); field.setValue(query); search = field;
    }
    public void blocks(Set<String> initial, Consumer<Set<String>> callback) {
        begin("blocks", false); selected.addAll(initial); onApply = callback;
        for (var block : ForgeRegistries.BLOCKS.getValues()) if (!block.defaultBlockState().isAir()) {
            String id = Objects.requireNonNull(String.valueOf(ForgeRegistries.BLOCKS.getKey(block))); ItemStack icon = new ItemStack(block);
            entries.add(new Entry(id, Objects.requireNonNull(block.getName()), icon.isEmpty() ? new ItemStack(Objects.requireNonNull(Items.BARRIER)) : icon, -1));
        }
        sort();
    }
    public void items(Set<String> initial, Consumer<Set<String>> callback) {
        begin("items", true); selected.addAll(initial); onApply = callback;
        for (var item : ForgeRegistries.ITEMS.getValues()) if (item != Items.AIR) entries.add(new Entry(Objects.requireNonNull(String.valueOf(ForgeRegistries.ITEMS.getKey(item))), Objects.requireNonNull(item.getDescription()), new ItemStack(item), -1));
        sort();
    }
    public void equipment(EquipmentSlot slot, Consumer<Entry> callback) {
        begin("equipment", true); onPick = callback; var player = Minecraft.getInstance().player;
        if (player == null) return;
        for (int index = 0; index < 36; index++) {
            ItemStack stack = Objects.requireNonNull(player.getInventory().getItem(index));
            if (!stack.isEmpty() && (slot == EquipmentSlot.OFFHAND || stack.canEquip(slot, player)))
                entries.add(new Entry("slot:" + index, Objects.requireNonNull(stack.getHoverName()), Objects.requireNonNull(stack.copy()), index));
        }
        sort();
    }
    private void begin(String key, boolean required) {
        entries.clear(); selected.clear(); onApply = null; onPick = null; page = 0; title = key; open = true; requireSelection = required;
        EditBox field = search; if (field != null) { field.setValue(""); field.setFocused(true); } lastQuery = "\u0000";
    }
    private void sort() { entries.sort(Comparator.comparing(entry -> entry.name().getString())); filter(); }
    private void filter() {
        String query = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        if (query.equals(lastQuery)) return; lastQuery = query; page = 0;
        visible = Objects.requireNonNull(entries.stream().filter(entry -> entry.id().toLowerCase(Locale.ROOT).contains(query) || entry.name().getString().toLowerCase(Locale.ROOT).contains(query)).toList());
    }
    public void close() { open = false; onApply = null; onPick = null; }
    public void tick() { if (open) { EditBox field = search; if (field != null) field.tick(); filter(); } }
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!open) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) close(); else { EditBox field = search; if (field != null) field.keyPressed(key, scan, modifiers); filter(); }
        return true;
    }
    public boolean charTyped(char character, int modifiers) {
        if (!open) return false; EditBox field = search; if (field != null) field.charTyped(character, modifiers); filter(); return true;
    }
    public boolean scroll(double delta) { if (!open) return false; if (Double.isFinite(delta)) page = Math.max(0, Math.min(pages() - 1, page - (int) Math.signum(delta))); return true; }
    private int columns() { return Math.max(1, (width - 16) / 26); }
    private int rows() { return Math.max(1, (height - 100) / 26); }
    private int capacity() { return columns() * rows(); }
    private int pages() { return Math.max(1, (visible.size() + capacity() - 1) / capacity()); }
    public boolean click(double mx, double my, int button) {
        if (!open) return false; if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (mx >= x + width - 24 && mx < x + width && my >= y && my < y + 23) { close(); return true; }
        if (width < 180 || height < 140) return true;
        EditBox field = search; if (field != null && field.mouseClicked(mx, my, button)) return true;
        if (mx >= x + 8 && mx < x + 8 + columns() * 26 && my >= y + 58 && my < y + 58 + rows() * 26) {
            int index = page * capacity() + (int) ((my - y - 58) / 26) * columns() + (int) ((mx - x - 8) / 26);
            if (index < visible.size()) {
                Entry entry = Objects.requireNonNull(visible.get(index)); Consumer<Entry> callback = onPick;
                if (callback != null) { callback.accept(entry); close(); }
                else if (!selected.remove(entry.id()) && selected.size() < TypeSelection.MAX_TYPES) selected.add(entry.id());
            }
        } else if (my >= y + height - 26 && my < y + height - 6) {
            if (mx >= x + width - 76 && mx < x + width - 8) {
                Consumer<Set<String>> callback = onApply;
                if (callback != null && (!requireSelection || !selected.isEmpty())) { callback.accept(Objects.requireNonNull(Set.copyOf(selected))); close(); }
            } else if (mx >= x + 8 && mx < x + 57) selected.clear();
            else if (mx >= x + 63 && mx < x + 87) page = Math.max(0, page - 1);
            else if (mx >= x + 92 && mx < x + 116) page = Math.min(pages() - 1, page + 1);
        }
        return true;
    }
    public void render(GuiGraphics graphics, int mx, int my) {
        Font current = font; if (!open || current == null) return; filter();
        graphics.fill(x, y, x + width, y + height, 0xFF182D39); graphics.fill(x, y, x + width, y + 2, UiTheme.ACCENT);
        draw(graphics, current, text(title), x + 8, y + 10, width - 35, UiTheme.TEXT); graphics.drawString(current, "×", x + width - 17, y + 9, UiTheme.SECONDARY, false);
        if (width < 180 || height < 140) { draw(graphics, current, text("small"), x + 8, y + 30, width - 16, UiTheme.WARNING); return; }
        EditBox field = search; if (field != null) field.render(graphics, mx, my, 0);
        Entry hovered = null;
        for (int cell = 0; cell < capacity() && page * capacity() + cell < visible.size(); cell++) {
            Entry entry = Objects.requireNonNull(visible.get(page * capacity() + cell)); int left = x + 8 + cell % columns() * 26, top = y + 58 + cell / columns() * 26;
            boolean hover = mx >= left && mx < left + 25 && my >= top && my < top + 25;
            graphics.fill(left, top, left + 25, top + 25, selected.contains(entry.id()) ? 0xFF347667 : hover ? 0xFF3B5060 : 0xFF263D4C);
            graphics.renderItem(entry.icon(), left + 4, top + 4); if (selected.contains(entry.id())) graphics.drawString(current, "✓", left + 16, top + 16, UiTheme.ACCENT, false);
            if (hover) hovered = entry;
        }
        draw(graphics, current, text(onPick == null ? selected.isEmpty() && !requireSelection ? "all" : "count" : "inventory", selected.size(), TypeSelection.MAX_TYPES), x + 8, y + height - 40, width - 16, UiTheme.SECONDARY);
        button(graphics, current, text("clear"), x + 8, y + height - 26, 49, onPick == null);
        button(graphics, current, Objects.requireNonNull(Component.literal("‹")), x + 63, y + height - 26, 24, page > 0);
        button(graphics, current, Objects.requireNonNull(Component.literal("›")), x + 92, y + height - 26, 24, page + 1 < pages());
        draw(graphics, current, Objects.requireNonNull(Component.literal((page + 1) + "/" + pages())), x + 121, y + height - 20, Math.max(1, width - 205), UiTheme.SECONDARY);
        button(graphics, current, text("apply"), x + width - 76, y + height - 26, 68, onApply != null && (!requireSelection || !selected.isEmpty()));
        if (hovered != null) graphics.renderTooltip(current, Objects.requireNonNull(List.of(hovered.name(), Objects.requireNonNull(Component.literal(hovered.id())))), Objects.requireNonNull(java.util.Optional.empty()), mx, my);
    }
    private static void button(GuiGraphics graphics, Font font, Component label, int x, int y, int width, boolean enabled) {
        graphics.fill(x, y, x + width, y + 20, enabled ? 0xFF28564E : 0xFF243844); draw(graphics, font, label, x + 4, y + 6, width - 8, enabled ? UiTheme.TEXT : 0x617B8D);
    }
    private static void draw(GuiGraphics graphics, Font font, Component value, int x, int y, int width, int colour) {
        if (width > 0) graphics.drawString(font, Objects.requireNonNull(font.plainSubstrByWidth(Objects.requireNonNull(value.getString()), width)), x, y, colour, false);
    }
    @Nonnull private static Component text(String key, Object... args) { return Objects.requireNonNull(Component.translatable("colonycamera.picker." + key, args)); }
}
