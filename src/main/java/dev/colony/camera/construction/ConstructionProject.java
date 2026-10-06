package dev.colony.camera.construction;

import dev.colony.camera.planning.ProjectionPlacement;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.BitSet;
import java.util.Objects;
import java.util.UUID;

@ParametersAreNonnullByDefault
public final class ConstructionProject {
    @Nonnull public final ProjectionPlacement placement;
    @Nonnull public final UUID owner;
    @Nonnull public final String ownerName;
    @Nonnull public final BuildPlan plan;
    public final long sequence;
    public final BitSet completed = new BitSet();
    public int priority;
    public boolean paused;
    public boolean cancelled;
    public int cursor;
    @Nonnull public String reason = "queued";
    public int activeWorkers;
    public int groundKnown;
    public int reserved;
    @Nonnull public String neededItem = "";
    public int neededCount;

    public ConstructionProject(ProjectionPlacement placement, UUID owner, String ownerName, int priority, long sequence) {
        this.placement = placement; this.owner = owner; this.ownerName = ownerName;
        this.priority = new ProjectOrder(placement.id(), priority, sequence).priority();
        this.sequence = sequence; this.plan = BuildPlan.compile(placement);
    }
    @Nonnull public UUID id() { return Objects.requireNonNull(placement.id()); }
    @Nonnull public ProjectOrder order() { return new ProjectOrder(id(), priority, sequence); }
    public boolean finished() { return completed.cardinality() == plan.units().size(); }
    public boolean runnable() { return !paused && !cancelled && !finished(); }
    public int completedBlocks() {
        int count = 0;
        for (int i = completed.nextSetBit(0); i >= 0 && i < plan.units().size(); i = completed.nextSetBit(i + 1)) count += plan.units().get(i).blocks().size();
        return count;
    }
}
