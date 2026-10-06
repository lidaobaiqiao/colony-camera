package dev.colony.camera.work;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Transient path/progress only; material templates identify items but never own them. */
@ParametersAreNonnullByDefault
public final class WorkTask {
    @Nonnull public final UUID job;
    @Nonnull public BlockPos stand;
    public int targetIndex;
    public int progress;
    public long deadline;
    public long lastWorked;
    @Nullable public UUID source;
    @Nullable public BlockPos destination;
    @Nullable public BlockPos targetStand;
    @Nullable public String toolItem;
    @Nullable public ItemStack toolTemplate;
    public boolean toolPickup;
    public int slot = -1;
    @Nonnull public Map<UUID, Integer> materialClaims = new LinkedHashMap<>();
    public WorkTask(UUID job, BlockPos stand, long deadline) { this.job = job; this.stand = stand; this.deadline = deadline; }
}
