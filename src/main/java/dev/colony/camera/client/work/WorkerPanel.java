package dev.colony.camera.client.work;

import com.google.gson.JsonObject;
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
    private enum Page { OVERVIEW, LEARNING, ORDERS, TASKS, EQUIPMENT, PRINT }
    @Nonnull private final RegistryPicker equipment = new RegistryPicker();
    private record Row(@Nonnull Component text, @Nullable String action, boolean enabled, double bar) {}
    @Nullable private UUID person, target, undertaking;
    @Nullable private BlockPos school, core;
    @Nonnull private WorkerProfile.Profession profession = WorkerProfile.Profession.MINER;
    @Nonnull private WorkerAttribute attribute = WorkerAttribute.MINING;
    @Nonnull private WorkerProfile.Interest interest = WorkerProfile.Interest.NONE;
    @Nonnull private WorkerProfile.Temperament temperament = WorkerProfile.Temperament.NONE;
    @Nonnull private Page page = Page.OVERVIEW;
    @Nonnull private final List<Row> rows = new ArrayList<>();
    @Nullable private Font font;
    private int x, y, width, height, scroll, tier = 1;
    private boolean open;
    public boolean isOpen() { return open; }
    public void resize(Font value, int screenWidth, int screenHeight) {
        font = value; width = Math.max(1, Math.min(400, screenWidth - 16)); height = Math.max(1, Math.min(320, screenHeight - 16));
        x = Math.max(0, (screenWidth - width) / 2); y = Math.max(0, (screenHeight - height) / 2);
        equipment.resize(value, screenWidth, screenHeight);
    }
    public void open(UUID id) { person = id; page = Page.OVERVIEW; scroll = 0; open = true; equipment.close(); ColonyWorkClient.refresh(); }
    public void print() { person = null; page = Page.PRINT; scroll = 0; open = true; equipment.close(); ColonyWorkClient.refresh(); }
    public void close() { open = false; equipment.close(); }
    public void tick() { if (open) equipment.tick(); }
    public boolean charTyped(char ch, int modifiers) { if (!open) return false; equipment.charTyped(ch, modifiers); return true; }
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!open) return false; if (equipment.keyPressed(key, scan, modifiers)) return true; if (key == GLFW.GLFW_KEY_ESCAPE) close();
        else if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) scroll += key == GLFW.GLFW_KEY_DOWN ? 1 : -1;
        return true;
    }
    public boolean scroll(double delta) { if (!open) return false; if (equipment.scroll(delta)) return true; if (Double.isFinite(delta)) scroll -= (int) Math.signum(delta); return true; }
    public boolean click(double mouseX, double mouseY, int button) {
        if (!open) return false; if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (equipment.click(mouseX, mouseY, button)) return true;
        rebuild();
        if (mouseX >= x + width - 24 && mouseX < x + width && mouseY >= y && mouseY < y + 22) { close(); return true; }
        if (width < 160 || height < 100) return true;
        if (page != Page.PRINT && mouseX >= x + 6 && mouseX < x + width - 6 && mouseY >= y + 24 && mouseY < y + 44) {
            int tab = Math.min(4, (int) ((mouseX - x - 6) * 5 / Math.max(1, width - 12))); page = Objects.requireNonNull(Page.values()[tab]); scroll = 0; return true;
        }
        int top = y + 49, bottom = y + height - 22;
        if (mouseX < x + 8 || mouseX >= x + width - 8 || mouseY < top || mouseY >= bottom) return true;
        int row = scroll + (int) ((mouseY - top) / 20);
        if (row >= 0 && row < rows.size()) {
            Row value = Objects.requireNonNull(rows.get(row));
            String action = value.action();
            if (value.enabled() && action != null) act(action);
        }
        return true;
    }
    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        Font current = font; if (!open || current == null) return; rebuild();
        if (equipment.isOpen()) { equipment.render(graphics, mouseX, mouseY); return; }
        graphics.fill(x, y, x + width, y + height, 0xFF142432); graphics.fill(x, y, x + width, y + 2, 0xFF67DEC2);
        draw(graphics, current, text(page == Page.PRINT ? "print_title" : "title", person == null ? "" : person.toString().substring(0, 8)), x + 9, y + 9, width - 42, 0xE8F1F5);
        graphics.drawString(current, "×", x + width - 17, y + 8, 0x90A8B8, false);
        if (width < 160 || height < 100) { draw(graphics, current, Objects.requireNonNull(Component.translatable("colonycamera.work.small_screen")), x + 5, y + 27, width - 10, 0xFFD379); return; }
        if (page != Page.PRINT) for (int i = 0; i < 5; i++) {
            int left = x + 6 + (width - 12) * i / 5, right = x + 6 + (width - 12) * (i + 1) / 5;
            graphics.fill(left, y + 24, right - 1, y + 43, page.ordinal() == i ? 0xFF28504C : 0xFF203442);
            draw(graphics, current, text("page." + Objects.requireNonNull(Page.values()[i]).name().toLowerCase(java.util.Locale.ROOT)), left + 4, y + 30, right - left - 8, 0xE8F1F5);
        }
        int top = y + 49, bottom = y + height - 22; graphics.enableScissor(x + 7, top, x + width - 7, bottom);
        for (int index = scroll; index < rows.size(); index++) {
            Row row = Objects.requireNonNull(rows.get(index)); int rowY = top + (index - scroll) * 20; if (rowY >= bottom) break;
            if (row.action() != null) graphics.fill(x + 8, rowY, x + width - 8, rowY + 18, row.enabled() ? 0xFF28443F : 0xFF26343F);
            draw(graphics, current, row.text(), x + 12, rowY + 5, width - 24, row.enabled() ? 0xE8F1F5 : 0x90A8B8);
            if (row.bar() >= 0) { graphics.fill(x + 12, rowY + 17, x + width - 12, rowY + 19, 0xFF344957); graphics.fill(x + 12, rowY + 17, x + 12 + (int) ((width - 24) * Math.min(1, row.bar())), rowY + 19, 0xFF67DEC2); }
        }
        graphics.disableScissor(); draw(graphics, current, ColonyWorkClient.notice(), x + 10, y + height - 14, width - 20, 0xFFD379);
    }
    private int visibleRows() { return Math.max(1, (height - 71) / 20); }
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
            if (worker == null) { row(text("unavailable")); scroll = 0; return; }
            JsonObject state = worker.state(); boolean writable = !flag(state, "readOnly");
            row(text("status", text("reason." + word(state, "reason", "idle"))));
            if (!writable) { row(text("read_only")); scroll = 0; return; }
            if (page == Page.OVERVIEW) {
                row(text("interest", label("interest", word(state, "interest", "NONE")))); row(text("temperament", label("temperament", word(state, "temperament", "NONE"))));
                row(text("professions", label("profession", word(state, "primary", "NONE")), label("profession", word(state, "secondary", "NONE"))));
                if (state.has("abilities")) for (var entry : state.getAsJsonArray("abilities")) {
                    JsonObject ability = Objects.requireNonNull(entry.getAsJsonObject());
                    row(text("profession_detail", label("profession", word(ability, "profession", "NONE")), text("ability." + word(ability, "id", "NONE").toLowerCase(java.util.Locale.ROOT))));
                    row(text(flag(ability, "implemented") ? "ability_active" : "ability_pending"));
                }
                rows.add(new Row(text("stress", number(state, "stress"), number(state, "loyalty")), null, true, number(state, "stress") / 100));
                row(text("life", number(state, "health"), number(state, "hunger"), number(state, "rest") * 100));
                row(text(flag(state, "homeValid") ? "home" : "homeless", state.has("home") ? position(Objects.requireNonNull(BlockPos.of(state.get("home").getAsLong()))) : text("none")));
                row(text("assign_home"), "home", writable); row(text("growth_rule"));
                if (state.has("attributes")) for (var entry : state.getAsJsonArray("attributes")) {
                    JsonObject stat = Objects.requireNonNull(entry.getAsJsonObject()); String id = word(stat, "id", "");
                    rows.add(new Row(text("attribute", label("attribute", id), word(stat, "points", "1"), text("effect." + id.toLowerCase(java.util.Locale.ROOT), number(stat, "effect"))), null, true, number(stat, "progress")));
                }
                row(text("sources"));
                if (state.has("pressure")) for (var entry : state.getAsJsonArray("pressure")) {
                    JsonObject pressure = Objects.requireNonNull(entry.getAsJsonObject()); row(text("source", label("stresscause", word(pressure, "id", "")), number(pressure, "amount")));
                }
                row(text("mined", worker.mined()));
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
                row(text("cargo_rule"));
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
            if (state.has("directive")) {
                JsonObject directive = Objects.requireNonNull(state.getAsJsonObject("directive"));
                row(text("directive", text("directive." + word(directive, "kind", "").toLowerCase(java.util.Locale.ROOT)), word(directive, "course", "")));
                rows.add(new Row(text("learning_progress", number(directive, "progress") / Math.max(1, number(directive, "duration")) * 100), null, true,
                        number(directive, "progress") / Math.max(1, number(directive, "duration"))));
                row(text("stop"), "stop", writable);
            }
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows())));
    }
    private void act(String action) {
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
                ColonyWorkClient.request("worker", payload);
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
    private void row(Component label) { rows.add(new Row(label, null, true, -1)); }
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
        if (width > 0) graphics.drawString(font, Objects.requireNonNull(font.plainSubstrByWidth(Objects.requireNonNull(text.getString()), width)), x, y, colour, false);
    }
}
