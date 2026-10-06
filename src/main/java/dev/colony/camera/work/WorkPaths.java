package dev.colony.camera.work;

import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

@ParametersAreNonnullByDefault
public final class WorkPaths {
    private WorkPaths() {}
    @Nullable public static BlockPos beside(ServerLevel level, WorkerEntity worker, BlockPos target) {
        var candidates = new ArrayList<BlockPos>();
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            BlockPos stand = Objects.requireNonNull(target.offset(dx, dy, dz));
            if (ConstructionManager.standable(level, stand)) candidates.add(stand);
        }
        candidates.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos)))));
        for (BlockPos stand : candidates.stream().limit(12).toList()) {
            if (worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(stand))) < 1.2 || GroundResources.path(level, worker, stand) != null) return stand;
        }
        return null;
    }
    public static boolean arrive(ServerLevel level, WorkerEntity worker, BlockPos stand) {
        if (!ConstructionManager.standable(level, stand)) return false;
        if (worker.distanceToSqr(GroundResources.bottom(stand)) <= 1.2) { worker.getNavigation().stop(); return true; }
        if (worker.getNavigation().isDone() || level.getGameTime() % 20 == 0) {
            var path = GroundResources.path(level, worker, stand); if (path != null) GroundResources.follow(level, worker, path);
        }
        return false;
    }
}
