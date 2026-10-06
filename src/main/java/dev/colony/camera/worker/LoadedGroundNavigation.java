package dev.colony.camera.worker;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Set;
import java.util.Objects;

/** Guard every vanilla recalculation too, not only paths requested by the dispatcher. */
@ParametersAreNonnullByDefault
public final class LoadedGroundNavigation extends GroundPathNavigation {
    public LoadedGroundNavigation(WorkerEntity worker, Level level) { super(worker, level); }
    @Override @Nullable protected Path createPath(Set<BlockPos> targets, int regionExtra, boolean above, int reach, float distance) {
        if (!(level instanceof ServerLevel server)) return null;
        BlockPos origin = Objects.requireNonNull(mob.blockPosition());
        for (int range = Math.min(WorkerEntity.NAVIGATION_RANGE, (int) distance); range >= 8; range -= 8) {
            if (loaded(server, origin, range + regionExtra)) return super.createPath(targets, regionExtra, above, reach, range);
        }
        return null;
    }
    private static boolean loaded(ServerLevel server, BlockPos origin, int radius) {
        for (int x = (origin.getX() - radius) >> 4; x <= (origin.getX() + radius) >> 4; x++) {
            for (int z = (origin.getZ() - radius) >> 4; z <= (origin.getZ() + radius) >> 4; z++) {
                if (!server.getChunkSource().hasChunk(x, z)) return false;
            }
        }
        return true;
    }
}
