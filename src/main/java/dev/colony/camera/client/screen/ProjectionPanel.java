package dev.colony.camera.client.screen;

import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.planning.ProjectionController;
import dev.colony.camera.client.planning.ProjectionRenderer;
import dev.colony.camera.client.construction.ConstructionClient;
import dev.colony.camera.network.ConstructionNetwork;
import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.preset.BlockPreset;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Library/dialogs stay inside StrategyScreen so opening them does not end the camera session. */
@ParametersAreNonnullByDefault
final class ProjectionPanel {
    private enum Mode { CLOSED, LIBRARY, SAVE, RENAME, DELETE_PRESET, DELETE_PROJECTION }
    private enum Action { CLOSE, PRESETS, PROJECTIONS, LOAD, RENAME, DELETE, EDIT, VISIBLE, ACCEPT, CANCEL,
        ROTATE, MIRROR, UP, DOWN, CONFIRM, CANCEL_DRAFT, FOLLOW, ENGINEERING, BUILD, PRINT_WORKER, PRIORITY_UP, PRIORITY_DOWN, PAUSE_JOB, CANCEL_JOB }
    private record Control(@Nonnull Action action, @Nonnull StrategyHudLayout.Rect bounds, @Nonnull Component label, boolean enabled) {}
    private final ProjectionController controller = ProjectionController.instance();
    private final List<Control> controls = new ArrayList<>();
    private Mode mode = Mode.CLOSED;
    private boolean projectsTab;
    private boolean constructionTab;
    @Nullable private UUID selectedJob;
    private int selected;
    private int scroll;
    private int width;
    private int height;
    private ProjectionPanelLayout layout = new ProjectionPanelLayout(1, 1, 0);
    @Nullable private Font font;
    @Nullable private EditBox name;
    @Nullable private BlockPreset targetPreset;
    @Nullable private ProjectionPlacement targetProjection;
    private String rememberedName = "";
    @Nullable private BlockPreset listedPreset;
    @Nonnull private List<Map.Entry<String, Long>> listedBlocks = Objects.requireNonNull(List.of());

    void resize(Font value, int w, int h, int bottom) {
        if (name != null) rememberedName = name.getValue();
        font = value; width = w; height = h;
        layout = new ProjectionPanelLayout(w, h, bottom);
        rebuildName();
        rebuildControls();
    }

    boolean modal() { return mode != Mode.CLOSED; }
    void close() { mode = Mode.CLOSED; name = null; rebuildControls(); }
    void library() { controller.reloadLibrary(); ConstructionClient.refresh(); mode = Mode.LIBRARY; name = null; rebuildControls(); }
    void saveDialog() {
        var selection = StrategyCameraController.instance().selection();
        if (selection.working() || selection.selectingHeight() || selection.selectionCount() == 0) {
            // Use the controller's normal failure feedback; an empty name cannot be persisted.
            controller.saveSelection("");
            library();
            return;
        }
        mode = Mode.SAVE; rememberedName = ""; rebuildName(); rebuildControls();
    }

    boolean blocks(double x, double y) {
        return modal() || controller.draft() != null && !controller.blockDraft() && draftBounds().contains(x, y);
    }

    boolean escape() {
        if (!modal()) return false;
        if (mode == Mode.LIBRARY) close();
        else library();
        return true;
    }

    boolean keyPressed(int key, int scan, int modifiers) {
        if (!modal()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) return escape();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { if (!constructionTab || mode != Mode.LIBRARY) run(mode == Mode.LIBRARY ? (projectsTab ? Action.EDIT : Action.LOAD) : Action.ACCEPT); return true; }
        if (name != null) name.keyPressed(key, scan, modifiers);
        else if (mode == Mode.LIBRARY && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            selected = Math.max(0, Math.min(count() - 1, selected + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
            if (constructionTab && selected < ConstructionClient.projects().size()) selectedJob = ConstructionClient.projects().get(selected).id();
            if (selected < scroll) scroll = selected;
            if (selected >= scroll + visibleRows()) scroll = selected - visibleRows() + 1;
            rebuildControls();
        }
        return true;
    }

    boolean charTyped(char character, int modifiers) {
        if (name != null) name.charTyped(character, modifiers);
        return modal();
    }

    boolean click(double x, double y, int button) {
        if (!blocks(x, y)) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        rebuildControls();
        if (name != null) name.mouseClicked(x, y, button);
        if (mode == Mode.LIBRARY) {
            var bounds = modalBounds();
            int listWidth = listWidth(bounds);
            for (int row = 0; row < visibleRows(); row++) {
                var item = new StrategyHudLayout.Rect(bounds.x() + 8, bounds.y() + 44 + row * 27, listWidth, 25);
                if (item.contains(x, y) && scroll + row < count()) { selected = scroll + row; if (constructionTab) selectedJob = ConstructionClient.projects().get(selected).id(); rebuildControls(); return true; }
            }
        }
        for (Control control : List.copyOf(controls)) {
            if (control.bounds().contains(x, y) && control.enabled()) { run(control.action()); break; }
        }
        return true;
    }

    boolean scroll(double x, double y, double delta) {
        if (!modal()) return false;
        if (!blocks(x, y)) return false;
        if (mode == Mode.LIBRARY) scroll = Math.max(0, Math.min(Math.max(0, count() - visibleRows()), scroll - (int) Math.signum(delta)));
        return true;
    }

    void tick() { EditBox input = name; if (input != null) input.tick(); }

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Font currentFont = font;
        if (currentFont == null) return;
        rebuildControls();
        if (modal()) {
            graphics.fill(0, 0, width, height, 0x90071019);
            var bounds = modalBounds();
            StrategyHud.panel(graphics, bounds);
            fitted(graphics, currentFont, text(mode == Mode.LIBRARY ? "library" : mode == Mode.SAVE ? "save_title" : mode == Mode.RENAME ? "rename_title" : "delete_title"),
                    bounds.x() + 12, bounds.y() + 8, bounds.width() - 48, 0xE8F1F5);
            if (mode == Mode.LIBRARY) renderLibrary(graphics, currentFont, bounds);
            else renderDialog(graphics, currentFont, bounds, mouseX, mouseY, partialTick);
            if (!constructionTab || mode != Mode.LIBRARY) fitted(graphics, currentFont, controller.status(), bounds.x() + 10, bounds.bottom() - 13, bounds.width() - 20, 0xFFD379);
        } else if (controller.draft() != null && !controller.blockDraft()) {
            var bounds = draftBounds();
            StrategyHud.panel(graphics, bounds);
            ProjectionPlacement draft = Objects.requireNonNull(controller.draft());
            fitted(graphics, currentFont, text("draft", draft.preset().name()), bounds.x() + 7, bounds.y() + 4, bounds.width() - 14, 0x67DEC2);
            if (bounds.height() >= 108) {
                fitted(graphics, currentFont, text("coordinates", draft.x(), draft.y(), draft.z()), bounds.x() + 7, bounds.y() + 18, bounds.width() - 14, 0xE8F1F5);
                fitted(graphics, currentFont, text("orientation", draft.quarterTurns() * 90, text(draft.mirrored() ? "yes" : "no")),
                        bounds.x() + 7, bounds.y() + 31, bounds.width() - 14, 0x90A8B8);
                if (bounds.height() >= 124) {
                    var stats = ProjectionRenderer.statistics();
                    fitted(graphics, currentFont, text(stats.complete() ? "stats" : "stats_scanning", stats.missing(), stats.matching(), stats.conflicting()), bounds.x() + 7, bounds.y() + 44, bounds.width() - 14, 0xFFD379);
                }
                if (bounds.height() >= 160) fitted(graphics, currentFont, text(controller.following() ? "following" : "pinned"), bounds.x() + 7, bounds.y() + 58, bounds.width() - 14, 0x90A8B8);
            }
        }
        for (Control control : controls) {
            var bounds = control.bounds();
            boolean hover = control.enabled() && bounds.contains(mouseX, mouseY);
            boolean active = control.action() == Action.PRESETS && !projectsTab && !constructionTab || control.action() == Action.PROJECTIONS && projectsTab || control.action() == Action.ENGINEERING && constructionTab;
            graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), hover ? 0xFF315667 : active ? 0xFF23534F : 0xFF233B49);
            int color = control.enabled() ? control.action() == Action.CONFIRM || control.action() == Action.ACCEPT ? 0x67DEC2 : 0xE8F1F5 : 0x627684;
            fitted(graphics, currentFont, Objects.requireNonNull(control.label()), bounds.x() + 4, bounds.y() + Math.max(2, (bounds.height() - 8) / 2), bounds.width() - 8, color);
        }
    }

    private void renderLibrary(GuiGraphics graphics, Font currentFont, StrategyHudLayout.Rect bounds) {
        if (constructionTab) { renderConstruction(graphics, currentFont, bounds); return; }
        int listWidth = listWidth(bounds);
        graphics.fill(bounds.x() + 8, bounds.y() + 43, bounds.x() + 8 + listWidth, bounds.bottom() - 43, 0x80101A24);
        int total = count();
        if (total == 0) fitted(graphics, currentFont, text(projectsTab ? "empty_projects" : "empty_library"), bounds.x() + 12, bounds.y() + 50, listWidth - 8, 0x90A8B8);
        for (int row = 0; row < visibleRows() && row + scroll < total; row++) {
            int index = row + scroll;
            int y = bounds.y() + 44 + row * 27;
            if (index == selected) graphics.fill(bounds.x() + 8, y, bounds.x() + 8 + listWidth, y + 25, 0xFF234B4B);
            var preset = projectsTab ? controller.placements().get(index).preset() : controller.presets().get(index);
            fitted(graphics, currentFont, Objects.requireNonNull(Component.literal(preset.name())), bounds.x() + 13, y + 3, listWidth - 10, 0xE8F1F5);
            Component detail = projectsTab ? text("coordinates", controller.placements().get(index).x(), controller.placements().get(index).y(), controller.placements().get(index).z())
                    : text("dimensions", preset.sizeX(), preset.sizeY(), preset.sizeZ(), preset.blocks().size());
            fitted(graphics, currentFont, detail, bounds.x() + 13, y + 15, listWidth - 10, 0x90A8B8);
        }
        BlockPreset preset = chosenPreset();
        if (preset == null) return;
        int x = bounds.x() + listWidth + 20;
        int available = bounds.right() - x - 10;
        fitted(graphics, currentFont, text("block_list"), x, bounds.y() + 46, available, 0x67DEC2);
        if (listedPreset != preset) {
            Map<String, Long> counts = preset.blocks().stream().map(cell -> cell.state().block())
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            listedBlocks = Objects.requireNonNull(counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList());
            listedPreset = preset;
        }
        int y = bounds.y() + 61;
        for (var entry : listedBlocks) {
            if (y > bounds.bottom() - 67) break;
            String id = Objects.requireNonNull(entry.getKey());
            var resource = net.minecraft.resources.ResourceLocation.tryParse(id);
            Component label = resource != null && net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(resource)
                    ? Objects.requireNonNull(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(resource)).getName() : Component.literal(id);
            fitted(graphics, currentFont, text("block_count", label, entry.getValue()), x, y, available, 0xE8F1F5);
            y += 12;
        }
        fitted(graphics, currentFont, text("scope_short"), x, bounds.bottom() - 57, available, 0x90A8B8);
    }

    private void renderConstruction(GuiGraphics graphics, Font currentFont, StrategyHudLayout.Rect bounds) {
        int listWidth = listWidth(bounds);
        var jobs = ConstructionClient.projects();
        if (jobs.isEmpty()) fitted(graphics, currentFont, constructionText("empty"), bounds.x() + 12, bounds.y() + 50, listWidth - 8, 0x90A8B8);
        for (int row = 0; row < visibleRows() && row + scroll < jobs.size(); row++) {
            int index = row + scroll; int y = bounds.y() + 44 + row * 27;
            if (index == selected) graphics.fill(bounds.x() + 8, y, bounds.x() + 8 + listWidth, y + 25, 0xFF234B4B);
            var job = jobs.get(index);
            fitted(graphics, currentFont, Objects.requireNonNull(job.palettePlan() == null ? Component.literal(job.name()) : Component.translatable(job.name())), bounds.x() + 13, y + 3, listWidth - 10, 0xE8F1F5);
            fitted(graphics, currentFont, constructionText("row", job.priority(), job.completed(), job.total()), bounds.x() + 13, y + 15, listWidth - 10, 0x90A8B8);
        }
        int x = bounds.x() + listWidth + 20; int available = bounds.right() - x - 10;
        fitted(graphics, currentFont, constructionText("worker_total", ConstructionClient.workers()), x, bounds.y() + 46, available, 0x67DEC2);
        var job = chosenJob();
        if (job == null && ConstructionClient.cores() == 0) fitted(graphics, currentFont, constructionText("need_core"), x, bounds.y() + 62, available, 0xFFD379);
        if (job != null) {
            fitted(graphics, currentFont, constructionText("reason." + job.reason()), x, bounds.y() + 62, available, 0xFFD379);
            if (bounds.height() >= 185) {
                fitted(graphics, currentFont, constructionText("assigned", job.workers()), x, bounds.y() + 77, available, 0xE8F1F5);
                fitted(graphics, currentFont, constructionText("ground", job.ground()), x, bounds.y() + 92, available, 0x90A8B8);
                fitted(graphics, currentFont, constructionText("reserved", job.reserved()), x, bounds.y() + 107, available, 0x90A8B8);
                var resource = net.minecraft.resources.ResourceLocation.tryParse(job.neededItem());
                var item = resource == null ? null : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(resource);
                if (item != null) fitted(graphics, currentFont, constructionText("needed", item.getDescription(), job.neededCount()),
                        x, bounds.y() + 122, available, 0xE8F1F5);
            }
        }
        fitted(graphics, currentFont, ConstructionClient.notice(), bounds.x() + 10, bounds.bottom() - 54, bounds.width() - 20, 0xFFD379);
    }

    private void renderDialog(GuiGraphics graphics, Font currentFont, StrategyHudLayout.Rect bounds, int mouseX, int mouseY, float partialTick) {
        if (name != null) {
            name.render(graphics, mouseX, mouseY, partialTick);
            fitted(graphics, currentFont, text(mode == Mode.SAVE ? "capture_note" : "name_hint"), bounds.x() + 12, bounds.y() + 59, bounds.width() - 24, 0x90A8B8);
            if (mode == Mode.SAVE && bounds.height() > 150) fitted(graphics, currentFont, text("capture_scope"), bounds.x() + 12, bounds.y() + 74, bounds.width() - 24, 0x90A8B8);
        } else {
            String label = targetPreset != null ? targetPreset.name() : targetProjection != null ? targetProjection.preset().name() : "";
            fitted(graphics, currentFont, text("delete_question", label), bounds.x() + 12, bounds.y() + 38, bounds.width() - 24, 0xFFD379);
            fitted(graphics, currentFont, text(mode == Mode.DELETE_PRESET ? "delete_preset_note" : "delete_projection_note"), bounds.x() + 12, bounds.y() + 57, bounds.width() - 24, 0x90A8B8);
        }
    }

    private void rebuildName() {
        Font currentFont = font;
        if (currentFont == null || (mode != Mode.SAVE && mode != Mode.RENAME)) { name = null; return; }
        var bounds = modalBounds();
        EditBox input = new EditBox(currentFont, bounds.x() + 12, bounds.y() + 31, Math.max(1, bounds.width() - 24), 20, text("name"));
        input.setMaxLength(48);
        input.setValue(Objects.requireNonNull(rememberedName));
        input.setFocused(true);
        input.setHint(text("name_hint"));
        name = input;
    }

    private void rebuildControls() {
        controls.clear();
        if (modal()) {
            var bounds = modalBounds();
            control(Action.CLOSE, bounds.right() - 26, bounds.y() + 5, 18, 17, "close", true);
            if (mode == Mode.LIBRARY) {
                if (constructionTab && selectedJob != null) {
                    var jobs = ConstructionClient.projects();
                    for (int index = 0; index < jobs.size(); index++) if (jobs.get(index).id().equals(selectedJob)) { selected = index; break; }
                }
                int tabWidth = Math.max(1, (bounds.width() - 32) / 3);
                control(Action.PRESETS, bounds.x() + 8, bounds.y() + 23, tabWidth, 17, "presets_tab", true);
                control(Action.PROJECTIONS, bounds.x() + 12 + tabWidth, bounds.y() + 23, tabWidth, 17, "projects_tab", true);
                control(Action.ENGINEERING, bounds.x() + 16 + tabWidth * 2, bounds.y() + 23, tabWidth, 17, "construction.tab", true);
                selected = Math.max(0, Math.min(selected, count() - 1));
                if (constructionTab && selectedJob == null && count() > 0) selectedJob = ConstructionClient.projects().get(selected).id();
                scroll = Math.max(0, Math.min(scroll, Math.max(0, count() - visibleRows())));
                int buttonWidth = Math.max(1, (bounds.width() - (constructionTab ? 32 : projectsTab ? 28 : 24)) / (constructionTab ? 5 : projectsTab ? 4 : 3));
                int y = bounds.bottom() - 38;
                if (constructionTab) {
                    var job = chosenJob();
                    boolean running = job != null && !job.cancelled() && job.completed() < job.total();
                    control(Action.PRIORITY_DOWN, bounds.x() + 8, y, buttonWidth, 20, "construction.priority_down", running && job != null && job.priority() > 1);
                    control(Action.PRIORITY_UP, bounds.x() + 12 + buttonWidth, y, buttonWidth, 20, "construction.priority_up", running && job != null && job.priority() < 9);
                    control(Action.PAUSE_JOB, bounds.x() + 16 + buttonWidth * 2, y, buttonWidth, 20, job != null && job.paused() ? "construction.resume" : "construction.pause", running);
                    control(Action.CANCEL_JOB, bounds.x() + 20 + buttonWidth * 3, y, buttonWidth, 20, running ? "construction.cancel" : "construction.remove", job != null);
                    control(Action.PRINT_WORKER, bounds.x() + 24 + buttonWidth * 4, y, buttonWidth, 20, "construction.print_worker", ConstructionClient.workers() < 8);
                } else {
                    control(projectsTab ? Action.EDIT : Action.LOAD, bounds.x() + 8, y, buttonWidth, 20, projectsTab ? "edit" : "load", count() > 0);
                    control(projectsTab ? Action.VISIBLE : Action.RENAME, bounds.x() + 12 + buttonWidth, y, buttonWidth, 20, projectsTab ? "visible" : "rename", count() > 0);
                    control(Action.DELETE, bounds.x() + 16 + buttonWidth * 2, y, buttonWidth, 20, "delete", count() > 0);
                    if (projectsTab) control(Action.BUILD, bounds.x() + 20 + buttonWidth * 3, y, buttonWidth, 20, "construction.build", count() > 0);
                }
            } else {
                int bw = Math.max(1, (bounds.width() - 28) / 2);
                control(Action.ACCEPT, bounds.x() + 10, bounds.bottom() - 38, bw, 20, mode == Mode.DELETE_PRESET || mode == Mode.DELETE_PROJECTION ? "delete" : "save",
                        name == null || !name.getValue().isBlank());
                control(Action.CANCEL, bounds.x() + 18 + bw, bounds.bottom() - 38, bw, 20, "cancel", true);
            }
        } else if (controller.draft() != null && !controller.blockDraft()) {
            for (var geometry : layout.draftControls()) {
                var action = Action.valueOf(Objects.requireNonNull(geometry.action().name()));
                String label = switch (geometry.action()) {
                    case ROTATE -> "rotate"; case MIRROR -> "mirror"; case UP -> "up"; case DOWN -> "down";
                    case FOLLOW -> "follow"; case CONFIRM -> "confirm"; case CANCEL_DRAFT -> "cancel";
                };
                var bounds = geometry.bounds();
                control(action, bounds.x(), bounds.y(), bounds.width(), bounds.height(), label, true);
            }
        }
    }

    private void run(Action action) {
        switch (action) {
            case CLOSE -> close();
            case PRESETS, PROJECTIONS, ENGINEERING -> { projectsTab = action == Action.PROJECTIONS; constructionTab = action == Action.ENGINEERING; selectedJob = null; selected = scroll = 0; if (constructionTab) ConstructionClient.refresh(); }
            case BUILD -> { var placement = chosenProjection(); if (placement != null) { ConstructionClient.create(placement); projectsTab = false; constructionTab = true; selectedJob = placement.id(); selected = scroll = 0; } }
            case PRINT_WORKER -> ConstructionClient.print(chosenJob());
            case PRIORITY_UP, PRIORITY_DOWN, PAUSE_JOB, CANCEL_JOB -> {
        var job = chosenJob();
                if (job != null) {
                    selectedJob = job.id();
                    String command = action == Action.PAUSE_JOB ? "pause" : action == Action.CANCEL_JOB ? (job.cancelled() || job.completed() == job.total() ? "remove" : "cancel") : "priority";
                    int value = action == Action.PRIORITY_UP ? job.priority() + 1 : job.priority() - 1;
                    ConstructionClient.request(command, job.id(), value);
                }
            }
            case LOAD -> { BlockPreset preset = chosenPreset(); if (preset != null && controller.start(preset)) close(); }
            case EDIT -> { ProjectionPlacement placement = chosenProjection(); if (placement != null && controller.edit(placement)) close(); }
            case RENAME -> {
                BlockPreset chosen = chosenPreset();
                targetPreset = chosen;
                if (chosen != null) { mode = Mode.RENAME; rememberedName = chosen.name(); rebuildName(); }
            }
            case DELETE -> {
                targetPreset = projectsTab ? null : chosenPreset(); targetProjection = projectsTab ? chosenProjection() : null;
                if (targetPreset != null || targetProjection != null) mode = projectsTab ? Mode.DELETE_PROJECTION : Mode.DELETE_PRESET;
            }
            case VISIBLE -> { ProjectionPlacement placement = chosenProjection(); if (placement != null) controller.toggleVisible(placement); }
            case ACCEPT -> {
                EditBox input = name;
                BlockPreset preset = targetPreset;
                ProjectionPlacement placement = targetProjection;
                if (mode == Mode.SAVE && input != null && controller.saveSelection(Objects.requireNonNull(input.getValue()))) library();
                else if (mode == Mode.RENAME && input != null && preset != null && controller.rename(preset, Objects.requireNonNull(input.getValue()))) library();
                else if (mode == Mode.DELETE_PRESET && preset != null) { controller.deletePreset(preset); library(); }
                else if (mode == Mode.DELETE_PROJECTION && placement != null) { controller.remove(placement); library(); }
            }
            case CANCEL -> library();
            case ROTATE -> controller.rotate();
            case MIRROR -> controller.mirror();
            case UP -> controller.move(0, 1, 0);
            case DOWN -> controller.move(0, -1, 0);
            case CONFIRM -> controller.confirm();
            case CANCEL_DRAFT -> controller.cancel();
            case FOLLOW -> controller.follow();
        }
        rebuildControls();
    }

    private void control(Action action, int x, int y, int w, int h, String label, boolean enabled) {
        if (x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= width && y + h <= height) {
            Component caption = label.startsWith("construction.") ? constructionText(Objects.requireNonNull(label.substring(13))) : text(label);
            if (action == Action.VISIBLE) {
                ProjectionPlacement placement = chosenProjection();
                if (placement != null) caption = text(placement.visible() ? "hide_projection" : "show_projection");
            }
            controls.add(new Control(action, new StrategyHudLayout.Rect(x, y, w, h), caption, enabled));
        }
    }

    @Nullable private ConstructionNetwork.View chosenJob() { return constructionTab && selected >= 0 && selected < ConstructionClient.projects().size() ? ConstructionClient.projects().get(selected) : null; }
    @Nonnull private static Component constructionText(String key, Object... args) { return Objects.requireNonNull(Component.translatable("colonycamera.construction." + key, args)); }
    private int count() { return constructionTab ? ConstructionClient.projects().size() : projectsTab ? controller.placements().size() : controller.presets().size(); }
    private int visibleRows() { return layout.visibleRows(); }
    private static int listWidth(StrategyHudLayout.Rect bounds) { return Math.max(1, (bounds.width() - 28) * 3 / 5); }
    @Nullable private BlockPreset chosenPreset() {
        if (selected < 0 || selected >= count()) return null;
        return projectsTab ? controller.placements().get(selected).preset() : controller.presets().get(selected);
    }
    @Nullable private ProjectionPlacement chosenProjection() {
        return selected >= 0 && selected < controller.placements().size() ? controller.placements().get(selected) : null;
    }
    @Nonnull
    private StrategyHudLayout.Rect modalBounds() {
        return layout.modal();
    }
    @Nonnull
    private StrategyHudLayout.Rect draftBounds() {
        return layout.draft();
    }
    @Nonnull private static Component text(String suffix, Object... args) {
        return Objects.requireNonNull(Component.translatable("screen.colonycamera.projection." + suffix, args));
    }
    private static void fitted(GuiGraphics graphics, Font font, Component text, int x, int y, int available, int color) {
        StrategyHud.drawFitted(graphics, font, text, x, y, available, color);
    }
}
