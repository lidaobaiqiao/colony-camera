package dev.colony.camera.tech;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.worker.Residences;
import dev.colony.camera.work.WorkStations;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.mojang.logging.LogUtils;

/** Real colony progress unlocks independent branches; tool tiers remain compatible with old jobs. */
@ParametersAreNonnullByDefault
public final class TechProgress extends SavedData {
    public static final int WATERWORKS = 4, INSULATION = 5, COMMUNITY = 6, EDUCATION = 7, NODE_COUNT = 8;
    public record Cost(@Nonnull Item item, int count) {}
    @Nonnull private final Map<UUID, Set<Integer>> unlocks = new LinkedHashMap<>();
    @Nullable private CompoundTag rejected;
    @Nonnull public static TechProgress get(ServerLevel level) {
        return Objects.requireNonNull(level.getDataStorage().computeIfAbsent(TechProgress::load, TechProgress::new, "colonycamera_technology"));
    }
    public int tier(UUID owner) {
        for (int stage = 3; stage > 0; stage--) if (unlocked(owner, stage)) return stage; return 0;
    }
    public boolean unlocked(UUID owner, int node) { return node == 0 || unlocks.getOrDefault(owner, Set.of()).contains(node); }
    public boolean readOnly() { return rejected != null; }
    public boolean advance(UUID owner, int expectedTier, Runnable materials) {
        if (readOnly() || expectedTier != tier(owner) || expectedTier < 0 || expectedTier >= 3) return false;
        materials.run(); grant(owner, expectedTier + 1); return true;
    }
    public boolean complete(ServerLevel level, UUID owner, int node, Runnable materials) {
        if (!researchReason(level, owner, node).equals("ready")) return false;
        materials.run(); grant(owner, node); return true;
    }
    private void grant(UUID owner, int node) { unlocks.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(node); setDirty(); }
    @Nonnull public static List<Integer> prerequisites(int node) {
        return Objects.requireNonNull(switch (node) {
            case 2 -> List.of(1); case 3 -> List.of(2); case INSULATION, COMMUNITY -> List.of(WATERWORKS);
            case EDUCATION -> List.of(COMMUNITY); default -> List.of();
        });
    }
    @Nonnull public String researchReason(ServerLevel level, UUID owner, int node) {
        if (readOnly()) return "tech_data";
        if (node < 1 || node >= NODE_COUNT) return "invalid";
        if (unlocked(owner, node)) return "researched";
        if (prerequisites(node).stream().anyMatch(parent -> !unlocked(owner, Objects.requireNonNull(parent)))) return "prerequisite";
        if (node <= 3) return "ready";
        var workers = ConstructionManager.get(level).workers(level).stream().filter(worker -> owner.equals(worker.owner()) && !worker.stateReadOnly() && worker.isAlive()).toList();
        if (node == WATERWORKS && workers.stream().noneMatch(worker -> worker.needs().drinks() > 0)) return "water_practice";
        if ((node == INSULATION || node == COMMUNITY) && Residences.rooms(level, owner).isEmpty()) return "housing";
        if (node == COMMUNITY && workers.stream().noneMatch(worker -> worker.needs().meals() > 0)) return "meals";
        if (node == EDUCATION) {
            if (workers.stream().noneMatch(worker -> worker.needs().relations().values().stream().anyMatch(relation -> relation.togetherTicks() >= 200))) return "social_practice";
            if (WorkStations.owned(level, owner).stream().noneMatch(pos -> level.getBlockEntity(Objects.requireNonNull(pos)) instanceof MachineBlockEntity machine
                    && machine.kind() == ColonyContent.Kind.ACADEMY && !machine.facilityReadOnly())) return "academy";
        }
        return "ready";
    }
    @Nonnull public static String name(int node) { return "colonycamera.tech." + switch (node) {
        case 1 -> "stone"; case 2 -> "iron"; case 3 -> "diamond"; case WATERWORKS -> "waterworks";
        case INSULATION -> "insulation"; case COMMUNITY -> "community"; case EDUCATION -> "education"; default -> "wood";
    }; }
    public static int researchTicks(int node) { return node >= 1 && node <= 3 ? node * 200 : switch (node) {
        case WATERWORKS -> 400; case INSULATION -> 600; case COMMUNITY -> 600; case EDUCATION -> 800; default -> 0;
    }; }
    @Nonnull public static List<Cost> costs(int node) {
        return Objects.requireNonNull(switch (node) {
            case 1 -> List.of(cost(Items.COBBLESTONE, 16));
            case 2 -> List.of(cost(Items.IRON_INGOT, 8), cost(Items.COAL, 8));
            case 3 -> List.of(cost(Items.DIAMOND, 2), cost(Items.REDSTONE, 16));
            case WATERWORKS -> List.of(cost(Items.COPPER_INGOT, 4), cost(Items.OAK_PLANKS, 8));
            case INSULATION -> List.of(cost(Items.WHITE_WOOL, 8), cost(Items.OAK_PLANKS, 16));
            case COMMUNITY -> List.of(cost(Items.BREAD, 6), cost(Items.OAK_PLANKS, 12));
            case EDUCATION -> List.of(cost(Items.PAPER, 12), cost(Items.BOOK, 3));
            default -> List.of();
        });
    }
    @Nonnull private static Cost cost(@Nullable Item item, int count) { return new Cost(Objects.requireNonNull(item), count); }
    @Nonnull private static JsonArray describeCosts(int node) {
        JsonArray materials = new JsonArray();
        for (Cost cost : costs(node)) {
            JsonObject material = new JsonObject(); material.addProperty("item", String.valueOf(ForgeRegistries.ITEMS.getKey(cost.item())));
            material.addProperty("name", new ItemStack(cost.item()).getDescriptionId()); material.addProperty("count", cost.count()); materials.add(material);
        }
        return materials;
    }
    @Nonnull public static JsonObject describe(ServerLevel level, UUID owner) {
        TechProgress progress = get(level); int tier = progress.tier(owner), next = tier == 3 ? -1 : tier + 1;
        JsonObject result = new JsonObject(); result.addProperty("tier", tier); result.addProperty("name", name(tier)); result.addProperty("nextTier", next);
        result.addProperty("nextName", name(next < 0 ? tier : next)); result.addProperty("researchTicks", researchTicks(next)); result.addProperty("readOnly", progress.readOnly());
        result.add("costs", describeCosts(next)); JsonArray nodes = new JsonArray();
        for (int node = 0; node < NODE_COUNT; node++) {
            JsonObject entry = new JsonObject(); entry.addProperty("tier", node); entry.addProperty("name", name(node));
            var parents = prerequisites(node); entry.addProperty("requiredTier", parents.isEmpty() ? -1 : parents.get(0));
            entry.addProperty("researchTicks", researchTicks(node)); entry.add("costs", describeCosts(node));
            entry.addProperty("done", progress.unlocked(owner, node)); String reason = node == 0 ? "researched" : progress.researchReason(level, owner, node);
            entry.addProperty("available", reason.equals("ready")); entry.addProperty("reason", "colonycamera.tech.reason." + reason);
            entry.addProperty("effect", "colonycamera.tech.effect." + node); nodes.add(entry);
            int id = node;
            var job = dev.colony.camera.work.ColonyWorkManager.get(level).jobs(owner).stream().filter(value -> value.kind == dev.colony.camera.work.ColonyJob.Kind.RESEARCH
                    && !value.cancelled && !value.finished() && value.recipe.equals(String.valueOf(id))).findFirst().orElse(null);
            entry.addProperty("running", job != null); entry.addProperty("progress", job == null || researchTicks(node) == 0 ? 0 : Math.min(1, job.workProgress / researchTicks(node)));
        }
        result.add("nodes", nodes); return result;
    }
    @Override @Nonnull public CompoundTag save(CompoundTag root) {
        var preserved = rejected; if (preserved != null) return Objects.requireNonNull(preserved.copy());
        root.putInt("schemaVersion", 2); ListTag entries = new ListTag();
        unlocks.forEach((owner, nodes) -> {
            CompoundTag entry = new CompoundTag(); entry.putUUID("owner", Objects.requireNonNull(owner)); entry.putInt("tier", tier(owner));
            entry.putIntArray("unlocks", Objects.requireNonNull(nodes.stream().mapToInt(value -> Objects.requireNonNull(value)).sorted().toArray())); entries.add(entry);
        });
        root.put("colonies", entries); return root;
    }
    @Nonnull public static TechProgress load(CompoundTag root) {
        TechProgress progress = new TechProgress();
        try {
            int schema = root.getInt("schemaVersion");
            if (!Set.of("schemaVersion", "colonies").equals(root.getAllKeys()) || !root.contains("schemaVersion", Tag.TAG_INT) || schema < 1 || schema > 2
                    || !(root.get("colonies") instanceof ListTag entries) || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Unsupported technology data");
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = Objects.requireNonNull(entries.getCompound(i)); int tier = entry.getInt("tier");
                if (!(schema == 1 ? Set.of("owner", "tier") : Set.of("owner", "tier", "unlocks")).equals(entry.getAllKeys())
                        || !entry.hasUUID("owner") || !entry.contains("tier", Tag.TAG_INT) || tier < 0 || tier > 3) throw new IllegalArgumentException("Invalid technology entry");
                UUID owner = Objects.requireNonNull(entry.getUUID("owner")); Set<Integer> nodes = new LinkedHashSet<>();
                if (schema == 1) for (int stage = 1; stage <= tier; stage++) nodes.add(stage);
                else {
                    if (!entry.contains("unlocks", Tag.TAG_INT_ARRAY) || entry.getIntArray("unlocks").length >= NODE_COUNT) throw new IllegalArgumentException("unlocks");
                    for (int node : entry.getIntArray("unlocks")) if (node < 1 || node >= NODE_COUNT || !nodes.add(node)) throw new IllegalArgumentException("unlocks");
                    for (int node : nodes) if (!nodes.containsAll(prerequisites(node))) throw new IllegalArgumentException("prerequisites");
                }
                if (progress.unlocks.put(owner, nodes) != null || progress.tier(owner) != tier) throw new IllegalArgumentException("technology");
            }
        } catch (RuntimeException invalid) { progress.unlocks.clear(); progress.rejected = root.copy(); LogUtils.getLogger().warn("Colony technology data rejected and preserved: {}", invalid.getMessage()); }
        return progress;
    }
}
