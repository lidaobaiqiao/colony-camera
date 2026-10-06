package dev.colony.camera.client.work;

import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.screen.StrategyHudLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Shared view; embedding it avoids ending the strategy camera when opening colony orders. */
@ParametersAreNonnullByDefault
public final class WorkPanel {
    private enum Tab { TASKS, CRAFT, TECHNOLOGY }
    private enum Action { CLOSE, TASKS, CRAFT, TECHNOLOGY, MINE, SWEEP, PRIORITY_DOWN, PRIORITY_UP,
        PAUSE, CANCEL, REFRESH, STATION, COUNT_DOWN, COUNT_UP, QUEUE_CRAFT, QUEUE_RESEARCH }
    private record Control(@Nonnull Action action, @Nonnull StrategyHudLayout.Rect bounds, @Nonnull Component label, boolean enabled) {}
    private static final int TEXT = 0xE8F1F5, MUTED = 0x90A8B8, ACCENT = 0x67DEC2, GOLD = 0xFFD379;
    private final boolean selectionAvailable;
    @Nonnull private final List<Control> controls = new ArrayList<>();
    @Nonnull private WorkPanelLayout layout = new WorkPanelLayout(1, 1);
    @Nullable private Font font;
    @Nullable private UUID selectedJob;
    @Nullable private String selectedRecipe;
    @Nullable private BlockPos craftStation;
    @Nullable private BlockPos researchStation;
    private Tab tab = Tab.TASKS;
    private int selected;
    private int scroll;
    private int amount = 1;
    private int width = 1, height = 1;
    private boolean open;

    public WorkPanel(boolean selectionAvailable) { this.selectionAvailable = selectionAvailable; }
    public boolean isOpen() { return open; }
    public void resize(Font newFont, int w, int h) {
        font = newFont; width = w; height = h; layout = new WorkPanelLayout(w, h); rebuild();
    }
    public void open() { open = true; ColonyWorkClient.refresh(); rebuild(); }
    public void openStation(BlockPos pos, boolean research) {
        if (research) researchStation = pos; else craftStation = pos;
        tab = research ? Tab.TECHNOLOGY : Tab.CRAFT; selected = scroll = 0; open();
    }
    public void close() { open = false; controls.clear(); }
    public void tick() {
        if (!open) return;
        rebuild();
    }
    public boolean blocks(double x, double y) { return open; }
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!open) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (layout.usable() && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) && tab != Tab.TECHNOLOGY) {
            choose(Math.max(0, Math.min(count() - 1, selected + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1))));
            revealSelected();
        }
        return true;
    }
    public boolean click(double x, double y, int button) {
        if (!open) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        rebuild();
        for (Control control : List.copyOf(controls)) {
            if (control.bounds().contains(x, y)) { if (control.enabled()) run(control.action()); return true; }
        }
        if (tab != Tab.TECHNOLOGY && layout.list().contains(x, y)) {
            int row = layout.rowAt(x, y);
            if (row >= 0 && row + scroll < count()) choose(row + scroll);
        }
        return true;
    }
    public boolean scroll(double x, double y, double delta) {
        if (!open) return false;
        if (Double.isFinite(delta) && layout.list().contains(x, y) && tab != Tab.TECHNOLOGY) {
            scroll = Math.max(0, Math.min(Math.max(0, count() - layout.rows()), scroll - (int) Math.signum(delta)));
        }
        return true;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        Font currentFont = font;
        if (!open || currentFont == null) return;
        rebuild();
        graphics.fill(0, 0, width, height, 0x80071019);
        var bounds = layout.bounds(); panel(graphics, bounds);
        if (bounds.width() >= 55 && bounds.height() >= 18) fitted(graphics, currentFont, text("title"), bounds.x() + 9, bounds.y() + 8, bounds.width() - 40, TEXT);
        if (!layout.usable()) {
            if (bounds.height() >= 40) fitted(graphics, currentFont, text("small_screen"), bounds.x() + 5, bounds.y() + 25, bounds.width() - 10, GOLD);
        } else {
            switch (tab) {
                case TASKS -> renderTasks(graphics, currentFont);
                case CRAFT -> renderCraft(graphics, currentFont);
                case TECHNOLOGY -> renderTechnology(graphics, currentFont);
            }
            fitted(graphics, currentFont, ColonyWorkClient.notice(), bounds.x() + 9, bounds.bottom() - 13, bounds.width() - 18, GOLD);
        }
        for (Control control : controls) {
            var rect = control.bounds(); boolean hover = rect.contains(mouseX, mouseY);
            boolean active = control.action() == Action.TASKS && tab == Tab.TASKS
                    || control.action() == Action.CRAFT && tab == Tab.CRAFT
                    || control.action() == Action.TECHNOLOGY && tab == Tab.TECHNOLOGY;
            graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), active ? 0xFF234B4B : hover && control.enabled() ? 0xFF263B4B : 0xFF192D39);
            fitted(graphics, currentFont, control.label(), rect.x() + 5, rect.y() + Math.max(1, (rect.height() - 9) / 2), rect.width() - 10,
                    !control.enabled() ? 0x586C79 : active ? ACCENT : TEXT);
        }
        List<Component> tooltip = tooltip(mouseX, mouseY);
        if (!tooltip.isEmpty()) {
            List<FormattedCharSequence> wrapped = new ArrayList<>();
            int wrap = Math.max(1, Math.min(280, width - 24));
            for (Component line : tooltip) wrapped.addAll(currentFont.split(Objects.requireNonNull(line), wrap));
            graphics.renderTooltip(currentFont, wrapped, mouseX, mouseY);
        }
    }

    private void renderTasks(GuiGraphics graphics, Font currentFont) {
        var jobs = ColonyWorkClient.jobs();
        if (jobs.isEmpty()) fitted(graphics, currentFont, text("jobs_empty"), layout.list().x() + 4, layout.list().y() + 4, layout.list().width() - 8, MUTED);
        for (int row = 0; row < layout.rows() && row + scroll < jobs.size(); row++) {
            var rect = layout.row(row); var job = Objects.requireNonNull(jobs.get(row + scroll));
            if (row + scroll == selected) graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), 0xFF234B4B);
            fitted(graphics, currentFont, jobTitle(job), rect.x() + 4, rect.y() + 2, rect.width() - 8, TEXT);
            fitted(graphics, currentFont, text("job_row", job.priority(), job.done(), job.total()), rect.x() + 4, rect.y() + 12, rect.width() - 8, MUTED);
        }
        List<Component> details = new ArrayList<>(); var job = chosenJob();
        if (job != null) {
            details.add(text("reason." + job.reason()));
            if (job.supply()) details.add(text("supply_controls"));
        }
        details.add(text("workers", ColonyWorkClient.workers().size()));
        for (var worker : ColonyWorkClient.workers()) details.add(text("worker", worker.miningLevel(), worker.xp(), itemName(worker.tool())));
        drawDetails(graphics, currentFont, details);
    }
    private void renderCraft(GuiGraphics graphics, Font currentFont) {
        var recipes = ColonyWorkClient.recipes();
        if (recipes.isEmpty()) fitted(graphics, currentFont, text("recipes_empty"), layout.list().x() + 4, layout.list().y() + 4, layout.list().width() - 8, MUTED);
        for (int row = 0; row < layout.rows() && row + scroll < recipes.size(); row++) {
            var rect = layout.row(row); var recipe = Objects.requireNonNull(recipes.get(row + scroll));
            if (row + scroll == selected) graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), 0xFF234B4B);
            fitted(graphics, currentFont, recipeTitle(recipe), rect.x() + 4, rect.y() + 2, rect.width() - 8, recipe.unlocked() ? TEXT : MUTED);
            fitted(graphics, currentFont, recipe.unlocked() ? text("output", recipe.count()) : text("locked_tier", tierName(recipe.requiredTier())),
                    rect.x() + 4, rect.y() + 12, rect.width() - 8, recipe.unlocked() ? MUTED : GOLD);
        }
        List<Component> details = new ArrayList<>(); var recipe = chosenRecipe();
        if (recipe != null) {
            details.add(text("craft_amount", amount, recipe.count() * amount));
            for (var ingredient : recipe.ingredients()) details.add(text("ingredient", ingredientName(Objects.requireNonNull(ingredient)), ingredient.count() * amount));
            if (!recipe.unlocked()) details.add(text("locked_tier", tierName(recipe.requiredTier())));
        }
        if (chosenStation(false) == null) details.add(text("need_crafting_station"));
        drawDetails(graphics, currentFont, details);
    }
    private void renderTechnology(GuiGraphics graphics, Font currentFont) {
        var tech = ColonyWorkClient.tech(); List<Component> lines = new ArrayList<>();
        lines.add(text("technology_current", ColonyWorkClient.text(tech.name())));
        if (tech.nextTier() >= 0) {
            lines.add(text("technology_next", tierName(tech.nextTier())));
            for (var cost : tech.costs()) lines.add(text("ingredient", ingredientName(Objects.requireNonNull(cost)), cost.count()));
            lines.add(text("research_duration", Math.max(0, tech.researchTicks() / 20)));
        } else lines.add(text("technology_complete"));
        if (tech.readOnly()) lines.add(text("reason.tech_data"));
        if (chosenStation(true) == null) lines.add(text("need_research_station"));
        var bounds = layout.bounds(); int y = layout.list().y();
        for (Component line : lines) {
            if (y + 9 > bounds.bottom() - 46) break;
            fitted(graphics, currentFont, Objects.requireNonNull(line), bounds.x() + 12, y, bounds.width() - 24, TEXT); y += 13;
        }
    }
    private void drawDetails(GuiGraphics graphics, Font currentFont, List<Component> lines) {
        var detail = layout.detail(); int y = detail.y() + 3;
        for (Component line : lines) {
            if (y + 9 > detail.bottom()) break;
            fitted(graphics, currentFont, Objects.requireNonNull(line), detail.x(), y, detail.width(), TEXT); y += 13;
        }
    }

    private void rebuild() {
        controls.clear(); if (!open) return;
        add(Action.CLOSE, layout.close(), Objects.requireNonNull(Component.literal("×")), true);
        if (!layout.usable()) return;
        add(Action.TASKS, layout.tab(0), text("tasks_tab"), true); add(Action.CRAFT, layout.tab(1), text("craft_tab"), true);
        add(Action.TECHNOLOGY, layout.tab(2), text("technology_tab"), true);
        if (tab == Tab.TASKS) {
            int previous = selected;
            if (selectedJob != null) {
                boolean found = false;
                for (int index = 0; index < ColonyWorkClient.jobs().size(); index++) if (ColonyWorkClient.jobs().get(index).id().equals(selectedJob)) { selected = index; found = true; break; }
                if (!found) selectedJob = null;
            }
            clamp(); if (selected != previous) revealSelected(); var job = chosenJob();
            boolean running = job != null && !job.cancelled() && !job.finished();
            var selection = StrategyCameraController.instance().selection();
            boolean selectedCells = selectionAvailable && selection.selectionCount() > 0 && !selection.working() && !selection.selectingHeight();
            add(Action.MINE, layout.creation(0), text("mine_selection"), selectedCells);
            add(Action.SWEEP, layout.creation(1), text("sweep_selection"), selectedCells);
            add(Action.PRIORITY_DOWN, layout.action(0, 5), text("priority_down"), running && job != null && job.priority() > 1);
            add(Action.PRIORITY_UP, layout.action(1, 5), text("priority_up"), running && job != null && job.priority() < 9);
            add(Action.PAUSE, layout.action(2, 5), text(job != null && job.paused() ? "resume" : "pause"), running);
            add(Action.CANCEL, layout.action(3, 5), text(running ? "cancel" : "remove"), job != null);
            add(Action.REFRESH, layout.action(4, 5), text("refresh"), true);
        } else if (tab == Tab.CRAFT) {
            int previous = selected;
            if (selectedRecipe != null) {
                boolean found = false;
                for (int index = 0; index < ColonyWorkClient.recipes().size(); index++) if (ColonyWorkClient.recipes().get(index).id().equals(selectedRecipe)) { selected = index; found = true; break; }
                if (!found) selectedRecipe = null;
            }
            clamp(); if (selected != previous) revealSelected(); var recipe = chosenRecipe();
            add(Action.STATION, layout.station(), stationCaption(false), !stations(false).isEmpty());
            add(Action.COUNT_DOWN, layout.action(0, 3), text("count_down"), amount > 1);
            add(Action.COUNT_UP, layout.action(1, 3), text("count_up", amount), amount < 64);
            add(Action.QUEUE_CRAFT, layout.action(2, 3), text("queue_craft"), recipe != null && recipe.unlocked() && chosenStation(false) != null);
        } else {
            add(Action.STATION, layout.station(), stationCaption(true), !stations(true).isEmpty());
            add(Action.QUEUE_RESEARCH, layout.action(0, 2), text("queue_research"), ColonyWorkClient.tech().nextTier() >= 0 && !ColonyWorkClient.tech().readOnly() && chosenStation(true) != null);
            add(Action.REFRESH, layout.action(1, 2), text("refresh"), true);
        }
    }
    private void run(Action action) {
        var job = chosenJob();
        switch (action) {
            case CLOSE -> close();
            case TASKS, CRAFT, TECHNOLOGY -> {
                tab = action == Action.TASKS ? Tab.TASKS : action == Action.CRAFT ? Tab.CRAFT : Tab.TECHNOLOGY;
                selected = scroll = 0; ColonyWorkClient.refresh();
            }
            case MINE -> ColonyWorkClient.mine();
            case SWEEP -> ColonyWorkClient.sweep();
            case PRIORITY_DOWN, PRIORITY_UP, PAUSE, CANCEL -> {
                if (job != null) {
                    String request = action == Action.PAUSE ? "pause" : action == Action.CANCEL ? (job.cancelled() || job.finished() ? "remove" : "cancel") : "priority";
                    ColonyWorkClient.control(Objects.requireNonNull(request), job.id(), job.priority() + (action == Action.PRIORITY_UP ? 1 : -1));
                }
            }
            case REFRESH -> ColonyWorkClient.refresh();
            case STATION -> cycleStation(tab == Tab.TECHNOLOGY);
            case COUNT_DOWN -> amount = Math.max(1, amount - 1);
            case COUNT_UP -> amount = Math.min(64, amount + 1);
            case QUEUE_CRAFT -> {
                var station = chosenStation(false); var recipe = chosenRecipe();
                if (station != null && recipe != null) ColonyWorkClient.craft(station.pos(), recipe, amount);
            }
            case QUEUE_RESEARCH -> { var station = chosenStation(true); if (station != null) ColonyWorkClient.research(station.pos()); }
        }
        rebuild();
    }
    private void add(Action action, StrategyHudLayout.Rect rect, Component label, boolean enabled) {
        if (rect.width() > 0 && rect.height() > 0) controls.add(new Control(action, rect, label, enabled));
    }
    private void choose(int index) {
        selected = index;
        if (tab == Tab.TASKS && index >= 0 && index < ColonyWorkClient.jobs().size()) selectedJob = ColonyWorkClient.jobs().get(index).id();
        else if (tab == Tab.CRAFT && index >= 0 && index < ColonyWorkClient.recipes().size()) selectedRecipe = ColonyWorkClient.recipes().get(index).id();
    }
    private void clamp() {
        selected = Math.max(0, Math.min(selected, count() - 1)); scroll = Math.max(0, Math.min(scroll, Math.max(0, count() - layout.rows())));
        if (tab == Tab.TASKS && selectedJob == null && count() > 0) choose(selected);
        if (tab == Tab.CRAFT && selectedRecipe == null && count() > 0) choose(selected);
    }
    private void revealSelected() {
        if (!layout.usable()) return;
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + layout.rows()) scroll = selected - layout.rows() + 1;
    }
    private int count() { return tab == Tab.TASKS ? ColonyWorkClient.jobs().size() : tab == Tab.CRAFT ? ColonyWorkClient.recipes().size() : 0; }
    @Nullable private ColonyWorkClient.Job chosenJob() { return tab == Tab.TASKS && selected >= 0 && selected < ColonyWorkClient.jobs().size() ? ColonyWorkClient.jobs().get(selected) : null; }
    @Nullable private ColonyWorkClient.Recipe chosenRecipe() { return tab == Tab.CRAFT && selected >= 0 && selected < ColonyWorkClient.recipes().size() ? ColonyWorkClient.recipes().get(selected) : null; }
    @Nonnull private List<ColonyWorkClient.Station> stations(boolean research) { return Objects.requireNonNull(ColonyWorkClient.stations().stream().filter(value -> value.research() == research).toList()); }
    @Nullable private ColonyWorkClient.Station chosenStation(boolean research) {
        var values = stations(research); if (values.isEmpty()) return null;
        BlockPos preferred = research ? researchStation : craftStation;
        for (var station : values) if (station.pos().equals(preferred)) return station;
        // Never silently queue at a different facility if the selected one vanished.
        if (preferred != null) return null;
        var first = values.get(0);
        if (research) researchStation = first.pos(); else craftStation = first.pos();
        return first;
    }
    private void cycleStation(boolean research) {
        var values = stations(research); var current = chosenStation(research); if (values.isEmpty()) return;
        int next = (values.indexOf(current) + 1) % values.size();
        if (research) researchStation = values.get(next).pos(); else craftStation = values.get(next).pos();
    }
    @Nonnull private Component stationCaption(boolean research) {
        var station = chosenStation(research);
        return station == null ? text(research ? "need_research_station" : "need_crafting_station")
                : text("station", text(research ? "research_station" : "crafting_station"), station.pos().getX(), station.pos().getY(), station.pos().getZ());
    }
    @Nonnull private List<Component> tooltip(double x, double y) {
        if (!layout.usable()) return Objects.requireNonNull(List.of());
        for (var control : controls) if (control.bounds().contains(x, y)) {
            String suffix = switch (control.action()) {
                case MINE -> selectionAvailable ? "mine_tip" : "selection_from_strategy";
                case SWEEP -> selectionAvailable ? "sweep_tip" : "selection_from_strategy";
                case STATION -> "station_tip";
                case QUEUE_CRAFT -> "craft_tip";
                case QUEUE_RESEARCH -> "research_tip";
                case PRIORITY_DOWN, PRIORITY_UP -> "priority_tip";
                default -> "";
            };
            return suffix.isEmpty() ? Objects.requireNonNull(List.of(control.label())) : Objects.requireNonNull(List.of(control.label(), text(Objects.requireNonNull(suffix))));
        }
        if (layout.list().contains(x, y) && tab != Tab.TECHNOLOGY) {
            int row = layout.rowAt(x, y); if (row < 0) return Objects.requireNonNull(List.of());
            int index = scroll + row;
            if (index >= count()) return Objects.requireNonNull(List.of());
            if (tab == Tab.TASKS) {
                var job = Objects.requireNonNull(ColonyWorkClient.jobs().get(index));
                return Objects.requireNonNull(List.of(jobTitle(job), text("job_row", job.priority(), job.done(), job.total()), text("reason." + job.reason())));
            }
            var recipe = Objects.requireNonNull(ColonyWorkClient.recipes().get(index)); List<Component> lines = new ArrayList<>();
            lines.add(recipeTitle(recipe)); lines.add(text("craft_amount", amount, recipe.count() * amount));
            for (var ingredient : recipe.ingredients()) {
                lines.add(text("ingredient", ingredientName(Objects.requireNonNull(ingredient)), ingredient.count() * amount));
                if (ingredient.alternatives().size() > 1) {
                    String names = Objects.requireNonNull(ingredient.alternatives().stream().map(id -> itemName(Objects.requireNonNull(id)).getString()).collect(java.util.stream.Collectors.joining(" / ")));
                    lines.add(text("ingredient_choices", names));
                }
            }
            if (!recipe.unlocked()) lines.add(text("locked_tier", tierName(recipe.requiredTier())));
            return lines;
        }
        if (tab == Tab.TECHNOLOGY && y >= layout.list().y() && y < layout.bounds().bottom() - 46 && layout.bounds().contains(x, y)) {
            var tech = ColonyWorkClient.tech(); List<Component> lines = new ArrayList<>();
            lines.add(text("technology_current", ColonyWorkClient.text(tech.name())));
            if (tech.nextTier() >= 0) {
                lines.add(text("technology_next", tierName(tech.nextTier())));
                for (var cost : tech.costs()) lines.add(text("ingredient", ingredientName(Objects.requireNonNull(cost)), cost.count()));
                lines.add(text("research_duration", tech.researchTicks() / 20));
            }
            return lines;
        }
        if (layout.detail().contains(x, y) && tab == Tab.TASKS) {
            List<Component> lines = new ArrayList<>(); lines.add(text("workers", ColonyWorkClient.workers().size()));
            for (var worker : ColonyWorkClient.workers()) lines.add(text("worker", worker.miningLevel(), worker.xp(), itemName(worker.tool())));
            return lines;
        }
        return Objects.requireNonNull(List.of());
    }
    @Nonnull private static Component jobTitle(ColonyWorkClient.Job job) {
        Component name = job.name().isBlank() ? text("kind." + job.kind().toLowerCase(java.util.Locale.ROOT)) : ColonyWorkClient.text(job.name());
        if (job.supply()) name = text("supply_title", name);
        var origin = job.origin();
        return origin == null ? name : text("job_position", name, origin.getX(), origin.getY(), origin.getZ());
    }
    @Nonnull private static Component recipeTitle(ColonyWorkClient.Recipe recipe) { return recipe.name().isBlank() ? itemName(recipe.output()) : ColonyWorkClient.text(recipe.name()); }
    @Nonnull private static Component tierName(int tier) { return text("tier." + tier); }
    @Nonnull private static Component ingredientName(ColonyWorkClient.Ingredient ingredient) {
        Component name = ingredient.name().isBlank() ? itemName(ingredient.item()) : ColonyWorkClient.text(ingredient.name());
        return ingredient.alternatives().size() > 1 ? text("ingredient_any", name) : name;
    }
    @Nonnull private static Component itemName(String id) {
        if (id.isBlank() || id.equals("minecraft:air")) return text("no_tool");
        var resource = ResourceLocation.tryParse(id); var item = resource == null ? null : ForgeRegistries.ITEMS.getValue(resource);
        return item == null ? Objects.requireNonNull(Component.literal(id)) : Objects.requireNonNull(item.getDescription());
    }
    @Nonnull private static Component text(String suffix, Object... arguments) { return Objects.requireNonNull(Component.translatable("colonycamera.work." + suffix, arguments)); }
    private static void fitted(GuiGraphics graphics, Font font, Component text, int x, int y, int available, int color) {
        if (available <= 0) return;
        String label = Objects.requireNonNull(text.getString());
        if (font.width(label) > available) label = font.plainSubstrByWidth(label, Math.max(0, available - font.width("…"))) + "…";
        graphics.drawString(font, Objects.requireNonNull(label), x, y, color, false);
    }
    private static void panel(GuiGraphics graphics, StrategyHudLayout.Rect bounds) {
        if (bounds.width() < 2 || bounds.height() < 2) return;
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), 0xFF334958);
        graphics.fill(bounds.x() + 1, bounds.y() + 1, bounds.right() - 1, bounds.bottom() - 1, 0xF0101C28);
    }
}
