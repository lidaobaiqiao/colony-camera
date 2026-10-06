package dev.colony.camera.resources;

import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Objects;

@ParametersAreNonnullByDefault
public final class GroundResources {
    private GroundResources() {}
    public static boolean matches(ItemStack stack, Item item) {
        return ItemStack.isSameItemSameTags(stack, new ItemStack(item));
    }
    @Nonnull public static Map<Item, List<ItemEntity>> scan(ServerLevel level, WorkerEntity worker) {
        Map<Item, List<ItemEntity>> result = new HashMap<>();
        var nearby = level.getEntitiesOfClass(ItemEntity.class, Objects.requireNonNull(worker.getBoundingBox().inflate(WorkerEntity.SEARCH_RANGE)));
        for (ItemEntity candidate : nearby) {
            ItemEntity entity = Objects.requireNonNull(candidate);
            Item item = Objects.requireNonNull(entity.getItem().getItem());
            if (eligible(entity, worker, item)) result.computeIfAbsent(item, ignored -> new ArrayList<>()).add(entity);
        }
        for (var items : result.values()) items.sort(Comparator.comparingDouble(worker::distanceToSqr));
        return result;
    }
    public static boolean eligible(ItemEntity entity, WorkerEntity worker, Item item) {
        if (!entity.isAlive() || entity.hasPickUpDelay() || !matches(Objects.requireNonNull(entity.getItem()), item)) return false;
        // ItemEntity#getOwner is the thrower entity, not the UUID restricting pickup.
        // The pickup target has no public getter in 1.20.1; read its normal persisted field.
        CompoundTag data = new CompoundTag(); entity.saveWithoutId(data);
        return !data.hasUUID("Owner") || data.getUUID("Owner").equals(worker.owner());
    }

    /** A minimum search area must be loaded; navigation shrinks longer searches to loaded chunks. */
    public static boolean searchAreaLoaded(ServerLevel level, WorkerEntity worker) {
        int radius = 16;
        BlockPos origin = worker.blockPosition();
        for (int x = (origin.getX() - radius) >> 4; x <= (origin.getX() + radius) >> 4; x++) {
            for (int z = (origin.getZ() - radius) >> 4; z <= (origin.getZ() + radius) >> 4; z++) {
                if (!level.getChunkSource().hasChunk(x, z)) return false;
            }
        }
        return true;
    }
    @Nullable public static Path path(ServerLevel level, WorkerEntity worker, BlockPos target) {
        if (!loaded(level, target) || !searchAreaLoaded(level, worker)) return null;
        Path path = worker.planningNavigation().createPath(target, 0);
        return path != null && path.canReach() ? path : null;
    }
    /** Start an actual route only after planning; vanilla retains the correct target for recalculation. */
    public static boolean follow(ServerLevel level, WorkerEntity worker, Path planned) {
        BlockPos target = Objects.requireNonNull(planned.getTarget());
        if (!loaded(level, target) || !searchAreaLoaded(level, worker)) return false;
        var navigation = worker.getNavigation();
        Path route = navigation.createPath(target, 0);
        return route != null && route.canReach() && navigation.moveTo(route, 1.0);
    }
    public static boolean canPickUp(ServerLevel level, WorkerEntity worker, ItemEntity item) {
        if (worker.distanceToSqr(item) > 3.25 || item.hasPickUpDelay()) return false;
        var hit = level.clip(new ClipContext(Objects.requireNonNull(worker.getEyePosition()), Objects.requireNonNull(item.position().add(0, 0.15, 0)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, worker));
        return hit.getType() == HitResult.Type.MISS;
    }
    public static boolean loaded(ServerLevel level, BlockPos pos) { return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4); }
    @Nonnull public static Vec3 center(BlockPos pos) { return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); }
    @Nonnull public static Vec3 bottom(BlockPos pos) { return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5); }
    @Nonnull public static AABB body(BlockPos stand) {
        return new AABB(stand.getX() + 0.2, stand.getY(), stand.getZ() + 0.2,
                stand.getX() + 0.8, stand.getY() + 1.8, stand.getZ() + 0.8);
    }
}
