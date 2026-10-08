package dev.colony.camera.worker;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/** Player-authored long-running intent; unloading pauses it without resetting learning progress. */
@ParametersAreNonnullByDefault
public final class WorkerDirective {
    public enum Kind { STUDY_PROFESSION, STUDY_ATTRIBUTE, EXECUTION, SACRIFICE }
    @Nonnull public final Kind kind;
    @Nullable public final BlockPos station;
    @Nullable public final UUID target;
    @Nonnull public final String course;
    public final boolean additional;
    public final int tier;
    public double progress;
    public WorkerDirective(Kind kind, @Nullable BlockPos station, @Nullable UUID target, String course, boolean additional, int tier) {
        if (tier < 1 || tier > 3 || course.length() > 32) throw new IllegalArgumentException("directive");
        this.kind = kind; this.station = station; this.target = target; this.course = course; this.additional = additional; this.tier = tier;
        switch (kind) {
            case STUDY_PROFESSION -> {
                var profession = WorkerProfile.parse(WorkerProfile.Profession.class, course);
                if (station == null || target != null || profession == WorkerProfile.Profession.NONE
                        || tier < WorkerProfile.schoolLevel(profession) || additional && tier != 3) throw new IllegalArgumentException("course");
            }
            case STUDY_ATTRIBUTE -> {
                if (station == null || target != null || additional) throw new IllegalArgumentException("course");
                WorkerProfile.parse(WorkerAttribute.class, course);
            }
            case EXECUTION, SACRIFICE -> {
                if (target == null || station != null || !course.isEmpty() || additional || tier != 1) throw new IllegalArgumentException("target");
            }
        }
    }
    @Nonnull public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putString("kind", Objects.requireNonNull(kind.name())); tag.putString("course", course);
        if (station != null) tag.putLong("station", station.asLong()); if (target != null) tag.putUUID("target", target);
        tag.putBoolean("additional", additional); tag.putInt("tier", tier); tag.putDouble("progress", progress); return tag;
    }
    @Nonnull public static WorkerDirective load(CompoundTag tag) {
        if (!Set.of("kind", "course", "station", "target", "additional", "tier", "progress").containsAll(tag.getAllKeys())) throw new IllegalArgumentException("directive");
        if (!tag.contains("kind", Tag.TAG_STRING) || !tag.contains("course", Tag.TAG_STRING) || !tag.contains("additional", Tag.TAG_BYTE)
                || !tag.contains("tier", Tag.TAG_INT) || !tag.contains("progress", Tag.TAG_DOUBLE)
                || tag.contains("station") && !tag.contains("station", Tag.TAG_LONG) || tag.contains("target") && !tag.hasUUID("target")) throw new IllegalArgumentException("directive");
        WorkerDirective directive = new WorkerDirective(WorkerProfile.parse(Kind.class, Objects.requireNonNull(tag.getString("kind"))),
                tag.contains("station") ? Objects.requireNonNull(BlockPos.of(tag.getLong("station"))) : null,
                tag.hasUUID("target") ? tag.getUUID("target") : null, Objects.requireNonNull(tag.getString("course")), tag.getBoolean("additional"), tag.getInt("tier"));
        double progress = tag.getDouble("progress"); if (!Double.isFinite(progress) || progress < 0) throw new IllegalArgumentException("progress");
        directive.progress = progress; return directive;
    }
    public void validate(WorkerState state) {
        if (kind != Kind.STUDY_PROFESSION) return;
        var profession = WorkerProfile.parse(WorkerProfile.Profession.class, course);
        if (profession == state.primary() || profession == state.secondary()
                || additional && (state.primary() == WorkerProfile.Profession.NONE || state.secondary() != WorkerProfile.Profession.NONE))
            throw new IllegalArgumentException("course");
    }
}
