package dev.colony.camera.worker;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@ParametersAreNonnullByDefault
public final class WorkerNeedsStorage {
    private WorkerNeedsStorage() {}
    @Nonnull public static CompoundTag save(WorkerNeeds needs) {
        CompoundTag tag = new CompoundTag(); tag.putInt("schemaVersion", 1);
        tag.putDouble("hydration", needs.hydration()); tag.putDouble("social", needs.social());
        tag.putDouble("waterQuality", needs.waterQuality()); tag.putDouble("temperature", needs.temperature());
        tag.putLong("drinks", needs.drinks()); tag.putLong("meals", needs.meals()); ListTag entries = new ListTag();
        tag.putLong("lastCareDay", needs.lastCareDay());
        needs.relations().forEach((id, relation) -> {
            CompoundTag entry = new CompoundTag(); entry.putUUID("person", Objects.requireNonNull(id));
            entry.putLong("togetherTicks", Objects.requireNonNull(relation).togetherTicks()); entries.add(entry);
        });
        tag.put("relations", entries); return tag;
    }
    @Nonnull public static WorkerNeeds load(CompoundTag tag) {
        if (!Set.of("schemaVersion", "hydration", "social", "waterQuality", "temperature", "drinks", "meals", "lastCareDay", "relations").equals(tag.getAllKeys())
                || !tag.contains("schemaVersion", Tag.TAG_INT) || tag.getInt("schemaVersion") != 1
                || !(tag.get("relations") instanceof ListTag entries) || entries.size() > WorkerNeeds.MAX_RELATIONS
                || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("needs");
        var friends = new LinkedHashMap<UUID, WorkerNeeds.Relation>();
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = Objects.requireNonNull(entries.getCompound(i));
            if (!Set.of("person", "togetherTicks").equals(entry.getAllKeys()) || !entry.hasUUID("person")
                    || !entry.contains("togetherTicks", Tag.TAG_LONG) || friends.put(Objects.requireNonNull(entry.getUUID("person")),
                    new WorkerNeeds.Relation(entry.getLong("togetherTicks"))) != null) throw new IllegalArgumentException("relation");
        }
        WorkerNeeds needs = new WorkerNeeds();
        needs.restore(decimal(tag, "hydration"), decimal(tag, "social"), decimal(tag, "waterQuality"), decimal(tag, "temperature"),
                number(tag, "drinks"), number(tag, "meals"), number(tag, "lastCareDay"), friends); return needs;
    }
    private static double decimal(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_DOUBLE) || !Double.isFinite(tag.getDouble(key))) throw new IllegalArgumentException(key);
        return tag.getDouble(key);
    }
    private static long number(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException(key); return tag.getLong(key);
    }
}
