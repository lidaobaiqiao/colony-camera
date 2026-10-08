package dev.colony.camera.client.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.screen.StrategyScreen;
import dev.colony.camera.client.selection.SelectionState;
import dev.colony.camera.network.ConstructionPayload;
import dev.colony.camera.work.WorkNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Client intentions and bounded server views. World changes remain server-side. */
@ParametersAreNonnullByDefault
@Mod.EventBusSubscriber(modid = ColonyCamera.ID, value = Dist.CLIENT)
public final class ColonyWorkClient {
    public record Job(@Nonnull UUID id, @Nonnull String kind, @Nonnull String name, int priority,
                      int done, int total, @Nonnull String reason, boolean paused, boolean cancelled, boolean finished,
                      long sequence, @Nullable BlockPos origin, boolean supply) {}
    public record Worker(@Nonnull UUID id, int miningLevel, int mined, @Nonnull String tool,
                         @Nullable UUID job, @Nullable UUID project, @Nonnull JsonObject state) {}
    public record Facility(@Nonnull BlockPos pos, @Nonnull String kind, int level, boolean valid, boolean occupied, @Nonnull JsonObject detail) {}
    public record PersonTarget(@Nonnull UUID id, @Nonnull String name) {}
    public record Station(@Nonnull BlockPos pos, boolean research) {}
    public record Ingredient(@Nonnull String item, @Nonnull String name, int count, @Nonnull List<String> alternatives) {}
    public record Recipe(@Nonnull String id, @Nonnull String name, @Nonnull String output, int count,
                         int requiredTier, boolean unlocked, @Nonnull List<Ingredient> ingredients) {}
    public record Tech(int tier, @Nonnull String name, int nextTier, @Nonnull List<Ingredient> costs, int researchTicks, boolean readOnly) {}

    public record TechNode(int tier, @Nonnull String name, int requiredTier, int researchTicks, @Nonnull List<Ingredient> costs) {}
    @Nonnull private static List<TechNode> techNodes = Objects.requireNonNull(List.of());
    private static long noticeTime;
    @Nonnull public static List<TechNode> techNodes() { return techNodes; }
    public static boolean recentNotice() { return noticeTime != 0 && System.nanoTime() - noticeTime < 4_000_000_000L; }

    @Nonnull private static List<Job> jobs = Objects.requireNonNull(List.of());
    @Nonnull private static List<Worker> workers = Objects.requireNonNull(List.of());
    @Nonnull private static List<Station> stations = Objects.requireNonNull(List.of());
    @Nonnull private static List<Facility> facilities = Objects.requireNonNull(List.of());
    @Nonnull private static List<PersonTarget> targets = Objects.requireNonNull(List.of());
    @Nonnull private static JsonObject workerRules = new JsonObject();
    @Nonnull public static List<Facility> facilities() { return facilities; }
    @Nonnull public static List<PersonTarget> targets() { return targets; }
    @Nonnull public static JsonObject workerRules() { return workerRules; }
    @Nonnull private static List<Recipe> recipes = Objects.requireNonNull(List.of());
    @Nonnull private static Tech tech = new Tech(0, "colonycamera.work.tier.0", 1, Objects.requireNonNull(List.of()), 0, false);
    @Nonnull private static Component notice = Objects.requireNonNull(Component.empty());
    @Nullable private static ClientLevel currentLevel;
    private ColonyWorkClient() {}

    @Nonnull public static List<Job> jobs() { return jobs; }
    @Nonnull public static List<Worker> workers() { return workers; }
    @Nonnull public static List<Station> stations() { return stations; }
    @Nonnull public static List<Recipe> recipes() { return recipes; }
    @Nonnull public static Tech tech() { return tech; }
    @Nonnull public static Component notice() { return notice; }
    public static void refresh() { request("list", new JsonObject()); }

    public static void mine() {
        var selection = StrategyCameraController.instance().selection();
        if (selection.working() || selection.selectingHeight()) { localNotice("selection_pending"); return; }
        var cells = selection.selectedCells();
        if (cells.isEmpty()) { localNotice("empty_selection"); return; }
        if (cells.size() > 8192) { localNotice("selection_large"); return; }
        JsonArray targets = new JsonArray();
        cells.stream().sorted(Comparator.comparingInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).x()).thenComparingInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).y())
                .thenComparingInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).z())).forEach(cell -> targets.add(new BlockPos(cell.x(), cell.y(), cell.z()).asLong()));
        JsonObject payload = new JsonObject(); payload.add("targets", targets); request("mine", payload);
    }

    public static void sweep() {
        var selection = StrategyCameraController.instance().selection();
        if (selection.working() || selection.selectingHeight()) { localNotice("selection_pending"); return; }
        var cells = selection.selectedCells();
        if (cells.isEmpty()) { localNotice("empty_selection"); return; }
        if (cells.size() > 8192) { localNotice("selection_large"); return; }
        JsonArray targets = new JsonArray();
        for (var cell : cells) targets.add(new BlockPos(cell.x(), cell.y(), cell.z()).asLong());
        JsonObject payload = new JsonObject(); payload.add("targets", targets);
        request("sweep", payload);
    }

    public static void craft(BlockPos station, Recipe recipe, int count) {
        craft(station, recipe.id(), count);
    }
    public static void craft(BlockPos station, String recipe, int count) {
        JsonObject payload = new JsonObject(); payload.addProperty("station", station.asLong());
        payload.addProperty("recipe", recipe); payload.addProperty("count", Math.max(1, Math.min(64, count)));
        request("craft", payload);
    }
    public static void research(BlockPos station) {
        JsonObject payload = new JsonObject(); payload.addProperty("station", station.asLong()); request("research", payload);
    }
    public static void control(String action, UUID id, int value) {
        JsonObject payload = new JsonObject(); payload.addProperty("id", id.toString()); payload.addProperty("value", value);
        request(action, payload);
    }
    public static void request(String action, JsonObject payload) {
        String json = Objects.requireNonNull(payload.toString());
        try { ConstructionPayload.encode(json); }
        catch (IllegalArgumentException large) { localNotice("selection_large"); return; }
        WorkNetwork.CHANNEL.sendToServer(new WorkNetwork.Request(action, json));
    }
    private static void localNotice(String suffix) { notice = text("colonycamera.work." + suffix); noticeTime = System.nanoTime(); }

    @SubscribeEvent public static void receive(WorkNetwork.SnapshotEvent event) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().toString().equals(event.snapshot.dimension())) return;
        try {
            JsonObject root = Objects.requireNonNull(JsonParser.parseString(event.snapshot.json()).getAsJsonObject());
            List<Job> newJobs = new ArrayList<>();
            for (JsonElement entry : array(root, "jobs")) {
                JsonObject job = Objects.requireNonNull(entry.getAsJsonObject());
                newJobs.add(new Job(Objects.requireNonNull(UUID.fromString(string(job, "id", ""))), string(job, "kind", "MINE"),
                        string(job, "name", ""), integer(job, "priority", 5), integer(job, "done", 0), integer(job, "total", 0),
                        string(job, "reason", "queued"), bool(job, "paused", false), bool(job, "cancelled", false),
                        bool(job, "finished", integer(job, "done", 0) >= integer(job, "total", 0)), longValue(job, "sequence", 0),
                        job.has("pos") ? BlockPos.of(longValue(job, "pos", 0)) : null, bool(job, "supply", false)));
            }
            List<Worker> newWorkers = new ArrayList<>();
            for (JsonElement entry : array(root, "workers")) {
                JsonObject worker = Objects.requireNonNull(entry.getAsJsonObject());
                newWorkers.add(new Worker(Objects.requireNonNull(UUID.fromString(string(worker, "id", ""))), integer(worker, "miningLevel", 0),
                        integer(worker, "mined", 0), string(worker, "tool", ""),
                        worker.has("job") ? UUID.fromString(string(worker, "job", "")) : null,
                        worker.has("project") ? UUID.fromString(string(worker, "project", "")) : null,
                        worker.has("state") ? Objects.requireNonNull(worker.getAsJsonObject("state")) : new JsonObject()));
            }
            List<Station> newStations = new ArrayList<>();
            for (JsonElement entry : array(root, "stations")) {
                JsonObject station = Objects.requireNonNull(entry.getAsJsonObject());
                newStations.add(new Station(Objects.requireNonNull(BlockPos.of(longValue(station, "pos", 0))), bool(station, "research", false)));
            }
            List<Recipe> newRecipes = new ArrayList<>();
            List<Facility> newFacilities = new ArrayList<>(); List<PersonTarget> newTargets = new ArrayList<>();
            for (JsonElement entry : array(root, "livingFacilities")) {
                JsonObject facility = Objects.requireNonNull(entry.getAsJsonObject());
                newFacilities.add(new Facility(Objects.requireNonNull(BlockPos.of(longValue(facility, "pos", 0))), string(facility, "kind", ""), integer(facility, "level", 1), bool(facility, "valid", false), bool(facility, "occupied", false), facility));
            }
            for (JsonElement entry : array(root, "peopleTargets")) {
                JsonObject target = Objects.requireNonNull(entry.getAsJsonObject()); newTargets.add(new PersonTarget(Objects.requireNonNull(UUID.fromString(string(target, "id", ""))), string(target, "name", "")));
            }
            JsonObject newRules = root.has("workerRules") ? Objects.requireNonNull(root.getAsJsonObject("workerRules")) : new JsonObject();
            for (JsonElement entry : array(root, "recipes")) {
                JsonObject recipe = Objects.requireNonNull(entry.getAsJsonObject());
                newRecipes.add(new Recipe(string(recipe, "id", ""), string(recipe, "name", ""), string(recipe, "output", ""),
                        integer(recipe, "count", 1), integer(recipe, "requiredTier", 0), bool(recipe, "unlocked", false), ingredients(recipe, "ingredients")));
            }
            Tech newTech = tech; List<TechNode> newNodes = new ArrayList<>();
            if (root.has("tech") && root.get("tech").isJsonObject()) {
                JsonObject value = Objects.requireNonNull(root.getAsJsonObject("tech")); int tier = integer(value, "tier", 0);
                newTech = new Tech(tier, string(value, "name", "colonycamera.work.tier." + tier), integer(value, "nextTier", -1),
                        ingredients(value, "costs"), integer(value, "researchTicks", 0), bool(value, "readOnly", false));
            }
            if (root.has("tech")) {
                JsonObject value = Objects.requireNonNull(root.getAsJsonObject("tech"));
                for (JsonElement entry : array(value, "nodes")) {
                    JsonObject node = Objects.requireNonNull(entry.getAsJsonObject());
                    newNodes.add(new TechNode(integer(node, "tier", 0), string(node, "name", ""), integer(node, "requiredTier", -1),
                            integer(node, "researchTicks", 0), ingredients(node, "costs")));
                }
            }
            Component newNotice = notice; boolean hasNotice = false;
            if (root.has("notice") && !root.get("notice").isJsonNull()) {
                JsonElement value = Objects.requireNonNull(root.get("notice"));
                if (value.isJsonPrimitive() && !value.getAsString().isBlank()) { newNotice = text(Objects.requireNonNull(value.getAsString())); hasNotice = true; }
                else if (value.isJsonObject()) {
                    JsonObject message = Objects.requireNonNull(value.getAsJsonObject()); List<Object> args = new ArrayList<>();
                    for (JsonElement argument : array(message, "args")) args.add(argument.getAsString());
                    String key = string(message, "key", "");
                    if (!key.isBlank()) { newNotice = Objects.requireNonNull(Component.translatable(key, Objects.requireNonNull(args.toArray()))); hasNotice = true; }
                }
            }
            jobs = Objects.requireNonNull(List.copyOf(newJobs)); workers = Objects.requireNonNull(List.copyOf(newWorkers));
            stations = Objects.requireNonNull(List.copyOf(newStations)); recipes = Objects.requireNonNull(List.copyOf(newRecipes));
            facilities = Objects.requireNonNull(List.copyOf(newFacilities)); targets = Objects.requireNonNull(List.copyOf(newTargets)); workerRules = newRules;
            tech = newTech; techNodes = Objects.requireNonNull(List.copyOf(newNodes)); notice = newNotice; if (hasNotice) noticeTime = System.nanoTime(); currentLevel = level;
        } catch (RuntimeException malformed) {
            localNotice("snapshot_invalid");
        }
    }

    @SubscribeEvent public static void open(WorkNetwork.OpenStationEvent event) {
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (client.screen instanceof StrategyScreen strategy) strategy.openWorkStation(event.pos, event.research);
        else client.setScreen(new ColonyWorkScreen(event.pos, event.research));
    }
    @SubscribeEvent public static void openPeople(WorkNetwork.OpenPeopleEvent event) {
        var client = Minecraft.getInstance(); if (client.level == null || client.player == null) return;
        var residence = event.residence;
        if (residence != null) { if (client.screen instanceof StrategyScreen strategy) strategy.openResidence(residence); else client.setScreen(new ColonyWorkScreen(residence)); return; }
        if (client.screen instanceof StrategyScreen strategy) strategy.openWork(WorkPanel.Tab.PEOPLE);
        else client.setScreen(new ColonyWorkScreen());
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        var level = Minecraft.getInstance().level;
        if (event.phase == TickEvent.Phase.END && level != currentLevel) {
            jobs = Objects.requireNonNull(List.of()); workers = Objects.requireNonNull(List.of()); stations = Objects.requireNonNull(List.of());
            recipes = Objects.requireNonNull(List.of()); tech = new Tech(0, "colonycamera.work.tier.0", 1, Objects.requireNonNull(List.of()), 0, false);
            notice = Objects.requireNonNull(Component.empty()); noticeTime = 0; techNodes = Objects.requireNonNull(List.of()); currentLevel = level;
            facilities = Objects.requireNonNull(List.of()); targets = Objects.requireNonNull(List.of()); workerRules = new JsonObject();
        }
    }

    @Nonnull public static Component text(String value) {
        return Objects.requireNonNull(value.startsWith("colonycamera.") || value.startsWith("item.") || value.startsWith("block.")
                ? Component.translatable(value) : Component.literal(value));
    }
    @Nonnull private static List<Ingredient> ingredients(JsonObject parent, String key) {
        List<Ingredient> values = new ArrayList<>();
        for (JsonElement entry : array(parent, key)) {
            JsonObject ingredient = Objects.requireNonNull(entry.getAsJsonObject());
            List<String> alternatives = new ArrayList<>();
            for (JsonElement alternative : array(ingredient, "alternatives")) alternatives.add(Objects.requireNonNull(alternative.getAsString()));
            values.add(new Ingredient(string(ingredient, "item", ""), string(ingredient, "name", ""), integer(ingredient, "count", 1), Objects.requireNonNull(List.copyOf(alternatives))));
        }
        return Objects.requireNonNull(List.copyOf(values));
    }
    @Nonnull private static JsonArray array(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonArray() ? Objects.requireNonNull(value.getAsJsonArray(key)) : new JsonArray();
    }
    @Nonnull private static String string(JsonObject value, String key, String fallback) {
        return value.has(key) && !value.get(key).isJsonNull() ? Objects.requireNonNull(value.get(key).getAsString()) : fallback;
    }
    private static int integer(JsonObject value, String key, int fallback) { return value.has(key) ? value.get(key).getAsInt() : fallback; }
    private static long longValue(JsonObject value, String key, long fallback) { return value.has(key) ? value.get(key).getAsLong() : fallback; }
    private static boolean bool(JsonObject value, String key, boolean fallback) { return value.has(key) ? value.get(key).getAsBoolean() : fallback; }
}
