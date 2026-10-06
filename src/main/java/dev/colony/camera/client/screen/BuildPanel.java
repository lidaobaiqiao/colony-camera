package dev.colony.camera.client.screen;

import dev.colony.camera.client.construction.BuildingClient;
import dev.colony.camera.client.planning.ProjectionController;
import dev.colony.camera.config.SelectionConfig;
import dev.colony.camera.construction.BlockBuilding;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

/** Searchable construction palette embedded in StrategyScreen; GUI clicks never reach the world. */
@ParametersAreNonnullByDefault
final class BuildPanel {
    private static final String[] CATEGORIES = {"all", "favorites", "colony", "natural", "structure", "mechanism", "decor"};
    private final Map<String, Item> items = new LinkedHashMap<>();
    private final Set<String> supported = new HashSet<>();
    private final Map<String, String> categories = new HashMap<>();
    private List<BuildCatalogFilter.Row> filtered = List.of();
    private BuildPanelLayout layout = new BuildPanelLayout(1, 1, 0);
    @Nullable private Font font;
    @Nullable private EditBox search;
    @Nullable private String selected;
    @Nonnull private String query = "", category = "all";
    private boolean open, availableOnly;
    private int scroll, catalogRevision = -1, width, height;
    void resize(Font value, int w, int h, int bottom) {
        font = value; width = w; height = h; layout = new BuildPanelLayout(w, h, bottom);
        boolean focused = search != null && search.isFocused();
        EditBox box = new EditBox(value, layout.search.x(), layout.search.y(), layout.search.width(), layout.search.height(), text("search"));
        box.setMaxLength(128); box.setValue(query); box.setHint(text("search")); box.setFocused(focused);
        box.setResponder(valueText -> { query = Objects.requireNonNull(valueText); scroll = 0; filter(); }); search = box; clampScroll();
    }
    boolean isOpen() { return open; }
    void open() {
        if (items.isEmpty()) loadItems(); open = true; BuildingClient.refresh(); filter();
        EditBox box = search; if (box != null) box.setFocused(true);
    }
    void close() { open = false; EditBox box = search; if (box != null) box.setFocused(false); }
    boolean blocks(double x, double y) { return open || ProjectionController.instance().blockDraft() && layout.draft.contains(x, y); }
    private void loadItems() {
        for (var item : ForgeRegistries.ITEMS.getValues()) {
            if (!(item instanceof BlockItem blockItem)) continue;
            var key = ForgeRegistries.ITEMS.getKey(item); if (key == null) continue;
            String id = Objects.requireNonNull(key.toString()); items.put(id, item); categories.put(id, classify(blockItem, id));
            try { BlockBuilding.design(Objects.requireNonNull(item)); supported.add(id); } catch (IllegalArgumentException ignored) { /* The detail pane explains the construction boundary. */ }
        }
    }
    private static String classify(BlockItem item, String id) {
        if (id.startsWith("colonycamera:")) return "colony";
        var state = item.getBlock().defaultBlockState();
        if (state.is(Objects.requireNonNull(BlockTags.LOGS)) || state.is(Objects.requireNonNull(BlockTags.LEAVES)) || id.contains("ore") || id.contains("dirt") || id.contains("grass") || id.contains("sand")) return "natural";
        if (state.hasProperty(Objects.requireNonNull(BlockStateProperties.POWERED)) || id.contains("redstone") || id.contains("piston") || id.contains("hopper") || id.contains("dispenser") || id.contains("rail")) return "mechanism";
        if (id.contains("planks") || id.contains("stone") || id.contains("brick") || id.contains("concrete") || id.contains("glass") || id.contains("stairs") || id.contains("slab") || id.contains("wall") || id.contains("fence")) return "structure";
        return "decor";
    }
    private int status(String id) {
        if (!BuildingClient.ready()) return 0;
        if (!supported.contains(id)) return 3;
        var entry = BuildingClient.entry(id);
        return entry == null ? 3 : entry.available() > 0 ? 1 : !entry.recipe().isEmpty() ? 2 : 3;
    }
    private void filter() {
        var rows = new ArrayList<BuildCatalogFilter.Row>();
        items.forEach((id, item) -> rows.add(new BuildCatalogFilter.Row(Objects.requireNonNull(id), Objects.requireNonNull(item.getDescription().getString()), Objects.requireNonNull(categories.get(id)), status(id))));
        filtered = BuildCatalogFilter.filter(rows, query, category, availableOnly, SelectionConfig.buildFavorites());
        if (selected == null || filtered.stream().noneMatch(row -> row.id().equals(selected))) selected = filtered.isEmpty() ? null : filtered.get(0).id();
        catalogRevision = BuildingClient.revision(); clampScroll();
    }
    private void clampScroll() { scroll = Math.max(0, Math.min(scroll, layout.maxScroll(filtered.size()))); }
    void tick() {
        if (!open) return;
        EditBox box = search; if (box != null) box.tick();
        if (catalogRevision != BuildingClient.revision()) filter();
        BuildingClient.refresh();
    }
    boolean keyPressed(int key, int scan, int modifiers) {
        if (!open) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { choose(); return true; }
        EditBox box = search;
        if (box != null && box.isFocused()) { box.keyPressed(key, scan, modifiers); return true; }
        int delta = switch (key) { case GLFW.GLFW_KEY_LEFT -> -1; case GLFW.GLFW_KEY_RIGHT -> 1; case GLFW.GLFW_KEY_UP -> -layout.columns; case GLFW.GLFW_KEY_DOWN -> layout.columns; default -> 0; };
        if (delta != 0 && !filtered.isEmpty()) {
            int current = 0; for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).id().equals(selected)) current = i;
            int next = Math.max(0, Math.min(filtered.size() - 1, current + delta)); selected = filtered.get(next).id();
            if (next / layout.columns < scroll) scroll = next / layout.columns;
            if (next / layout.columns >= scroll + layout.rows) scroll = next / layout.columns - Math.max(1, layout.rows) + 1;
            clampScroll();
        }
        return true;
    }
    boolean charTyped(char value, int modifiers) { if (!open) return false; EditBox box = search; if (box != null) box.charTyped(value, modifiers); return true; }
    boolean click(double x, double y, int button) {
        if (!blocks(x, y)) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (!open) {
            var draft = ProjectionController.instance();
            int index = (int) ((x - layout.draft.x()) * 5 / Math.max(1, layout.draft.width()));
            if (y >= layout.draft.bottom() - 24) switch (index) { case 0 -> open(); case 1 -> draft.rotate(); case 2 -> draft.move(0, 1, 0); case 3 -> draft.move(0, -1, 0); case 4 -> draft.cancel(); default -> { } }
            return true;
        }
        EditBox box = search; if (box != null) { box.setFocused(layout.search.contains(x, y)); box.mouseClicked(x, y, button); }
        if (layout.close.contains(x, y)) close();
        else if (layout.refresh.contains(x, y)) BuildingClient.refresh();
        else if (layout.available.contains(x, y)) { availableOnly = !availableOnly; scroll = 0; filter(); }
        else if (layout.choose.contains(x, y)) choose();
        else if (layout.favorite.contains(x, y) && selected != null) {
            try { SelectionConfig.favorite(selected); filter(); } catch (RuntimeException failure) { /* Config values are rolled back; keep the current panel usable. */ }
        } else {
            for (int i = 0; i < CATEGORIES.length; i++) if (layout.category(i, CATEGORIES.length).contains(x, y)) { category = Objects.requireNonNull(CATEGORIES[i]); scroll = 0; filter(); return true; }
            int index = layout.rowAt(x, y, scroll); if (index >= 0 && index < filtered.size()) selected = filtered.get(index).id();
        }
        return true;
    }
    private void choose() {
        String id = selected; if (id == null || !supported.contains(id) || !BuildingClient.ready() || BuildingClient.pending()) return;
        Item item = items.get(id); if (item != null && ProjectionController.instance().startBlock(item)) close();
    }
    boolean scroll(double x, double y, double delta) {
        if (!open) return false;
        if (Double.isFinite(delta)) { scroll -= (int) Math.signum(delta) * 3; clampScroll(); } return true;
    }
    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font current = font; if (current == null) return;
        if (!open) { renderDraft(graphics, current, mouseX, mouseY); return; }
        graphics.fill(0, 0, width, height, 0xB0071019); StrategyHud.panel(graphics, layout.panel);
        fitted(graphics, current, text("title"), layout.panel.x() + 10, layout.panel.y() + 9, layout.panel.width() - 44, 0xFFE8F1F5);
        button(graphics, current, layout.close, Objects.requireNonNull(Component.literal("×")), false, mouseX, mouseY);
        EditBox box = search; if (box != null) box.render(graphics, mouseX, mouseY, partialTick);
        button(graphics, current, layout.refresh, text("refresh"), false, mouseX, mouseY);
        button(graphics, current, layout.available, text("available"), availableOnly, mouseX, mouseY);
        for (int i = 0; i < CATEGORIES.length; i++) button(graphics, current, layout.category(i, CATEGORIES.length), text("category." + CATEGORIES[i]), category.equals(CATEGORIES[i]), mouseX, mouseY);
        graphics.enableScissor(layout.grid.x(), layout.grid.y(), layout.grid.right(), layout.grid.bottom());
        for (int i = 0; i < layout.rows * layout.columns; i++) {
            int index = scroll * layout.columns + i; if (index >= filtered.size()) break;
            var row = filtered.get(index); var cell = layout.cell(i); boolean chosen = row.id().equals(selected);
            graphics.fill(cell.x() + 1, cell.y() + 1, cell.right() - 1, cell.bottom() - 1, chosen ? 0xFF294453 : cell.contains(mouseX, mouseY) ? 0xFF23323F : 0xFF14232F);
            graphics.fill(cell.x() + 2, cell.y() + 2, cell.x() + 4, cell.bottom() - 2, color(row.status()));
            Item item = items.get(row.id()); if (item != null) graphics.renderItem(new ItemStack(item), cell.x() + 8, cell.y() + 4);
            fitted(graphics, current, text("status." + row.status()), cell.x() + 28, cell.y() + 7, cell.width() - 31, color(row.status()));
            fitted(graphics, current, Objects.requireNonNull(Component.literal(row.name())), cell.x() + 7, cell.y() + 25, cell.width() - 12, 0xFFE8F1F5);
        }
        graphics.disableScissor(); renderDetail(graphics, current, mouseX, mouseY);
        if (layout.grid.height() > 0 && filtered.isEmpty()) fitted(graphics, current, text(BuildingClient.ready() ? "empty" : "loading"), layout.grid.x() + 5, layout.grid.y() + 9, layout.grid.width() - 10, 0xFF90A8B8);
    }
    private void renderDetail(GuiGraphics graphics, Font current, int mouseX, int mouseY) {
        var bounds = layout.detail; String id = selected; Item item = id == null ? null : items.get(id);
        if (item != null && id != null) {
            int state = status(id), x = bounds.x() + 6, y = bounds.y() + 6, w = Math.max(0, bounds.width() - 12);
            fitted(graphics, current, Objects.requireNonNull(item.getDescription()), x, y, bounds.width() - (bounds.height() > 60 ? 36 : 12), 0xFFE8F1F5);
            if (bounds.height() > 60) {
                fitted(graphics, current, Objects.requireNonNull(Component.literal(id)), x, y + 15, w, 0xFF90A8B8);
                var stock = BuildingClient.entry(id);
                fitted(graphics, current, text("stock", stock == null ? 0 : stock.available()), x, y + 32, w, color(state));
                Component reason = text(!BuildingClient.ready() ? "loading" : !supported.contains(id) ? "unsupported" : state == 1 ? "ready" : state == 2 ? "autocraft" : "missing");
                int line = y + 49; for (var part : current.split(reason, Math.max(1, w))) { if (line + 9 >= layout.choose.y()) break; graphics.drawString(current, Objects.requireNonNull(part), x, line, color(state), false); line += 11; }
                if (state == 2 && stock != null) {
                    var world = Minecraft.getInstance().level; ResourceLocation recipeId = ResourceLocation.tryParse(stock.recipe());
                    var recipe = world == null || recipeId == null ? null : world.getRecipeManager().byKey(recipeId).orElse(null);
                    if (recipe != null) {
                        line += 6; fitted(graphics, current, text("ingredients"), x, line, w, 0xFF90A8B8); line += 12;
                        Map<Item, Integer> ingredients = new LinkedHashMap<>();
                        for (var ingredient : recipe.getIngredients()) { var choices = ingredient.getItems(); if (choices.length > 0) ingredients.merge(choices[0].getItem(), 1, (left, right) -> Objects.requireNonNull(left) + Objects.requireNonNull(right)); }
                        for (var material : ingredients.entrySet()) {
                            if (line + 18 > layout.choose.y() - 8) break;
                            graphics.renderItem(new ItemStack(Objects.requireNonNull(material.getKey())), x, line);
                            fitted(graphics, current, text("ingredient", material.getKey().getDescription(), material.getValue()), x + 20, line + 4, w - 20, 0xFFE8F1F5); line += 19;
                        }
                    }
                }
            } else fitted(graphics, current, text(supported.contains(id) ? "status." + state : "unsupported"), x, y + 15, Math.max(0, bounds.width() - 140), color(state));
        }
        boolean favorite = id != null && SelectionConfig.buildFavorites().contains(id);
        button(graphics, current, layout.favorite, Objects.requireNonNull(Component.literal(favorite ? "★" : "☆")), favorite, mouseX, mouseY);
        button(graphics, current, layout.choose, text("choose"), id != null && supported.contains(id) && BuildingClient.ready(), mouseX, mouseY);
        if (layout.choose.contains(mouseX, mouseY) && id != null && !supported.contains(id)) graphics.renderTooltip(current, text("unsupported"), mouseX, mouseY);
    }
    private void renderDraft(GuiGraphics graphics, Font current, int mouseX, int mouseY) {
        var controller = ProjectionController.instance(); var draft = controller.draft(); String id = controller.blockItem();
        if (draft == null || id == null) return; var bounds = layout.draft; StrategyHud.panel(graphics, bounds);
        Item item = items.get(id); Component name = item == null ? Component.literal(id) : item.getDescription();
        fitted(graphics, current, text("placing", name), bounds.x() + 7, bounds.y() + 6, bounds.width() - 14, 0xFF67DEC2);
        if (bounds.height() >= 74) fitted(graphics, current, text("draft_hint"), bounds.x() + 7, bounds.y() + 22, bounds.width() - 14, 0xFFE8F1F5);
        if (bounds.height() >= 90) fitted(graphics, current, BuildingClient.notice(), bounds.x() + 7, bounds.y() + 40, bounds.width() - 14, 0xFFFFD379);
        String[] labels = {"palette", "rotate", "up", "down", "cancel"};
        for (int i = 0; i < labels.length; i++) {
            int left = bounds.x() + bounds.width() * i / labels.length, right = bounds.x() + bounds.width() * (i + 1) / labels.length;
            button(graphics, current, new StrategyHudLayout.Rect(left, bounds.bottom() - Math.min(23, bounds.height()), right - left, Math.min(23, bounds.height())), text(Objects.requireNonNull(labels[i])), false, mouseX, mouseY);
        }
    }
    private static int color(int state) { return switch (state) { case 1 -> 0xFF65DC9E; case 2 -> 0xFF67ADFF; case 3 -> 0xFFFF7778; default -> 0xFF90A8B8; }; }
    private static void button(GuiGraphics graphics, Font font, StrategyHudLayout.Rect bounds, Component label, boolean active, int mouseX, int mouseY) {
        if (bounds.width() < 3 || bounds.height() < 3) return;
        graphics.fill(bounds.x() + 1, bounds.y() + 1, bounds.right() - 1, bounds.bottom() - 1, active ? 0xFF315851 : bounds.contains(mouseX, mouseY) ? 0xFF2B4050 : 0xFF1A2D3B);
        fitted(graphics, font, label, bounds.x() + 4, bounds.y() + Math.max(0, (bounds.height() - 9) / 2), bounds.width() - 8, active ? 0xFF8BF2C8 : 0xFFE8F1F5);
    }
    private static void fitted(GuiGraphics graphics, Font font, Component value, int x, int y, int width, int color) { StrategyHud.drawFitted(graphics, font, value, x, y, width, color); }
    @Nonnull private static Component text(String key, Object... args) { return Objects.requireNonNull(Component.translatable("screen.colonycamera.build." + key, args)); }
}
