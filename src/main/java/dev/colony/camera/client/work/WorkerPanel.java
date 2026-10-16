package dev.colony.camera.client.work;

import com.google.gson.JsonObject;
import dev.colony.camera.client.screen.InspectorLayout;
import dev.colony.camera.client.screen.UiDraw;
import dev.colony.camera.client.screen.UiTheme;
import net.minecraft.util.FormattedCharSequence;
import dev.colony.camera.client.construction.ConstructionClient;
import dev.colony.camera.worker.WorkerAttribute;
import dev.colony.camera.worker.WorkerProfile;
import dev.colony.camera.worker.WorkerPreferences;
import dev.colony.camera.client.screen.RegistryPicker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/** Compact, scrollable person view. Values and available facilities come exclusively from server snapshots. */
@ParametersAreNonnullByDefault
public final class WorkerPanel {
    private enum Page { OVERVIEW, TASKS, EQUIPMENT, GROWTH, LIFE, SOCIAL, LEARNING, ORDERS, PRINT }
    private static final Page[] TABS = {Page.OVERVIEW, Page.TASKS, Page.EQUIPMENT, Page.GROWTH, Page.LIFE, Page.SOCIAL};
    @Nonnull private final RegistryPicker equipment = new RegistryPicker();
    private record Row(@Nonnull Component text, @Nullable String action, boolean enabled, double bar, @Nullable FormattedCharSequence line) {
        Row(Component text, @Nullable String action, boolean enabled, double bar) { this(text, action, enabled, bar, null); }
    }
    @Nullable private UUID person, target, undertaking;
    @Nullable private BlockPos school, core;
    @Nonnull private WorkerProfile.Profession profession = WorkerProfile.Profession.MINER;
    @Nonnull private WorkerAttribute attribute = WorkerAttribute.MINING;
    @Nonnull private WorkerProfile.Interest interest = WorkerProfile.Interest.NONE;
    @Nonnull private WorkerProfile.Temperament temperament = WorkerProfile.Temperament.NONE;
    @Nonnull private Page page = Page.OVERVIEW;
    @Nonnull private final List<Row> rows = new ArrayList<>();
    @Nullable private Font font;
    private int x, y, width, scroll, tier = 1, viewportWidth = 1, viewportHeight = 1;
    @Nonnull private InspectorLayout layout = new InspectorLayout(1, 1, false, 0);
    @Nullable private JsonObject pendingOrder;
    private boolean pressureExpanded;
    private boolean open;
    public boolean isOpen() { return open; }
    public void resize(Font value, int screenWidth, int screenHeight) {
        font = value; viewportWidth = screenWidth; viewportHeight = screenHeight; relayout();
        equipment.resize(value, screenWidth, screenHeight);
    }
    private void relayout() {
        layout = new InspectorLayout(viewportWidth, viewportHeight, page == Page.PRINT || pendingOrder != null, rows.size());
        x = layout.bounds.x(); y = layout.bounds.y(); width = layout.bounds.width();
    }
    public boolean modal() { return open && (page == Page.PRINT || pendingOrder != null || equipment.isOpen()); }
    public boolean blocks(double mouseX, double mouseY) { return open && (modal() || layout.bounds.contains(mouseX, mouseY)); }
    public void open(UUID id) {
        if (!id.equals(person)) { page = Page.OVERVIEW; scroll = 0; pressureExpanded = false; }
        person = id; pendingOrder = null; open = true; equipment.close(); relayout(); ColonyWorkClient.refresh();
    }
    public void print() { person = null; pendingOrder = null; page = Page.PRINT; scroll = 0; open = true; equipment.close(); relayout(); ColonyWorkClient.refresh(); }
    public void close() { open = false; pendingOrder = null; equipment.close(); }
    public void tick() { if (open) equipment.tick(); }
    public boolean charTyped(char ch, int modifiers) { if (!open) return false; return equipment.charTyped(ch, modifiers) || modal(); }
    private void back() {
        if (pendingOrder != null) pendingOrder = null;
        else if (page == Page.LEARNING || page == Page.ORDERS) page = page == Page.LEARNING ? Page.GROWTH : Page.TASKS;
        else { close(); return; }
        scroll = 0; relayout();
    }
    private int tabIndex() { return page == Page.LEARNING ? 3 : page == Page.ORDERS ? 1 : Math.min(5, page.ordinal()); }
    private void changePage(int change) { page = Objects.requireNonNull(TABS[Math.floorMod(tabIndex() + change, TABS.length)]); scroll = 0; relayout(); }
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!open) return false;
        if (equipment.keyPressed(key, scan, modifiers)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) { back(); return true; }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) {
            scroll += (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP ? visibleRows() : 1); return true;
        }
        if (key == GLFW.GLFW_KEY_TAB && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && !modal()) { changePage((modifiers & GLFW.GLFW_MOD_SHIFT) == 0 ? 1 : -1); return true; }
        return modal();
    }
    public boolean scroll(double delta) { if (!open) return false; if (equipment.scroll(delta)) return true; if (Double.isFinite(delta)) scroll -= (int) Math.signum(delta); return true; }
    public boolean click(double mouseX, double mouseY, int button) {
        if (!blocks(mouseX, mouseY)) return false;
        if (equipment.click(mouseX, mouseY, button)) return true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        rebuild();
        if (layout.close.contains(mouseX, mouseY)) { close(); return true; }
        boolean nested = page == Page.LEARNING || page == Page.ORDERS || pendingOrder != null;
        if (nested && layout.back.contains(mouseX, mouseY)) { back(); return true; }
        if (!layout.usable) return true;
        if (!layout.modal && pendingOrder == null) {
            if (layout.compact) {
                if (layout.previous.contains(mouseX, mouseY)) { changePage(-1); return true; }
                if (layout.next.contains(mouseX, mouseY) || layout.tab(0).contains(mouseX, mouseY)) { changePage(1); return true; }
            } else for (int i = 0; i < TABS.length; i++) if (layout.tab(i).contains(mouseX, mouseY)) { page = Objects.requireNonNull(TABS[i]); scroll = 0; relayout(); return true; }
        }
        int index = layout.rowAt(mouseX, mouseY);
        if (index >= 0 && scroll + index < rows.size()) {
            Row entry = Objects.requireNonNull(rows.get(scroll + index)); String action = entry.action();
            if (action != null && entry.enabled()) act(action);
        }
        return true;
    }
    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        Font current = font; if (!open || current == null) return;
        rebuild();
        if (modal()) graphics.fill(0, 0, viewportWidth, viewportHeight, UiTheme.SCRIM);
        UiDraw.panel(graphics, layout.bounds);
        boolean nested = page == Page.LEARNING || page == Page.ORDERS || pendingOrder != null;
        int titleX = x + (nested ? 24 : 7);
        draw(graphics, current, text(page == Page.PRINT ? "print_title" : pendingOrder != null ? "confirm_title" : "title", person == null ? "" : person.toString().substring(0, 8)), titleX, y + 6, width - (nested ? 48 : 30), UiTheme.TEXT);
        UiDraw.button(graphics, current, layout.close, Objects.requireNonNull(Component.literal("×")), true, false, layout.close.contains(mouseX, mouseY));
        if (nested) UiDraw.button(graphics, current, layout.back, Objects.requireNonNull(Component.literal("‹")), true, false, layout.back.contains(mouseX, mouseY));
        if (!layout.usable) { draw(graphics, current, Objects.requireNonNull(Component.translatable("colonycamera.work.small_screen")), x + 5, y + 25, width - 10, UiTheme.WARNING); return; }
        if (!layout.modal) {
            if (layout.compact) {
                UiDraw.button(graphics, current, layout.previous, Objects.requireNonNull(Component.literal("‹")), true, false, layout.previous.contains(mouseX, mouseY));
                UiDraw.button(graphics, current, layout.next, Objects.requireNonNull(Component.literal("›")), true, false, layout.next.contains(mouseX, mouseY));
                UiDraw.button(graphics, current, layout.tab(0), text("page." + Objects.requireNonNull(TABS[tabIndex()].name()).toLowerCase(java.util.Locale.ROOT)), true, true, layout.tab(0).contains(mouseX, mouseY));
            } else for (int i = 0; i < TABS.length; i++) UiDraw.button(graphics, current, layout.tab(i), text("page." + Objects.requireNonNull(TABS[i].name()).toLowerCase(java.util.Locale.ROOT)), true, i == tabIndex(), layout.tab(i).contains(mouseX, mouseY));
        }
        graphics.enableScissor(layout.content.x(), layout.content.y(), layout.content.right(), layout.content.bottom());
        for (int index = 0; index < visibleRows() && index + scroll < rows.size(); index++) {
            Row row = Objects.requireNonNull(rows.get(index + scroll)); var rect = layout.row(index);
            if (row.action() != null) UiDraw.button(graphics, current, rect, row.text(), row.enabled(), false, rect.contains(mouseX, mouseY));
            else if (row.line() != null) graphics.drawString(current, Objects.requireNonNull(row.line()), rect.x() + 3, rect.y() + 4, UiTheme.TEXT, false);
            else draw(graphics, current, row.text(), rect.x() + 3, rect.y() + 4, rect.width() - 6, UiTheme.TEXT);
            if (row.bar() >= 0) UiDraw.progress(graphics, rect.x() + 3, rect.bottom() - 2, rect.width() - 6, row.bar(), UiTheme.ACCENT);
        }
        graphics.disableScissor();
        Component notice = ColonyWorkClient.recentNotice() ? ColonyWorkClient.notice() : text("page_hint", scroll + 1, Math.max(1, rows.size()));
        draw(graphics, current, notice, layout.notice.x(), layout.notice.y(), layout.notice.width(), UiTheme.SECONDARY);
        int hovered = layout.rowAt(mouseX, mouseY);
        if (hovered >= 0 && hovered + scroll < rows.size()) {
            Row entry = Objects.requireNonNull(rows.get(hovered + scroll));
            if (entry.action() != null || current.width(entry.text()) > layout.content.width() - 6)
                graphics.renderTooltip(current, Objects.requireNonNull(current.split(entry.text(), Math.max(1, Math.min(240, viewportWidth - 24)))), mouseX, mouseY);
        }
        equipment.render(graphics, mouseX, mouseY);
    }
    private int visibleRows() { return layout.rows(); }
    private void rebuild() {
        rows.clear();
        if (page == Page.PRINT) {
            row(text("print_home_rule"));
            row(text("interest", label(interest)), "interest", true); row(text("temperament", label(temperament)), "temperament", true);
            row(text("core", position(core())), "core", !ConstructionClient.corePositions().isEmpty());
            row(text("print_now"), "print", core() != null && WorkerProfile.compatible(interest, temperament));
            row(text("birth_rule"));
        } else {
            var worker = worker();
            if (worker == null) { row(text("unavailable")); relayout(); scroll = 0; return; }
            JsonObject state = worker.state(); boolean writable = !flag(state, "readOnly");
            row(text("status", text("reason." + word(state, "reason", "idle"))));
            if (!writable) { row(text("read_only")); relayout(); scroll = 0; return; }
            JsonObject confirming = pendingOrder;
            if (confirming != null) {
                row(text("confirm_warning")); row(text("target", word(confirming, "target", "")));
                row(text("confirm_order"), "confirm_order", writable); row(text("cancel_order"), "cancel_order", true);
            } else if (page == Page.OVERVIEW) {
                bar(text("stress_only", number(state, "stress")), number(state, "stress") / 100);
                row(text("loyalty_only", number(state, "loyalty")));
                row(text("life", number(state, "health"), number(state, "hunger"), number(state, "rest") * 100));
                if (state.has("needs")) {
                    var needs = Objects.requireNonNull(state.getAsJsonObject("needs"));
                    row(text("water_status", number(needs, "hydration"))); row(text("temperature_status", number(needs, "temperature")));
                }
                row(text("professions", label("profession", word(state, "primary", "NONE")), label("profession", word(state, "secondary", "NONE"))));
                row(text(flag(state, "homeValid") ? "home" : "homeless", state.has("home") ? position(Objects.requireNonNull(BlockPos.of(state.get("home").getAsLong()))) : text("none")));
                row(text("pressure_toggle", pressureExpanded ? "−" : "+"), "pressure", true);
                if (pressureExpanded && state.has("pressure")) for (var entry : state.getAsJsonArray("pressure")) {
                    JsonObject pressure = Objects.requireNonNull(entry.getAsJsonObject()); row(text("source", label("stresscause", word(pressure, "id", "")), number(pressure, "amount")));
                }
            } else if (page == Page.GROWTH) {
                row(text("interest", label("interest", word(state, "interest", "NONE")))); row(text("temperament", label("temperament", word(state, "temperament", "NONE"))));
                row(text("open_learning"), "open_learning", true); row(text("growth_rule"));
                if (state.has("attributes")) for (var entry : state.getAsJsonArray("attributes")) {
                    JsonObject stat = Objects.requireNonNull(entry.getAsJsonObject()); String id = word(stat, "id", "");
                    bar(text("attribute", label("attribute", id), word(stat, "points", "1"), text("effect." + id.toLowerCase(java.util.Locale.ROOT), number(stat, "effect"))), number(stat, "progress"));
                }
                if (state.has("abilities")) for (var entry : state.getAsJsonArray("abilities")) {
                    JsonObject ability = Objects.requireNonNull(entry.getAsJsonObject()); row(text("profession_detail", label("profession", word(ability, "profession", "NONE")), text("ability." + word(ability, "id", "NONE").toLowerCase(java.util.Locale.ROOT))));
                    row(text(flag(ability, "implemented") ? "ability_active" : "ability_pending"));
                }
                row(text("mined", worker.mined()));
            } else if (page == Page.LIFE) {
                row(text(flag(state, "homeValid") ? "home" : "homeless", state.has("home") ? position(Objects.requireNonNull(BlockPos.of(state.get("home").getAsLong()))) : text("none")));
                row(text("assign_home"), "home", writable);
                if (state.has("needs")) {
                    var needs = Objects.requireNonNull(state.getAsJsonObject("needs"));
                    row(text("needs", number(needs, "hydration"), number(needs, "temperature"), number(needs, "social")));
                    row(text("need_speed", number(needs, "workSpeed") * 100)); row(text("life_practice", number(needs, "drinks"), number(needs, "meals")));
                }
                for (var facility : ColonyWorkClient.facilities()) if (facility.kind().equals("WATER_PUMP") || facility.kind().equals("MEETING_POINT"))
                    row(text("life_facility", Objects.requireNonNull(Component.translatable("block.colonycamera." + (facility.kind().equals("WATER_PUMP") ? "water_pump" : "meeting_point"))), position(facility.pos()), Objects.requireNonNull(Component.translatable("colonycamera.life.facility." + word(facility.detail(), "status", "denied")))));
                row(text("water_rule")); row(text("climate_rule")); row(text("loyalty_care_rule"));
            } else if (page == Page.SOCIAL) {
                row(text("social_rule")); row(text("relationships"));
                if (!state.has("relationships") || state.getAsJsonArray("relationships").isEmpty()) row(text("no_relations"));
                else for (var entry : state.getAsJsonArray("relationships")) {
                    var relation = Objects.requireNonNull(entry.getAsJsonObject()); double affinity = number(relation, "affinity");
                    row(text("relation", word(relation, "name", ""), text("bond." + (affinity >= 60 ? "close" : affinity >= 20 ? "friend" : affinity >= 5 ? "familiar" : "met")), affinity));
                    row(text("relation_time", number(relation, "seconds"), text(flag(relation, "loaded") ? "relation_nearby" : "relation_unloaded")));
                }
            } else if (page == Page.LEARNING) {
                row(text("school", position(school())), "school", !schools().isEmpty());
                row(text("school_rule")); row(text("profession", label(profession)), "profession", true);
                row(text("profession_detail", label(WorkerProfile.layer(profession)), text("ability." + WorkerProfile.ability(profession).name().toLowerCase(java.util.Locale.ROOT))));
                row(text(WorkerProfile.abilityImplemented(WorkerProfile.ability(profession)) ? "ability_active" : "ability_pending"));
                row(text("profession_bonus", label("attribute", Objects.requireNonNull(WorkerProfile.focus(profession).name())),
                        label("attribute", Objects.requireNonNull(WorkerProfile.secondaryFocus(profession).name())), WorkerProfile.schoolLevel(profession)));
                row(text("learn_profession"), "learn_profession", writable && school() != null && flag(state, "homeValid"));
                row(text("learn_second"), "learn_second", writable && school() != null && flag(state, "homeValid"));
                row(text("attribute_choice", label("attribute", Objects.requireNonNull(attribute.name()))), "attribute", true);
                row(text("course_tier", tier), "tier", true); row(text("learn_attribute"), "learn_attribute", writable && school() != null && flag(state, "homeValid"));
                row(text("forced_study_rule"));
            } else if (page == Page.ORDERS) {
                row(text("order_rule", number(ColonyWorkClient.workerRules(), "executeLoyalty"), number(ColonyWorkClient.workerRules(), "sacrificeLoyalty")));
                row(text("target", targetName()), "target", !ColonyWorkClient.targets().isEmpty());
                row(text("execute"), "execute", writable && target != null);
                row(text("undertaking", undertakingName()), "undertaking", !undertakings().isEmpty());
                row(text("sacrifice"), "sacrifice", writable && undertaking != null);
                row(text("danger_rule")); row(text("witness_rule"));
            } else if (page == Page.TASKS) {
                row(text("task_rule"));
                JsonObject preferences = state.has("preferences") ? Objects.requireNonNull(state.getAsJsonObject("preferences")) : new JsonObject();
                for (var task : WorkerPreferences.Task.values()) row(text("task", text("task." + task.name().toLowerCase(java.util.Locale.ROOT)), text(flag(preferences, Objects.requireNonNull(task.name())) ? "enabled" : "disabled")), "task_" + task.name(), writable);
                row(text("cargo_rule")); row(text("open_orders"), "open_orders", writable);
            } else if (page == Page.EQUIPMENT) {
                row(text("equipment_rule"));
                if (state.has("equipment")) for (var entry : state.getAsJsonArray("equipment")) {
                    JsonObject equipped = Objects.requireNonNull(entry.getAsJsonObject()); String slot = word(equipped, "slot", "OFFHAND");
                    String name = word(equipped, "name", "");
                    row(text("equipment", text("slot." + slot.toLowerCase(java.util.Locale.ROOT)), name.isEmpty() ? text("none") : Component.literal(name)), "equip_" + slot, writable);
                    if (number(equipped, "maxDamage") > 0) row(text("durability", (int) (number(equipped, "maxDamage") - number(equipped, "damage")), (int) number(equipped, "maxDamage")));
                    if (!word(equipped, "item", "minecraft:air").equals("minecraft:air")) row(text("unequip", text("slot." + slot.toLowerCase(java.util.Locale.ROOT))), "unequip_" + slot, writable);
                }
                JsonObject preferences = state.has("preferences") ? Objects.requireNonNull(state.getAsJsonObject("preferences")) : new JsonObject();
                row(text("automatic_tools", text(flag(preferences, "automaticTools") ? "enabled" : "disabled")), "automatic_tools", writable);
            }
            if (pendingOrder == null && state.has("directive")) {
                JsonObject directive = Objects.requireNonNull(state.getAsJsonObject("directive"));
                row(text("directive", text("directive." + word(directive, "kind", "").toLowerCase(java.util.Locale.ROOT)), word(directive, "course", "")));
                rows.add(new Row(text("learning_progress", number(directive, "progress") / Math.max(1, number(directive, "duration")) * 100), null, true,
                        number(directive, "progress") / Math.max(1, number(directive, "duration"))));
                row(text("stop"), "stop", writable);
            }
        }
        relayout(); scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows())));
    }
    private void act(String action) {
        if (action.equals("pressure")) { pressureExpanded = !pressureExpanded; return; }
        if (action.equals("open_learning") || action.equals("open_orders")) { page = action.equals("open_learning") ? Page.LEARNING : Page.ORDERS; scroll = 0; relayout(); return; }
        if (action.equals("cancel_order")) { pendingOrder = null; scroll = 0; relayout(); return; }
        if (action.equals("confirm_order")) { JsonObject intent = pendingOrder; pendingOrder = null; if (intent != null) ColonyWorkClient.request("worker", intent); scroll = 0; relayout(); return; }
        var selectedWorker = worker();
        if (selectedWorker != null && action.startsWith("task_")) {
            String task = Objects.requireNonNull(action.substring(5)); JsonObject payload = personIntent("task"); payload.addProperty("task", task);
            payload.addProperty("enabled", !flag(Objects.requireNonNull(selectedWorker.state().getAsJsonObject("preferences")), task)); ColonyWorkClient.request("worker", payload); return;
        }
        if (action.startsWith("equip_") || action.startsWith("unequip_")) {
            var slot = Objects.requireNonNull(net.minecraft.world.entity.EquipmentSlot.valueOf(Objects.requireNonNull(action.substring(action.startsWith("unequip_") ? 8 : 6))));
            if (action.startsWith("unequip_")) { JsonObject payload = personIntent("equipment"); payload.addProperty("slot", slot.name()); payload.addProperty("source", -1); ColonyWorkClient.request("worker", payload); }
            else equipment.equipment(slot, chosen -> {
                JsonObject payload = personIntent("equipment"); payload.addProperty("slot", slot.name()); payload.addProperty("source", chosen.inventorySlot());
                payload.addProperty("expected", chosen.icon().save(new net.minecraft.nbt.CompoundTag()).toString()); ColonyWorkClient.request("worker", payload);
            });
            return;
        }
        if (action.equals("automatic_tools") && selectedWorker != null) { JsonObject payload = personIntent(action); payload.addProperty("enabled", !flag(Objects.requireNonNull(selectedWorker.state().getAsJsonObject("preferences")), "automaticTools")); ColonyWorkClient.request("worker", payload); return; }
        switch (action) {
            case "interest" -> { interest = Objects.requireNonNull(WorkerProfile.Interest.values()[(interest.ordinal() + 1) % WorkerProfile.Interest.values().length]); if (!WorkerProfile.compatible(interest, temperament)) temperament = WorkerProfile.Temperament.NONE; }
            case "temperament" -> { do { temperament = Objects.requireNonNull(WorkerProfile.Temperament.values()[(temperament.ordinal() + 1) % WorkerProfile.Temperament.values().length]); } while (!WorkerProfile.compatible(interest, temperament)); }
            case "profession" -> profession = Objects.requireNonNull(WorkerProfile.Profession.values()[profession.ordinal() == WorkerProfile.Profession.values().length - 1 ? 1 : profession.ordinal() + 1]);
            case "attribute" -> attribute = Objects.requireNonNull(WorkerAttribute.values()[(attribute.ordinal() + 1) % WorkerAttribute.values().length]);
            case "tier" -> tier = tier % 3 + 1;
            case "school" -> { var list = schools(); if (!list.isEmpty()) school = list.get((list.indexOf(school) + 1) % list.size()); }
            case "core" -> { var list = ConstructionClient.corePositions(); if (!list.isEmpty()) core = list.get((list.indexOf(core) + 1) % list.size()); }
            case "target" -> { var list = ColonyWorkClient.targets(); if (!list.isEmpty()) { int index = -1; for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(target)) index = i; target = list.get((index + 1) % list.size()).id(); } }
            case "undertaking" -> { var list = undertakings(); if (!list.isEmpty()) undertaking = list.get((list.indexOf(undertaking) + 1) % list.size()); }
            case "print" -> { BlockPos printingCore = core(); if (printingCore != null) { JsonObject payload = new JsonObject(); payload.addProperty("core", printingCore.asLong()); payload.addProperty("interest", interest.name()); payload.addProperty("temperament", temperament.name()); ColonyWorkClient.request("print", payload); } }
            default -> {
                UUID selectedPerson = person;
                if (selectedPerson == null) return;
                JsonObject payload = new JsonObject(); payload.addProperty("id", selectedPerson.toString());
                switch (action) {
                    case "learn_profession", "learn_second", "learn_attribute" -> {
                        BlockPos selectedSchool = school(); if (selectedSchool == null) return;
                        payload.addProperty("station", selectedSchool.asLong()); payload.addProperty("operation", action.equals("learn_attribute") ? "study" : "profession");
                        payload.addProperty("course", action.equals("learn_attribute") ? attribute.name() : profession.name()); payload.addProperty("additional", action.equals("learn_second")); payload.addProperty("tier", tier);
                    }
                    case "execute", "sacrifice" -> { UUID selectedTarget = action.equals("execute") ? target : undertaking; if (selectedTarget == null) return; payload.addProperty("operation", action); payload.addProperty("target", selectedTarget.toString()); }
                    default -> payload.addProperty("operation", action);
                }
                if (action.equals("execute") || action.equals("sacrifice")) { pendingOrder = payload; scroll = 0; relayout(); }
                else ColonyWorkClient.request("worker", payload);
            }
        }
    }
    @Nonnull private JsonObject personIntent(String operation) { JsonObject payload = new JsonObject(); payload.addProperty("id", Objects.requireNonNull(person).toString()); payload.addProperty("operation", operation); return payload; }
    @Nullable private ColonyWorkClient.Worker worker() { return ColonyWorkClient.workers().stream().filter(value -> value.id().equals(person)).findFirst().orElse(null); }
    @Nonnull private List<BlockPos> schools() { return Objects.requireNonNull(ColonyWorkClient.facilities().stream().filter(value -> value.kind().equals("ACADEMY") && value.valid()).map(value -> Objects.requireNonNull(value).pos()).toList()); }
    @Nullable private BlockPos school() { var list = schools(); if (school == null && !list.isEmpty()) school = list.get(0); return list.contains(school) ? school : null; }
    @Nullable private BlockPos core() { var list = ConstructionClient.corePositions(); if (core == null && !list.isEmpty()) core = list.get(0); return list.contains(core) ? core : null; }
    @Nonnull private List<UUID> undertakings() {
        List<UUID> list = new ArrayList<>(); for (var job : ColonyWorkClient.jobs()) if (!job.cancelled() && !job.finished()) list.add(job.id());
        for (var project : ConstructionClient.projects()) if (!project.cancelled() && project.completed() < project.total()) list.add(project.id()); return list;
    }
    @Nonnull private Component undertakingName() {
        for (var job : ColonyWorkClient.jobs()) if (job.id().equals(undertaking)) return ColonyWorkClient.text(job.name());
        for (var project : ConstructionClient.projects()) if (project.id().equals(undertaking)) return ColonyWorkClient.text(project.name()); return text("none");
    }
    @Nonnull private Component targetName() { for (var value : ColonyWorkClient.targets()) if (value.id().equals(target)) return Objects.requireNonNull(Component.literal(value.name() + " · " + value.id().toString().substring(0, 8))); return text("none"); }
    private void row(Component label) {
        Font current = font;
        if (current == null || layout.content.width() < 12) { rows.add(new Row(label, null, true, -1)); return; }
        for (FormattedCharSequence line : current.split(label, layout.content.width() - 6)) rows.add(new Row(label, null, true, -1, Objects.requireNonNull(line)));
    }
    private void bar(Component label, double fraction) {
        int before = rows.size(); row(label);
        if (rows.size() > before) { Row last = Objects.requireNonNull(rows.remove(rows.size() - 1)); rows.add(new Row(last.text(), null, true, Math.max(0, Math.min(1, fraction)), last.line())); }
    }
    private void row(Component label, String action, boolean enabled) { rows.add(new Row(label, action, enabled, -1)); }
    @Nonnull private static Component label(Enum<?> value) { return Objects.requireNonNull(Component.translatable(WorkerProfile.key(value))); }
    @Nonnull private static Component label(String type, String value) { return Objects.requireNonNull(Component.translatable("colonycamera.worker." + type + "." + value.toLowerCase(java.util.Locale.ROOT))); }
    @Nonnull private static Component position(@Nullable BlockPos pos) { return pos == null ? text("none") : Objects.requireNonNull(Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())); }
    @Nonnull private static Component text(String key, Object... args) {
        Object[] formatted = args.clone();
        for (int i = 0; i < formatted.length; i++) if (formatted[i] instanceof Double value) formatted[i] = String.format(java.util.Locale.ROOT, "%.2f", value);
        return Objects.requireNonNull(Component.translatable("colonycamera.worker.ui." + key, formatted));
    }
    @Nonnull private static String word(JsonObject value, String key, String fallback) { return value.has(key) ? Objects.requireNonNull(value.get(key).getAsString()) : fallback; }
    private static double number(JsonObject value, String key) { return value.has(key) ? value.get(key).getAsDouble() : 0; }
    private static boolean flag(JsonObject value, String key) { return value.has(key) && value.get(key).getAsBoolean(); }
    private static void draw(GuiGraphics graphics, Font font, Component text, int x, int y, int width, int colour) {
        UiDraw.fitted(graphics, font, text, x, y, width, colour);
    }
}
