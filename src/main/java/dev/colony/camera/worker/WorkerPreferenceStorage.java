package dev.colony.camera.worker;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class WorkerPreferenceStorage {
    private WorkerPreferenceStorage() {}
    @Nonnull public static CompoundTag save(WorkerPreferences preferences) {
        CompoundTag tag = new CompoundTag(); tag.putInt("schemaVersion", 1);
        tag.putBoolean("automaticTools", preferences.automaticTools());
        for (var task : WorkerPreferences.Task.values()) tag.putBoolean(Objects.requireNonNull(task.name()), preferences.allows(Objects.requireNonNull(task)));
        return tag;
    }
    @Nonnull public static WorkerPreferences load(CompoundTag tag) {
        Set<String> keys = new HashSet<>(Set.of("schemaVersion", "automaticTools"));
        for (var task : WorkerPreferences.Task.values()) keys.add(task.name());
        if (!tag.getAllKeys().equals(keys) || !tag.contains("schemaVersion", Tag.TAG_INT) || tag.getInt("schemaVersion") != 1) throw new IllegalArgumentException("data");
        WorkerPreferences result = new WorkerPreferences();
        for (String key : keys) if (!key.equals("schemaVersion") && (!tag.contains(Objects.requireNonNull(key), Tag.TAG_BYTE) || tag.getByte(key) < 0 || tag.getByte(key) > 1)) throw new IllegalArgumentException("data");
        result.automaticTools(tag.getBoolean("automaticTools"));
        for (var task : WorkerPreferences.Task.values()) result.allows(Objects.requireNonNull(task), tag.getBoolean(Objects.requireNonNull(task.name())));
        return result;
    }
}
