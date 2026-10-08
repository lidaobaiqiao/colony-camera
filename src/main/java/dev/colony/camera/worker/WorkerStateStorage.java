package dev.colony.camera.worker;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Set;
import static dev.colony.camera.worker.WorkerProfile.*;

/** Atomic, strict entity state codec. Callers preserve the original tag if any member is rejected. */
@ParametersAreNonnullByDefault
public final class WorkerStateStorage {
    private WorkerStateStorage() {}
    @Nonnull public static CompoundTag save(WorkerState state) {
        CompoundTag tag = new CompoundTag(); tag.putInt("schemaVersion", 1);
        CompoundTag attributes = new CompoundTag();
        for (WorkerAttribute attribute : WorkerAttribute.values()) {
            CompoundTag entry = new CompoundTag(); entry.putString("birth", Objects.requireNonNull(state.birthPoints(Objects.requireNonNull(attribute)).toString()));
            entry.putString("practice", Objects.requireNonNull(state.credit(attribute).toString())); attributes.put(Objects.requireNonNull(attribute.name()), entry);
        }
        tag.put("attributes", attributes); tag.putString("interest", Objects.requireNonNull(state.interest().name()));
        tag.putString("temperament", Objects.requireNonNull(state.temperament().name())); tag.putString("primary", Objects.requireNonNull(state.primary().name()));
        tag.putString("secondary", Objects.requireNonNull(state.secondary().name()));
        CompoundTag pressure = new CompoundTag(); state.pressureSources().forEach((cause, amount) -> pressure.putDouble(Objects.requireNonNull(cause.name()), Objects.requireNonNull(amount)));
        tag.put("pressure", pressure); tag.putDouble("hunger", state.hunger()); tag.putDouble("rest", state.rest());
        tag.putDouble("foodQuality", state.foodQuality()); tag.putDouble("lastQuality", state.lastQuality()); tag.putDouble("loyalty", state.loyalty());
        tag.putBoolean("striking", state.striking()); tag.putLong("continuousWork", state.continuousWork());
        tag.putString("minedBlocks", Objects.requireNonNull(state.minedBlocks().toString())); tag.putLong("moraleUntil", state.moraleUntil()); tag.putBoolean("moraleSuccess", state.moraleSuccess());
        return tag;
    }
    @Nonnull public static WorkerState load(CompoundTag tag) {
        if (!Set.of("schemaVersion", "attributes", "interest", "temperament", "primary", "secondary", "pressure", "hunger", "rest",
                "foodQuality", "lastQuality", "loyalty", "striking", "continuousWork", "minedBlocks", "moraleUntil", "moraleSuccess",
                "home", "directive", "cargoExtra").containsAll(tag.getAllKeys())) throw new IllegalArgumentException("worker_data");
        if (!tag.contains("schemaVersion", Tag.TAG_INT) || tag.getInt("schemaVersion") != 1 || !tag.contains("attributes", Tag.TAG_COMPOUND)
                || !tag.contains("pressure", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("worker_data");
        WorkerState state = new WorkerState(); CompoundTag attributes = Objects.requireNonNull(tag.getCompound("attributes"));
        if (attributes.getAllKeys().size() != WorkerAttribute.values().length) throw new IllegalArgumentException("attributes");
        for (WorkerAttribute attribute : WorkerAttribute.values()) {
            if (!attributes.contains(Objects.requireNonNull(attribute.name()), Tag.TAG_COMPOUND)) throw new IllegalArgumentException("attribute");
            CompoundTag entry = Objects.requireNonNull(attributes.getCompound(Objects.requireNonNull(attribute.name())));
            if (!Set.of("birth", "practice").containsAll(entry.getAllKeys())) throw new IllegalArgumentException("attribute");
            state.restoreAttribute(Objects.requireNonNull(attribute), integer(entry, "birth"), integer(entry, "practice"));
        }
        state.restoreIdentity(parse(Interest.class, string(tag, "interest")), parse(Temperament.class, string(tag, "temperament")),
                parse(Profession.class, string(tag, "primary")), parse(Profession.class, string(tag, "secondary")));
        CompoundTag pressure = Objects.requireNonNull(tag.getCompound("pressure"));
        for (String key : pressure.getAllKeys()) state.addPressure(parse(StressCause.class, Objects.requireNonNull(key)), decimal(pressure, key));
        if (!tag.contains("striking", Tag.TAG_BYTE) || !tag.contains("moraleSuccess", Tag.TAG_BYTE)) throw new IllegalArgumentException("life");
        state.restoreLife(decimal(tag, "hunger"), decimal(tag, "rest"), decimal(tag, "foodQuality"), decimal(tag, "lastQuality"), decimal(tag, "loyalty"),
                tag.getBoolean("striking"), number(tag, "continuousWork"), integer(tag, "minedBlocks"), number(tag, "moraleUntil"), tag.getBoolean("moraleSuccess"));
        return state;
    }
    @Nonnull private static BigInteger integer(CompoundTag tag, String key) {
        String value = string(tag, key);
        if (value.length() > 4096 || !value.matches("[0-9]+")) throw new IllegalArgumentException("integer");
        return new BigInteger(value);
    }
    @Nonnull private static String string(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException(key); return Objects.requireNonNull(tag.getString(key));
    }
    private static double decimal(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_DOUBLE) || !Double.isFinite(tag.getDouble(key))) throw new IllegalArgumentException(key); return tag.getDouble(key);
    }
    private static long number(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException(key); return tag.getLong(key);
    }
}
