package dev.colony.camera.client.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.client.screen.StrategyHudLayout.Rect;
import dev.colony.camera.work.WorkNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Native recipe registry and item icons; only the server decides what a real station can produce. */
@Mod.EventBusSubscriber(modid = ColonyCamera.ID, value = Dist.CLIENT)
@ParametersAreNonnullByDefault
public final class CraftingPanel {
    private record Entry(@Nonnull CraftingRecipe recipe, @Nonnull String id, @Nonnull String category,
                         @Nonnull ItemStack output, @Nonnull String name, @Nonnull String search) {}
    @Nullable private static CraftingPanel active;
    @Nonnull private CraftingPanelLayout layout = new CraftingPanelLayout(1, 1);
    @Nullable private Font font;
    @Nullable private EditBox input;
    @Nullable private ClientLevel level;
    @Nullable private BlockPos station;
    @Nonnull private List<Entry> entries = Objects.requireNonNull(List.of()), filtered = Objects.requireNonNull(List.of());
    @Nonnull private final Map<String, String> reasons = new HashMap<>();
    @Nonnull private final Map<String, Integer> checked = new HashMap<>();
    @Nonnull private String category = "ALL", selected = "", query = "";
    private boolean open, craftableOnly;
    private int page, amount = 1, ticks, request, requestedAt = -100;
    private static final String[] CATEGORIES = {"ALL", "BUILDING", "EQUIPMENT", "REDSTONE", "MISC"};
    public boolean isOpen() { return open; }
    public void resize(Font currentFont, int width, int height) {
        font = currentFont; layout = new CraftingPanelLayout(width, height);
        EditBox previous = input;
        if (!layout.usable) { input = null; return; }
        var rect = layout.search;
        EditBox field = new EditBox(currentFont, rect.x(), rect.y(), rect.width(), rect.height(), text("search"));
        field.setMaxLength(128); field.setHint(text("search")); field.setValue(query);
        field.setFocused(previous != null && previous.isFocused());
        field.setResponder(value -> { query = Objects.requireNonNull(value); page = 0; filter(); requestedAt = -100; }); input = field;
    }
    public void open(@Nullable BlockPos preferred) {
        CraftingPanel previous = active; if (previous != null && previous != this) previous.close();
        level = Minecraft.getInstance().level; station = preferred; open = true; active = this;
        reasons.clear(); checked.clear(); ticks = 0; requestedAt = -100; request++; rebuildRecipes();
    }
    public void close() { open = false; if (active == this) active = null; EditBox field = input; if (field != null) field.setFocused(false); }
    public void tick() {
        if (!open) return;
        if (Minecraft.getInstance().level != level || level == null) { close(); return; }
        ticks++;
        if (ticks % 100 == 0) rebuildRecipes();
        chooseStation();
        if (layout.usable && station != null && ticks - requestedAt >= 20) refresh();
    }
    private void rebuildRecipes() {
        ClientLevel current = level; if (current == null) return;
        List<Entry> values = new ArrayList<>();
        for (CraftingRecipe recipe : current.getRecipeManager().getAllRecipesFor(Objects.requireNonNull(RecipeType.CRAFTING))) {
            ItemStack output = Objects.requireNonNull(recipe.getResultItem(Objects.requireNonNull(current.registryAccess())).copy());
            String id = Objects.requireNonNull(recipe.getId().toString());
            String name = Objects.requireNonNull(output.isEmpty() ? id : output.getHoverName().getString());
            values.add(new Entry(Objects.requireNonNull(recipe), id, Objects.requireNonNull(recipe.category().name()), output, name,
                    Objects.requireNonNull((name + " " + id).toLowerCase(Locale.ROOT))));
        }
        values.sort(Comparator.comparing((Entry entry) -> Objects.requireNonNull(entry).name()).thenComparing(entry -> Objects.requireNonNull(entry).id()));
        entries = Objects.requireNonNull(List.copyOf(values)); filter();
    }
    private void filter() {
        String search = query.toLowerCase(Locale.ROOT).trim();
        filtered = Objects.requireNonNull(entries.stream().filter(entry -> (category.equals("ALL") || entry.category().equals(category))
                && (search.isEmpty() || entry.search().contains(search)) && (!craftableOnly || "ready".equals(reasons.get(entry.id())))).toList());
        page = Math.max(0, Math.min(page, pages() - 1));
        if (selected.isEmpty() && !filtered.isEmpty()) selected = filtered.get(0).id();
    }
    private int pages() { return Math.max(1, (filtered.size() + Math.max(1, layout.pageSize()) - 1) / Math.max(1, layout.pageSize())); }
    @Nullable private Entry chosen() { return entries.stream().filter(entry -> entry.id().equals(selected)).findFirst().orElse(null); }
    @Nonnull private List<Entry> visible() {
        int start = Math.min(filtered.size(), page * layout.pageSize()); return Objects.requireNonNull(filtered.subList(start, Math.min(filtered.size(), start + layout.pageSize())));
    }
    private void chooseStation() {
        if (station != null) return;
        for (var value : ColonyWorkClient.stations()) if (!value.research()) { station = value.pos(); break; }
    }
    private void refresh() {
        BlockPos current = station; if (current == null || !layout.usable) return;
        JsonArray ids = new JsonArray(); List<String> wanted = new ArrayList<>();
        // When filtering to craftable recipes, query unchecked candidates too; otherwise
        // an initially empty green-only view would never discover newly available recipes.
        List<Entry> candidates = craftableOnly ? entries.stream().filter(entry -> (category.equals("ALL") || entry.category().equals(category))
                && entry.search().contains(query.toLowerCase(Locale.ROOT).trim())).toList() : visible();
        int start = craftableOnly && !candidates.isEmpty() ? (ticks / 20 * 20) % candidates.size() : 0;
        for (int index = 0; index < Math.min(20, candidates.size()); index++) wanted.add(candidates.get((start + index) % candidates.size()).id());
        Entry choice = chosen(); if (choice != null && !wanted.contains(choice.id())) wanted.add(choice.id());
        for (String id : wanted) ids.add(id);
        JsonObject payload = new JsonObject(); payload.addProperty("station", current.asLong()); payload.addProperty("count", amount);
        payload.addProperty("request", ++request); payload.add("recipes", ids); requestedAt = ticks;
        ColonyWorkClient.request("craft_view", payload);
    }
    @SubscribeEvent public static void receive(WorkNetwork.CraftViewEvent event) {
        CraftingPanel panel = active;
        if (panel == null || !panel.open || panel.level == null || !panel.level.dimension().location().toString().equals(event.view.dimension())) return;
        try {
            JsonObject data = Objects.requireNonNull(JsonParser.parseString(event.view.json()).getAsJsonObject());
            BlockPos current = panel.station;
            if (current == null || data.get("request").getAsInt() != panel.request || data.get("station").getAsLong() != current.asLong()
                    || data.get("count").getAsInt() != panel.amount || data.getAsJsonArray("states").size() > 24) return;
            for (var value : data.getAsJsonArray("states")) {
                var state = value.getAsJsonObject(); String id = state.get("id").getAsString(); panel.reasons.put(id, state.get("reason").getAsString()); panel.checked.put(id, panel.ticks);
            }
            panel.filter();
        } catch (RuntimeException invalid) { panel.reasons.clear(); }
    }
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!open) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        EditBox field = input;
        if (field != null && field.isFocused() && field.keyPressed(key, scan, modifiers)) return true;
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) { page = Math.max(0, Math.min(pages() - 1, page + (key == GLFW.GLFW_KEY_RIGHT ? 1 : -1))); requestedAt = -100; }
        return true;
    }
    public boolean charTyped(char character, int modifiers) { EditBox field = input; if (open && field != null) field.charTyped(character, modifiers); return open; }
    public boolean scroll(double delta) {
        if (!open) return false;
        if (Double.isFinite(delta)) { page = Math.max(0, Math.min(pages() - 1, page - (int) Math.signum(delta))); requestedAt = -100; }
        return true;
    }
    public boolean click(double x, double y, int button) {
        if (!open) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (layout.close.contains(x, y)) { close(); return true; }
        if (!layout.usable) return true;
        EditBox field = input;
        if (field != null) { field.setFocused(layout.search.contains(x, y)); if (field.mouseClicked(x, y, button)) return true; }
        if (layout.station.contains(x, y)) {
            var stations = ColonyWorkClient.stations().stream().filter(value -> !value.research()).toList();
            if (!stations.isEmpty()) {
                int index = -1; for (int i = 0; i < stations.size(); i++) if (stations.get(i).pos().equals(station)) index = i;
                station = stations.get((index + 1) % stations.size()).pos(); reasons.clear(); checked.clear();
            }
        } else if (layout.filter.contains(x, y)) { craftableOnly = !craftableOnly; page = 0; filter(); }
        else if (layout.categories.contains(x, y)) { category = Objects.requireNonNull(CATEGORIES[Math.min(4, ((int) x - layout.categories.x()) / 25)]); page = 0; filter(); }
        else if (layout.previous.contains(x, y)) page = Math.max(0, page - 1);
        else if (layout.next.contains(x, y)) page = Math.min(pages() - 1, page + 1);
        else if (layout.down.contains(x, y) || layout.up.contains(x, y)) {
            amount = Math.max(1, Math.min(64, amount + (layout.up.contains(x, y) ? 1 : -1))); reasons.clear(); checked.clear(); filter();
        } else if (layout.queue.contains(x, y)) {
            Entry entry = chosen(); BlockPos current = station;
            if (entry != null && current != null && reason(entry).equals("ready")) { ColonyWorkClient.craft(current, entry.id(), amount); reasons.clear(); checked.clear(); filter(); }
        } else {
            int index = layout.cellAt(x, y); var visible = visible(); if (index >= 0 && index < visible.size()) selected = visible.get(index).id();
        }
        requestedAt = -100; return true;
    }
    @Nonnull private String reason(Entry entry) {
        if (entry.recipe().isSpecial() || entry.output().isEmpty()) return "recipe";
        if (station == null) return "station";
        return ticks - Objects.requireNonNull(checked.getOrDefault(entry.id(), -100)) > 60 ? "checking" : Objects.requireNonNull(reasons.getOrDefault(entry.id(), "checking"));
    }
    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        Font currentFont = font; if (!open || currentFont == null) return;
        var bounds = layout.bounds; graphics.fill(0, 0, Minecraft.getInstance().getWindow().getGuiScaledWidth(), Minecraft.getInstance().getWindow().getGuiScaledHeight(), 0x60000000);
        bevel(graphics, bounds, 0xFFC6C6C6); label(graphics, currentFont, text("title", amount), bounds.x() + 8, bounds.y() + 7, bounds.width() - 30, 0x404040);
        button(graphics, currentFont, layout.close, Objects.requireNonNull(Component.literal("×")), true);
        if (!layout.usable) { label(graphics, currentFont, text("small_screen"), bounds.x() + 5, bounds.y() + 25, bounds.width() - 10, 0x8A3030); return; }
        var icons = List.of(Items.COMPASS, Items.BRICKS, Items.IRON_AXE, Items.REDSTONE, Items.LAVA_BUCKET);
        for (int i = 0; i < 5; i++) {
            var rect = new Rect(layout.categories.x() + 25 * i, layout.categories.y(), 22, 20);
            bevel(graphics, rect, category.equals(CATEGORIES[i]) ? 0xFF9EC6A0 : 0xFF9A9A9A); graphics.renderItem(new ItemStack(Objects.requireNonNull(icons.get(i))), rect.x() + 3, rect.y() + 2);
        }
        BlockPos current = station;
        button(graphics, currentFont, layout.station, current == null ? text("station_missing") : text("station", current.getX(), current.getY(), current.getZ()), true);
        EditBox field = input; if (field != null) field.render(graphics, mouseX, mouseY, 0);
        button(graphics, currentFont, layout.filter, Objects.requireNonNull(Component.literal(craftableOnly ? "+" : "−")), true);
        List<Component> tooltip = new ArrayList<>();
        var values = visible();
        for (int i = 0; i < values.size(); i++) {
            Entry entry = Objects.requireNonNull(values.get(i)); var rect = layout.cell(i); String state = reason(entry);
            bevel(graphics, rect, state.equals("ready") ? 0xFFA9C9A0 : state.equals("checking") ? 0xFF9D9D9D : 0xFFD0AAAA);
            if (entry.id().equals(selected)) graphics.renderOutline(rect.x(), rect.y(), rect.width(), rect.height(), 0xFF476A95);
            ItemStack icon = entry.output().isEmpty() ? new ItemStack(Objects.requireNonNull(Items.BARRIER)) : entry.output();
            graphics.renderItem(icon, rect.x() + 3, rect.y() + 3); graphics.renderItemDecorations(currentFont, icon, rect.x() + 3, rect.y() + 3);
            if (rect.contains(mouseX, mouseY)) { tooltip.add(Objects.requireNonNull(Component.literal(entry.name()))); tooltip.add(Objects.requireNonNull(Component.literal(entry.id()))); tooltip.add(status(entry)); }
        }
        if (values.isEmpty()) label(graphics, currentFont, text(craftableOnly ? "none_ready" : "no_results"), layout.grid.x(), layout.grid.y(), layout.grid.width(), 0x804040);
        Entry entry = chosen();
        if (entry != null) {
            int x = layout.detailX, y = bounds.y() + 74;
            label(graphics, currentFont, Objects.requireNonNull(Component.literal(entry.name())), x, y, layout.detailWidth, 0x404040);
            List<Ingredient> ingredients = entry.recipe().getIngredients();
            int slotSize = bounds.height() < 190 ? 16 : 18;
            for (int slot = 0; slot < 9; slot++) {
                int index = slot;
                if (entry.recipe() instanceof ShapedRecipe shaped) index = slot % 3 < shaped.getWidth() && slot / 3 < shaped.getHeight() ? slot / 3 * shaped.getWidth() + slot % 3 : -1;
                Ingredient ingredient = index >= 0 && index < ingredients.size() ? ingredients.get(index) : Ingredient.EMPTY;
                var rect = new Rect(x + slot % 3 * slotSize, y + 16 + slot / 3 * slotSize, slotSize, slotSize); bevel(graphics, rect, 0xFF8B8B8B);
                var alternatives = ingredient.getItems();
                if (alternatives.length > 0) {
                    var stack = Objects.requireNonNull(alternatives[(ticks / 20) % alternatives.length]); graphics.renderItem(stack, rect.x(), rect.y());
                    if (rect.contains(mouseX, mouseY)) { tooltip.add(stack.getHoverName()); tooltip.add(text("ingredient_count", amount)); if (alternatives.length > 1) tooltip.add(text("alternatives", alternatives.length)); }
                }
            }
            label(graphics, currentFont, Objects.requireNonNull(Component.literal("→")), x + 58, y + 36, 13, 0x404040);
            var output = new Rect(x + 74, y + 33, 24, 24); bevel(graphics, output, 0xFF8B8B8B);
            if (!entry.output().isEmpty()) { graphics.renderItem(entry.output(), output.x() + 4, output.y() + 4); graphics.renderItemDecorations(currentFont, entry.output(), output.x() + 4, output.y() + 4); }
            int statusY = y + 16 + 3 * slotSize + 5;
            if (statusY + 9 < layout.queue.y() - 3) {
                label(graphics, currentFont, status(entry), x, statusY, layout.detailWidth, reason(entry).equals("ready") ? 0x23652D : 0x913D3D);
                if (statusY + 22 < layout.queue.y() - 3)
                    label(graphics, currentFont, text("batch", amount, entry.output().getCount() * amount), x, statusY + 13, layout.detailWidth, 0x404040);
            }
            if (output.contains(mouseX, mouseY)) { tooltip.add(Objects.requireNonNull(Component.literal(entry.name()))); tooltip.add(status(entry)); }
        }
        button(graphics, currentFont, layout.previous, Objects.requireNonNull(Component.literal("‹")), page > 0);
        button(graphics, currentFont, layout.next, Objects.requireNonNull(Component.literal("›")), page + 1 < pages());
        label(graphics, currentFont, text("page", page + 1, pages()), layout.previous.right() + 5, layout.previous.y() + 5, 76, 0x404040);
        button(graphics, currentFont, layout.down, Objects.requireNonNull(Component.literal("−")), amount > 1);
        button(graphics, currentFont, layout.up, Objects.requireNonNull(Component.literal("+")), amount < 64);
        button(graphics, currentFont, layout.queue, text("queue"), entry != null && reason(entry).equals("ready"));
        if (ColonyWorkClient.recentNotice()) {
            List<net.minecraft.util.FormattedCharSequence> message = currentFont.split(ColonyWorkClient.notice(), Math.max(80, bounds.width() - 20));
            int messageY = Math.max(2, bounds.y() - message.size() * 11 - 4);
            graphics.fill(bounds.x(), messageY - 2, bounds.right(), messageY + message.size() * 11, 0xE0202020);
            for (var line : message) { graphics.drawString(currentFont, Objects.requireNonNull(line), bounds.x() + 6, messageY, 0xFFFFFFFF); messageY += 11; }
        }
        if (layout.filter.contains(mouseX, mouseY)) tooltip.add(text(craftableOnly ? "show_all" : "show_ready"));
        for (int i = 0; i < 5; i++) if (new Rect(layout.categories.x() + i * 25, layout.categories.y(), 22, 20).contains(mouseX, mouseY)) tooltip.add(text("category." + CATEGORIES[i].toLowerCase(Locale.ROOT)));
        if (layout.queue.contains(mouseX, mouseY)) { tooltip.add(text("batch", amount, entry == null ? 0 : entry.output().getCount() * amount)); if (entry != null) tooltip.add(status(entry)); }
        if (!tooltip.isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> wrapped = new ArrayList<>();
            for (Component line : tooltip) wrapped.addAll(currentFont.split(Objects.requireNonNull(line), Math.max(80, Math.min(220, bounds.width() - 16))));
            graphics.renderTooltip(currentFont, wrapped, mouseX, mouseY);
        }
    }
    @Nonnull private Component status(Entry entry) {
        String reason = reason(entry); return reason.equals("checking") ? text("checking") : reason.equals("ready") ? text("ready")
                : reason.equals("recipe") ? text("manual_recipe") : Objects.requireNonNull(Component.translatable("colonycamera.work.reason." + reason));
    }
    private static void bevel(GuiGraphics graphics, Rect rect, int color) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), color);
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.y() + 1, 0xFFFFFFFF); graphics.fill(rect.x(), rect.y(), rect.x() + 1, rect.bottom(), 0xFFFFFFFF);
        graphics.fill(rect.x(), rect.bottom() - 1, rect.right(), rect.bottom(), 0xFF555555); graphics.fill(rect.right() - 1, rect.y(), rect.right(), rect.bottom(), 0xFF555555);
    }
    private static void button(GuiGraphics graphics, Font font, Rect rect, Component text, boolean enabled) {
        bevel(graphics, rect, enabled ? 0xFFB1B1B1 : 0xFF999999); label(graphics, font, text, rect.x() + 3, rect.y() + Math.max(1, (rect.height() - 9) / 2), rect.width() - 6, enabled ? 0x303030 : 0x676767);
    }
    private static void label(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int color) {
        if (width > 0) graphics.drawString(font, Objects.requireNonNull(font.plainSubstrByWidth(Objects.requireNonNull(text.getString()), width)), x, y, color, false);
    }
    @Nonnull private static Component text(String key, Object... values) { return Objects.requireNonNull(Component.translatable("colonycamera.craftbook." + key, values)); }
}
