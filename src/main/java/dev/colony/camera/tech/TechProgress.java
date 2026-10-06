package dev.colony.camera.tech;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.mojang.logging.LogUtils;

/** Research belongs to one colony owner in one dimension; rejected data stays intact. */
@ParametersAreNonnullByDefault
public final class TechProgress extends SavedData {
    public record Cost(@Nonnull Item item, int count) {}
    private final Map<UUID, Integer> tiers = new LinkedHashMap<>();
    @Nullable private CompoundTag rejected;

    @Nonnull public static TechProgress get(ServerLevel level) {
        return Objects.requireNonNull(level.getDataStorage().computeIfAbsent(TechProgress::load, TechProgress::new, "colonycamera_technology"));
    }
    public int tier(UUID owner) { return tiers.getOrDefault(owner, 0); }
    public boolean readOnly() { return rejected != null; }
    /** The material transaction must finish before publishing the unlock. */
    public boolean advance(UUID owner, int expectedTier, Runnable materials) {
        if (readOnly() || expectedTier != tier(owner) || expectedTier >= 3) return false;
        materials.run();
        tiers.put(owner, expectedTier + 1); setDirty(); return true;
    }
    @Nonnull public static String name(int tier) { return "colonycamera.tech." + switch (tier) { case 1 -> "stone"; case 2 -> "iron"; case 3 -> "diamond"; default -> "wood"; }; }
    public static int researchTicks(int nextTier) { return nextTier >= 1 && nextTier <= 3 ? nextTier * 200 : 0; }
    @Nonnull public static List<Cost> costs(int nextTier) {
        return Objects.requireNonNull(switch (nextTier) {
            case 1 -> List.of(new Cost(Objects.requireNonNull(Items.COBBLESTONE), 16));
            case 2 -> List.of(new Cost(Objects.requireNonNull(Items.IRON_INGOT), 8), new Cost(Objects.requireNonNull(Items.COAL), 8));
            case 3 -> List.of(new Cost(Objects.requireNonNull(Items.DIAMOND), 2), new Cost(Objects.requireNonNull(Items.REDSTONE), 16));
            default -> List.of();
        });
    }
    @Nonnull public static JsonObject describe(ServerLevel level, UUID owner) {
        TechProgress progress = get(level); int tier = progress.tier(owner); int next = tier == 3 ? -1 : tier + 1;
        JsonObject result = new JsonObject(); result.addProperty("tier", tier); result.addProperty("name", name(tier)); result.addProperty("nextTier", next);
        result.addProperty("nextName", tier == 3 ? name(tier) : name(next)); result.addProperty("researchTicks", tier == 3 ? 0 : researchTicks(next)); result.addProperty("readOnly", progress.readOnly());
        JsonArray materials = new JsonArray();
        if (tier < 3) for (Cost cost : costs(next)) {
            JsonObject material = new JsonObject(); material.addProperty("item", String.valueOf(ForgeRegistries.ITEMS.getKey(cost.item())));
            material.addProperty("name", new ItemStack(cost.item()).getDescriptionId()); material.addProperty("count", cost.count()); materials.add(material);
        }
        result.add("costs", materials);
        JsonArray nodes = new JsonArray();
        for (int stage = 0; stage <= 3; stage++) {
            JsonObject node = new JsonObject(); node.addProperty("tier", stage); node.addProperty("name", name(stage));
            node.addProperty("requiredTier", stage - 1); node.addProperty("researchTicks", researchTicks(stage));
            JsonArray costs = new JsonArray();
            for (Cost cost : costs(stage)) {
                JsonObject material = new JsonObject(); material.addProperty("item", String.valueOf(ForgeRegistries.ITEMS.getKey(cost.item())));
                material.addProperty("name", new ItemStack(cost.item()).getDescriptionId()); material.addProperty("count", cost.count()); costs.add(material);
            }
            node.add("costs", costs); nodes.add(node);
        }
        result.add("nodes", nodes); return result;
    }
    @Override @Nonnull public CompoundTag save(CompoundTag root) {
        var preserved = rejected; if (preserved != null) return Objects.requireNonNull(preserved.copy());
        root.putInt("schemaVersion", 1); ListTag entries = new ListTag();
        tiers.forEach((owner, tier) -> { CompoundTag entry = new CompoundTag(); entry.putUUID("owner", Objects.requireNonNull(owner)); entry.putInt("tier", tier); entries.add(entry); });
        root.put("colonies", entries); return root;
    }
    @Nonnull public static TechProgress load(CompoundTag root) {
        TechProgress progress = new TechProgress();
        try {
            if (!root.contains("schemaVersion", Tag.TAG_INT) || root.getInt("schemaVersion") != 1
                    || !(root.get("colonies") instanceof ListTag entries) || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Unsupported technology data");
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i); int tier = entry.getInt("tier");
                if (!entry.hasUUID("owner") || !entry.contains("tier", Tag.TAG_INT) || tier < 0 || tier > 3
                        || progress.tiers.put(Objects.requireNonNull(entry.getUUID("owner")), tier) != null) throw new IllegalArgumentException("Invalid technology entry");
            }
        } catch (RuntimeException invalid) { progress.tiers.clear(); progress.rejected = root.copy(); LogUtils.getLogger().warn("Colony technology data rejected and preserved: {}", invalid.getMessage()); }
        return progress;
    }
}
