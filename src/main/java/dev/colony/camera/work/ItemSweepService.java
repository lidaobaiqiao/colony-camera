package dev.colony.camera.work;

import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.worker.WorkerEntity;
import dev.colony.camera.worker.WorkerPreferences;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

/** Finite item orders capture existing drops, then budget real route probes across workers. */
@ParametersAreNonnullByDefault
public final class ItemSweepService {
    static final class Probes {
        final Set<UUID> tested = new HashSet<>(), reachable = new HashSet<>();
        int cursor;
    }
    private ItemSweepService() {}
    @Nonnull public static List<UUID> capture(ServerLevel level, ColonyJob job) {
        Set<UUID> result = new LinkedHashSet<>();
        for (BlockPos origin : job.targets) for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, Objects.requireNonNull(new AABB(Objects.requireNonNull(origin)).inflate(128)))) {
            if (matches(level, job, Objects.requireNonNull(item))) result.add(Objects.requireNonNull(item.getUUID()));
            if (result.size() > 8192) throw new IllegalArgumentException("limit");
        }
        return Objects.requireNonNull(List.copyOf(result));
    }
    public static boolean matches(ServerLevel level, ColonyJob job, ItemEntity item) {
        if (!item.isAlive() || item.getItem().isEmpty() || !GroundResources.loaded(level, Objects.requireNonNull(item.blockPosition()))
                || !job.itemTypes.contains(String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item.getItem().getItem())))) return false;
        var tag = new net.minecraft.nbt.CompoundTag(); item.saveWithoutId(tag);
        return (!tag.hasUUID("Owner") || tag.getUUID("Owner").equals(job.owner))
                && job.targets.stream().anyMatch(origin -> GroundResources.center(Objects.requireNonNull(origin)).distanceToSqr(Objects.requireNonNull(item.position())) <= 128 * 128);
    }
    @Nonnull public static List<ItemEntity> drops(ServerLevel level, ColonyJob job) {
        List<ItemEntity> result = new ArrayList<>();
        for (UUID id : job.sweepDrops) if (level.getEntity(Objects.requireNonNull(id)) instanceof ItemEntity item && matches(level, job, item)) result.add(item);
        return result;
    }
    @Nonnull static List<ItemEntity> candidates(ServerLevel level, WorkerEntity worker, ColonyJob job) {
        Probes probes = Objects.requireNonNull(job.sweepProbes.computeIfAbsent(Objects.requireNonNull(worker.getUUID()), ignored -> new Probes()));
        List<ItemEntity> pending = drops(level, job); int paths = 0;
        for (int checked = 0; checked < pending.size() && paths < 16; checked++) {
            ItemEntity item = Objects.requireNonNull(pending.get(Math.floorMod(probes.cursor++, pending.size()))); UUID id = Objects.requireNonNull(item.getUUID());
            // Recheck previously reachable drops too: terrain can change while a worker travels.
            boolean nearby = worker.distanceToSqr(item) <= (double) worker.navigationRange() * worker.navigationRange();
            boolean reachable = nearby && (GroundResources.canPickUp(level, worker, item) || SweepService.pickupStand(level, worker, item) != null);
            if (nearby) paths++;
            probes.tested.add(id); if (reachable) probes.reachable.add(id); else probes.reachable.remove(id);
        }
        return Objects.requireNonNull(pending.stream().filter(item -> probes.reachable.contains(item.getUUID())).toList());
    }
    static void finish(ServerLevel level, ColonyJob job, List<WorkerEntity> workers) {
        if (!job.itemSweep()) return;
        if (workers.stream().anyMatch(worker -> job.owner.equals(Objects.requireNonNull(worker).owner()) && SweepService.ownsCargo(level, worker, job))) return;
        var eligible = workers.stream().filter(worker -> job.owner.equals(Objects.requireNonNull(worker).owner()) && worker.isAlive() && !worker.stateReadOnly()
                && worker.preferences().allows(WorkerPreferences.Task.SWEEP)).toList();
        var drops = drops(level, job);
        if (!drops.isEmpty() && eligible.isEmpty()) return;
        for (ItemEntity item : drops) for (WorkerEntity worker : eligible) {
            Probes probes = job.sweepProbes.get(worker.getUUID());
            if (probes == null || !probes.tested.contains(item.getUUID()) || probes.reachable.contains(item.getUUID())) return;
        }
        // Every available worker has tried the remaining drops. They are currently unreachable.
        job.completed.set(0); job.requested = Math.max(1, job.done); job.done = job.requested; job.reason = "complete";
    }
}
