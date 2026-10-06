package dev.colony.camera.work;

import dev.colony.camera.construction.ProjectOrder;
import net.minecraft.core.BlockPos;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persistent intent and progress; NPC assignments are transient and never contain copied resources. */
@ParametersAreNonnullByDefault
public final class ColonyJob {
    public enum Kind { MINE, SWEEP, CRAFT, RESEARCH }
    @Nonnull public final UUID id;
    @Nonnull public final UUID owner;
    @Nonnull public final String ownerName;
    @Nonnull public final Kind kind;
    public int priority;
    public final long sequence;
    @Nonnull public List<BlockPos> targets = java.util.Objects.requireNonNull(List.of());
    @Nonnull public List<String> expectedStates = java.util.Objects.requireNonNull(List.of());
    @Nullable public BlockPos station;
    @Nonnull public String recipe = "";
    public int requested = 1;
    @Nonnull public final BitSet completed = new BitSet();
    public int done;
    public int cursor;
    @Nonnull public String reason = "queued";
    public boolean paused;
    public boolean cancelled;

    public ColonyJob(UUID id, UUID owner, String ownerName, Kind kind, int priority, long sequence) {
        this.id = Objects.requireNonNull(id); this.owner = Objects.requireNonNull(owner);
        this.ownerName = Objects.requireNonNull(ownerName); this.kind = Objects.requireNonNull(kind);
        this.priority = new ProjectOrder(id, priority, sequence).priority(); this.sequence = sequence;
    }
    @Nonnull public ProjectOrder order() { return new ProjectOrder(id, priority, sequence); }
    public int total() { return kind == Kind.MINE ? targets.size() : Math.max(1, requested); }
    public boolean finished() { return kind == Kind.SWEEP ? completed.get(0) : kind == Kind.MINE ? completed.cardinality() >= targets.size() : done >= requested; }
    public boolean runnable() { return !paused && !cancelled && !finished(); }
}
