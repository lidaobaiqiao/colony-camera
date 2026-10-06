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

/** A sidebar palette; only its visible controls own world input. */
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
    private boolean open, availableOnly, categoriesOpen;
    private int scroll, catalogRevision = -1, width;
    void resize(Font value, int w, int h, int bottom) {
        font = value; width = w; layout = new BuildPanelLayout(w, h, bottom);
        boolean focused = search != null && search.isFocused();
        EditBox box = new EditBox(value, layout.search.x(), layout.search.y(), layout.search.width(), layout.search.height(), text("search"));
        box.setMaxLength(128); box.setValue(query); box.setHint(text("search")); box.setFocused(focused);
        box.setResponder(valueText -> { query = Objects.requireNonNull(valueText); scroll = 0; filter(); }); search = box; clampScroll();
    }
    boolean isOpen() { return open; }
    boolean capturesKeyboard() { EditBox box = search; return open && box != null && box.isFocused(); }
    void open() {
        ProjectionController.instance().cancel();
        if (items.isEmpty()) loadItems(); open = true; BuildingClient.refresh(); filter();
        EditBox box = search; if (box != null) box.setFocused(true);
    }
    void close() { open = false; categoriesOpen = false; EditBox box = search; if (box != null) box.setFocused(false); }
    boolean blocks(double x, double y) { return open ? layout.panel.contains(x, y) : ProjectionController.instance().blockDraft() && layout.draft.contains(x, y); }
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
        return delta != 0;
    }
    boolean charTyped(char value, int modifiers) { if (!capturesKeyboard()) return false; EditBox box = search; return box != null && box.charTyped(value, modifiers); }
    boolean click(double x, double y, int button) {
        if (!blocks(x, y)) { EditBox box = search; if (box != null) box.setFocused(false); categoriesOpen = false; return false; }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int index = layout.rowAt(x, y, scroll);
            if (open && !categoriesOpen && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && index >= 0 && index < filtered.size()) toggleFavorite(filtered.get(index).id());
            return true;
        }
        if (!open) {
            var draft = ProjectionController.instance();
            for (int i = 0; i < 5; i++) if (layout.draftControl(i).contains(x, y)) {
                switch (i) { case 0 -> open(); case 1 -> draft.rotate(); case 2 -> draft.move(0, 1, 0); case 3 -> draft.move(0, -1, 0); case 4 -> draft.cancel(); default -> { } }
                break;
            }
            return true;
        }
        EditBox box = search; if (box != null) { box.setFocused(layout.search.contains(x, y)); box.mouseClicked(x, y, button); }
        if (categoriesOpen) {
            for (int i = 0; i < CATEGORIES.length; i++) if (layout.category(i, CATEGORIES.length).contains(x, y)) {
                category = Objects.requireNonNull(CATEGORIES[i]); categoriesOpen = false; scroll = 0; filter(); return true;
            }
            categoriesOpen = false;
            if (layout.category.contains(x, y)) return true;
        }
        if (layout.close.contains(x, y)) close();
        else if (layout.refresh.contains(x, y)) BuildingClient.refresh();
        else if (layout.available.contains(x, y)) { availableOnly = !availableOnly; scroll = 0; filter(); }
        else if (layout.category.contains(x, y)) categoriesOpen = true;
        else if (layout.choose.contains(x, y)) choose();
        else if (layout.favorite.contains(x, y) && selected != null) toggleFavorite(selected);
        else {
            int index = layout.rowAt(x, y, scroll); if (index >= 0 && index < filtered.size()) { selected = filtered.get(index).id(); choose(); }
        }
        return true;
    }
    private void toggleFavorite(String id) {
        try { SelectionConfig.favorite(id); filter(); } catch (RuntimeException failure) { /* Config rolls back; keep the palette usable. */ }
    }
    private void choose() {
        String id = selected; if (id == null || !supported.contains(id) || !BuildingClient.ready() || BuildingClient.pending()) return;
        Item item = items.get(id); if (item != null && ProjectionController.instance().startBlock(item)) close();
    }
    boolean scroll(double x, double y, double delta) {
        if (!open || !layout.panel.contains(x, y)) return false;
        if (Double.isFinite(delta)) { scroll -= (int) Math.signum(delta) * 3; clampScroll(); } return true;
    }
    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font current = font; if (current == null) return;
        if (!open) { renderDraft(graphics, current, mouseX, mouseY); return; }
        StrategyHud.panel(graphics, layout.panel);
        fitted(graphics, current, text("title"), layout.panel.x() + 7, layout.panel.y() + 9, layout.panel.width() - 34, 0xFFE8F1F5);
        button(graphics, current, layout.close, Objects.requireNonNull(Component.literal("×")), false, mouseX, mouseY);
        EditBox box = search; if (box != null) box.render(graphics, mouseX, mouseY, partialTick);
        button(graphics, current, layout.refresh, Objects.requireNonNull(Component.literal("↻")), false, mouseX, mouseY);
        button(graphics, current, layout.available, text("available"), availableOnly, mouseX, mouseY);
        button(graphics, current, layout.category, text("category." + category), categoriesOpen, mouseX, mouseY);
        graphics.enableScissor(layout.grid.x(), layout.grid.y(), layout.grid.right(), layout.grid.bottom());
        for (int i = 0; i < layout.rows * layout.columns; i++) {
            int index = scroll * layout.columns + i; if (index >= filtered.size()) break;
            var row = filtered.get(index); var cell = layout.cell(i); boolean chosen = row.id().equals(selected);
            graphics.fill(cell.x() + 1, cell.y() + 1, cell.right() - 1, cell.bottom() - 1, chosen ? 0xFF294453 : cell.contains(mouseX, mouseY) ? 0xFF23323F : 0xFF14232F);
            graphics.fill(cell.x() + 2, cell.bottom() - 3, cell.right() - 2, cell.bottom() - 2, color(row.status()));
            Item item = items.get(row.id()); if (item != null) graphics.renderItem(new ItemStack(item), cell.x() + (cell.width() - 16) / 2, cell.y() + 4);
        }
        graphics.disableScissor(); renderDetail(graphics, current, mouseX, mouseY);
        if (layout.grid.height() > 0 && filtered.isEmpty()) fitted(graphics, current, text(BuildingClient.ready() ? "empty" : "loading"), layout.grid.x() + 5, layout.grid.y() + 9, layout.grid.width() - 10, 0xFF90A8B8);
        if (categoriesOpen) {
            graphics.fill(layout.search.x(), layout.category.bottom(), layout.search.right(), layout.category(CATEGORIES.length - 1, CATEGORIES.length).bottom(), 0xFF10232F);
            for (int i = 0; i < CATEGORIES.length; i++) button(graphics, current, layout.category(i, CATEGORIES.length), text("category." + CATEGORIES[i]), category.equals(CATEGORIES[i]), mouseX, mouseY);
        } else {
            int index = layout.rowAt(mouseX, mouseY, scroll);
            if (index >= 0 && index < filtered.size()) tooltip(graphics, current, detailLines(filtered.get(index).id()), mouseX, mouseY);
            else if (layout.refresh.contains(mouseX, mouseY)) tooltip(graphics, current, Objects.requireNonNull(List.of(text("refresh"))), mouseX, mouseY);
            else if (layout.available.contains(mouseX, mouseY)) tooltip(graphics, current, Objects.requireNonNull(List.of(text("available_tip"))), mouseX, mouseY);
            else if (layout.detail.contains(mouseX, mouseY) && selected != null) tooltip(graphics, current, detailLines(selected), mouseX, mouseY);
        }
    }
    private void renderDetail(GuiGraphics graphics, Font current, int mouseX, int mouseY) {
        var bounds = layout.detail; String id = selected; Item item = id == null ? null : items.get(id);
        if (item != null && id != null) {
            fitted(graphics, current, Objects.requireNonNull(item.getDescription()), bounds.x() + 3, bounds.y() + 4, Math.max(0, bounds.width() - 28), color(status(id)));
        }
        boolean favorite = id != null && SelectionConfig.buildFavorites().contains(id);
        button(graphics, current, layout.favorite, Objects.requireNonNull(Component.literal(favorite ? "★" : "☆")), favorite, mouseX, mouseY);
        button(graphics, current, layout.choose, text("choose"), id != null && supported.contains(id) && BuildingClient.ready() && !BuildingClient.pending(), mouseX, mouseY);
    }
    @Nonnull private List<Component> detailLines(String id) {
        Item item = items.get(id); if (item == null) return Objects.requireNonNull(List.of());
        var lines = new ArrayList<Component>(); int state = status(id); var stock = BuildingClient.entry(id);
        lines.add(Objects.requireNonNull(item.getDescription())); lines.add(Objects.requireNonNull(Component.literal(id)));
        lines.add(text("stock", stock == null ? 0 : stock.available()));
        lines.add(text(!BuildingClient.ready() ? "loading" : !supported.contains(id) ? "unsupported" : state == 1 ? "ready" : state == 2 ? "autocraft" : "missing"));
        if (state == 2 && stock != null) {
            var world = Minecraft.getInstance().level; ResourceLocation recipeId = ResourceLocation.tryParse(stock.recipe());
            var recipe = world == null || recipeId == null ? null : world.getRecipeManager().byKey(recipeId).orElse(null);
            if (recipe != null) {
                Map<Item, Integer> ingredients = new LinkedHashMap<>();
                for (var ingredient : recipe.getIngredients()) { var choices = ingredient.getItems(); if (choices.length > 0) ingredients.merge(choices[0].getItem(), 1, (left, right) -> Objects.requireNonNull(left) + Objects.requireNonNull(right)); }
                lines.add(text("ingredients"));
                for (var material : ingredients.entrySet()) lines.add(text("ingredient", material.getKey().getDescription(), material.getValue()));
            }
        }
        lines.add(text("item_tip")); return lines;
    }
    private void renderDraft(GuiGraphics graphics, Font current, int mouseX, int mouseY) {
        var controller = ProjectionController.instance(); var draft = controller.draft(); String id = controller.blockItem();
        if (draft == null || id == null) return; var bounds = layout.draft; StrategyHud.panel(graphics, bounds);
        Item item = items.get(id); Component name = Objects.requireNonNull(item == null ? Component.literal(id) : item.getDescription());
        boolean recent = BuildingClient.recentNotice() || controller.recentStatus();
        Component notice = BuildingClient.recentNotice() ? BuildingClient.notice() : controller.status();
        fitted(graphics, current, recent ? notice : name, bounds.x() + 6, bounds.y() + 8, layout.draftControl(0).x() - bounds.x() - 12, recent ? 0xFFFFD379 : 0xFF67DEC2);
        String[] labels = {"palette", "rotate", "up", "down", "cancel"};
        String[] shortcuts = {"N", "R", "+", "−", "×"};
        for (int i = 0; i < labels.length; i++) {
            button(graphics, current, layout.draftControl(i), Objects.requireNonNull(Component.literal(Objects.requireNonNull(shortcuts[i]))), false, mouseX, mouseY);
        }
        if (bounds.contains(mouseX, mouseY)) {
            var lines = new ArrayList<Component>(); lines.add(text("placing", name)); lines.add(text("draft_hint"));
            if (recent) lines.add(notice);
            for (int i = 0; i < labels.length; i++) if (layout.draftControl(i).contains(mouseX, mouseY)) lines.add(text(Objects.requireNonNull(labels[i])));
            tooltip(graphics, current, lines, mouseX, mouseY);
        }
    }
    private void tooltip(GuiGraphics graphics, Font current, List<Component> lines, int x, int y) {
        var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (var line : lines) wrapped.addAll(current.split(Objects.requireNonNull(line), Math.max(1, Math.min(260, width - 24))));
        graphics.renderTooltip(current, wrapped, x, y);
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
