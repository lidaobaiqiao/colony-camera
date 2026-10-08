package dev.colony.camera.work;

import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.StorageResources;
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
        return beside(level, worker, target, null);
    }
    @Nullable public static BlockPos beside(ServerLevel level, WorkerEntity worker, BlockPos target, @Nullable BlockPos preferred) {
        if (preferred != null && operable(level, worker, target, preferred) && reachable(level, worker, preferred)) return preferred;
        var candidates = new ArrayList<BlockPos>();
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            BlockPos stand = Objects.requireNonNull(target.offset(dx, dy, dz));
            if (operable(level, worker, target, stand)) candidates.add(stand);
        }
        candidates.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos)))));
        for (BlockPos stand : candidates.stream().limit(12).toList()) {
            if (reachable(level, worker, Objects.requireNonNull(stand))) return stand;
        }
        return null;
    }
    private static boolean operable(ServerLevel level, WorkerEntity worker, BlockPos target, BlockPos stand) {
        if (!ConstructionManager.standable(level, stand)) return false;
        var feet = GroundResources.bottom(stand);
        return StorageResources.canTakeFrom(level, worker, feet, Objects.requireNonNull(feet.add(0, worker.getEyeHeight(), 0)), target);
    }
    private static boolean reachable(ServerLevel level, WorkerEntity worker, BlockPos stand) {
        return worker.distanceToSqr(GroundResources.bottom(stand)) <= 0.01 || GroundResources.path(level, worker, stand) != null;
    }
    public static boolean arrive(ServerLevel level, WorkerEntity worker, BlockPos stand) {
        if (!ConstructionManager.standable(level, stand)) return false;
        if (worker.distanceToSqr(GroundResources.bottom(stand)) <= 0.01) { worker.getNavigation().stop(); return true; }
        if (worker.getNavigation().isDone() || !stand.equals(worker.getNavigation().getTargetPos())) {
            var path = GroundResources.path(level, worker, stand); if (path != null) GroundResources.follow(level, worker, path);
        }
        return false;
    }
}
