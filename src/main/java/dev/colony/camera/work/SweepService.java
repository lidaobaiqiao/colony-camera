package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayerFactory;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Pickup and deposit are separate transfers. A full or disconnected destination leaves cargo in hand. */
@ParametersAreNonnullByDefault
public final class SweepService {
    private SweepService() {}
    public static AABB bounds(ColonyJob job) {
        if (job.surfaceSweep) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : job.targets) {
                minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
            }
            return new AABB(minX, minY, minZ, maxX + 1.0, maxY + 2.0, maxZ + 1.0);
        }
        BlockPos min = job.targets.get(0), max = job.targets.get(1);
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
    }
    public static boolean contains(ServerLevel level, ColonyJob job, ItemEntity item) {
        if (job.itemSweep()) return job.sweepDrops.contains(item.getUUID()) && ItemSweepService.matches(level, job, item);
        if (!job.surfaceSweep) return bounds(job).contains(Objects.requireNonNull(item.position()));
        int x = net.minecraft.util.Mth.floor(item.getX()), z = net.minecraft.util.Mth.floor(item.getZ());
        int y = net.minecraft.util.Mth.floor(item.getY());
        for (int below = 0; below <= 2; below++) {
            BlockPos floor = new BlockPos(x, y - below, z);
            if (!job.sweepsSurface(floor) || !GroundResources.loaded(level, floor)) continue;
            var state = level.getBlockState(floor); if (state.isAir()) continue;
            var shape = state.getCollisionShape(level, floor);
            double top = shape.isEmpty() ? floor.getY() + 1 : Double.NEGATIVE_INFINITY;
            var footprint = item.getBoundingBox();
            for (AABB part : shape.toAabbs()) {
                if (footprint.maxX > floor.getX() + part.minX && footprint.minX < floor.getX() + part.maxX
                        && footprint.maxZ > floor.getZ() + part.minZ && footprint.minZ < floor.getZ() + part.maxZ)
                    top = Math.max(top, floor.getY() + part.maxY);
            }
            // Stairs have two different tops; use the part beneath this item,
            // rather than the maximum height of the entire block shape.
            if (item.getY() < top - 0.05 || item.getY() >= top + 1) continue;
            // A drop resting on another block above this one belongs to that upper
            // surface, even when both tops fit inside the same one-block band.
            if (item.getY() <= top + 0.05) return true;
            var hit = level.clip(new net.minecraft.world.level.ClipContext(
                    new net.minecraft.world.phys.Vec3(item.getX(), top + 0.001, item.getZ()),
                    new net.minecraft.world.phys.Vec3(item.getX(), item.getY() + 0.02, item.getZ()),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, item));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
        }
        return false;
    }
    private static boolean owned(ItemEntity item, WorkerEntity worker) {
        CompoundTag tag = new CompoundTag(); item.saveWithoutId(tag);
        return !tag.hasUUID("Owner") || tag.getUUID("Owner").equals(worker.owner());
    }
    private static List<ItemEntity> items(ServerLevel level, WorkerEntity worker, ColonyJob job) {
        if (job.itemSweep()) return ItemSweepService.drops(level, job).stream().sorted(Comparator.comparingDouble(worker::distanceToSqr)).toList();
        return level.getEntitiesOfClass(ItemEntity.class, Objects.requireNonNull(bounds(job))).stream()
                .filter(item -> item.isAlive() && !item.getItem().isEmpty() && owned(Objects.requireNonNull(item), worker) && contains(level, job, item))
                .sorted(Comparator.comparingDouble(worker::distanceToSqr)).toList();
    }
    @Nullable public static WorkTask assign(ServerLevel level, WorkerEntity worker, ColonyJob job, ReservationLedger ledger, long time) {
        if (job.surfaceSweep) {
            for (BlockPos pos : job.targets) if (!GroundResources.loaded(level, Objects.requireNonNull(pos))) { job.reason = "unloaded"; return null; }
        } else if (!job.itemSweep()) {
            BlockPos min = job.targets.get(0), max = job.targets.get(1);
            for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) {
                if (!level.getChunkSource().hasChunk(x, z)) { job.reason = "unloaded"; return null; }
            }
        }
        ItemStack cargo = Objects.requireNonNull(worker.getMainHandItem());
        boolean ownCargo = ownsCargo(level, worker, job);
        WorkTask previous = worker.workTask;
        if (previous != null && (!previous.job.equals(job.id) || previous.deadline < time)) previous = null;
        var items = job.itemSweep() ? ItemSweepService.candidates(level, worker, job).stream().sorted(Comparator.comparingDouble(worker::distanceToSqr)).toList() : items(level, worker, job);
        if (previous != null && previous.source != null) {
            var preferredSource = previous.source;
            // A valid pickup survives redispatch; newly nearer drops must not keep restarting its route.
            items = items.stream().sorted(Comparator.comparingInt(item -> Objects.equals(item.getUUID(), preferredSource) ? 0 : 1)).toList();
        }
        if (!ownCargo && items.isEmpty()) {
            if (job.itemSweep()) { job.reason = "checking_items"; return null; }
            // A different worker may already have picked up the last drop and still be hauling it.
            if (ConstructionManager.get(level).workers(level).stream().anyMatch(other -> job.owner.equals(other.owner())
                    && ownsCargo(level, Objects.requireNonNull(other), job))) { job.reason = "hauling"; return null; }
            job.completed.set(0); job.requested = Math.max(1, job.done); job.done = job.requested; job.reason = "complete"; return null;
        }
        if (!cargo.isEmpty() && !ownCargo) { job.reason = "queued"; return null; }
        job.requested = Math.max(job.requested, job.done + items.size());
        var destinations = new LinkedHashSet<BlockPos>();
        BlockPos knownDestination = previous == null ? null : previous.destination;
        if (knownDestination != null
                && worker.distanceToSqr(GroundResources.center(knownDestination)) <= (double) worker.navigationRange() * worker.navigationRange()) {
            // Searching discovers new stores. An already chosen loaded store remains known while travelling.
            destinations.add(knownDestination);
        }
        destinations.addAll(StorageResources.scan(level, worker));
        if (knownDestination == null) destinations.addAll(StorageResources.colonyStores(level, worker));
        for (BlockPos destination : destinations) {
            if (!GroundResources.loaded(level, Objects.requireNonNull(destination))) continue;
            if (!(level.getBlockEntity(Objects.requireNonNull(destination)) instanceof MachineBlockEntity machine)
                    || machine.kind() != ColonyContent.Kind.WAREHOUSE && machine.kind() != ColonyContent.Kind.NETWORK_PORT) continue;
            StorageAccess access = StorageAccess.at(level, destination, job.owner);
            if (access.inventories.isEmpty()) continue;
            BlockPos preferred = previous != null && destination.equals(previous.destination) ? previous.stand : null;
            BlockPos deposit = WorkPaths.beside(level, worker, destination, preferred); if (deposit == null) continue;
            if (!cargo.isEmpty()) {
                if (access.insert(cargo, true).getCount() >= cargo.getCount()) continue;
                worker.cargoTask(job.id);
                job.reason = "hauling";
                WorkTask task = new WorkTask(job.id, deposit, time + 1200); task.destination = destination; return task;
            }
            for (ItemEntity item : items.stream().limit(128).toList()) {
                if (item.hasPickUpDelay() || worker.distanceToSqr(item) > (double) worker.navigationRange() * worker.navigationRange()) continue;
                var target = Objects.requireNonNull(item.blockPosition());
                if (!GroundResources.loaded(level, target)) continue;
                BlockPos preferredPickup = previous != null && item.getUUID().equals(previous.source) ? previous.targetStand : null;
                BlockPos pickup = GroundResources.canPickUp(level, worker, item) ? Objects.requireNonNull(worker.blockPosition()) : pickupStand(level, worker, item, preferredPickup);
                if (pickup == null) continue;
                ItemStack offered = item.getItem().copy(); offered.setCount(Math.min(offered.getCount(), Math.min(offered.getMaxStackSize(), dev.colony.camera.worker.WorkerEffects.capacity(worker.state()))));
                int wanted = Math.min(offered.getCount() - access.insert(offered, true).getCount(), item.getItem().getCount() - ledger.reserved(item.getUUID()));
                if (wanted < 1 || !ledger.reserve(worker.getUUID(), job.id, item.getUUID(), item.getItem().getCount(), wanted)) continue;
                job.reason = "collecting";
                WorkTask task = new WorkTask(job.id, deposit, time + 1200);
                task.destination = destination; task.targetStand = pickup; task.source = item.getUUID(); task.slot = wanted; return task;
            }
        }
        job.reason = cargo.isEmpty() && items.stream().anyMatch((ItemEntity checkedElement) -> Objects.requireNonNull(checkedElement).hasPickUpDelay()) ? "pickup_delay" : "storage_full_or_unreachable";
        return null;
    }
    private static boolean seesDrop(ServerLevel level, WorkerEntity worker, BlockPos stand, ItemEntity item) {
        var feet = GroundResources.bottom(stand);
        if (feet.distanceToSqr(Objects.requireNonNull(item.position())) > 3.25) return false;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(Objects.requireNonNull(feet.add(0, worker.getEyeHeight(), 0)),
                Objects.requireNonNull(item.position().add(0, 0.15, 0)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, worker));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }
    @Nullable static BlockPos pickupStand(ServerLevel level, WorkerEntity worker, ItemEntity item) {
        return pickupStand(level, worker, item, null);
    }
    @Nullable private static BlockPos pickupStand(ServerLevel level, WorkerEntity worker, ItemEntity item, @Nullable BlockPos preferred) {
        if (preferred != null && ConstructionManager.standable(level, preferred) && seesDrop(level, worker, preferred, item)
                && (worker.distanceToSqr(GroundResources.bottom(preferred)) <= 0.01 || GroundResources.path(level, worker, preferred) != null)) return preferred;
        var candidates = new java.util.ArrayList<BlockPos>();
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos stand = Objects.requireNonNull(item.blockPosition().offset(dx, dy, dz));
            if (ConstructionManager.standable(level, stand) && seesDrop(level, worker, stand, item)) candidates.add(stand);
        }
        candidates.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos)))));
        for (BlockPos stand : candidates) if (worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(stand))) <= 0.01 || GroundResources.path(level, worker, stand) != null) return stand;
        return null;
    }
    static boolean ownsCargo(ServerLevel level, WorkerEntity worker, ColonyJob job) {
        if (worker.getMainHandItem().isEmpty()) return false;
        var taskId = worker.cargoTask();
        if (job.id.equals(taskId)) return true;
        if (taskId != null) {
            var construction = ConstructionManager.get(level).project(taskId);
            if (construction != null && !construction.cancelled && !construction.finished()) return false;
            if (ColonyWorkManager.get(level).all().stream().anyMatch(other -> other.id.equals(taskId) && !other.cancelled && !other.finished())) return false;
        } else {
            WorkTask previous = worker.workTask;
            if (previous != null && previous.job.equals(job.id) && previous.destination != null) {
                worker.cargoTask(job.id); return true;
            }
            if (ConstructionManager.get(level).recoverConstructionCargo(worker)) return false;
        }
        // Cancelled/orphaned deliveries and legacy sweep cargo may be returned to storage.
        return true;
    }
    public static boolean execute(ServerLevel level, WorkerEntity worker, ColonyJob job, WorkTask task, ReservationLedger ledger, long time) {
        var destination = task.destination;
        if (destination == null) { job.reason = "source_changed"; return true; }
        var actor = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(job.owner, job.ownerName)));
        if (!level.mayInteract(actor, destination)) { job.reason = "denied"; return true; }
        ItemStack held = Objects.requireNonNull(worker.getMainHandItem());
        if (held.isEmpty()) {
            var sourceId = task.source;
            if (sourceId == null || !(level.getEntity(sourceId) instanceof ItemEntity item) || !item.isAlive()
                    || !contains(level, job, item) || !owned(item, worker)) { job.reason = "source_changed"; return true; }
            if (!GroundResources.loaded(level, Objects.requireNonNull(item.blockPosition())) || !level.mayInteract(actor, Objects.requireNonNull(item.blockPosition()))) { job.reason = "denied"; return true; }
            if (!GroundResources.canPickUp(level, worker, item)) {
                BlockPos pickup = task.targetStand;
                if (pickup == null || !ConstructionManager.standable(level, pickup) || !seesDrop(level, worker, pickup, item)) pickup = pickupStand(level, worker, item);
                if (pickup == null) { job.reason = "unreachable"; return true; }
                task.targetStand = pickup; job.reason = "collecting";
                if (WorkPaths.arrive(level, worker, pickup) && !GroundResources.canPickUp(level, worker, item)) { job.reason = "unreachable"; return true; }
                return false;
            }
            worker.getNavigation().stop(); job.reason = "collecting";
            worker.practice(dev.colony.camera.worker.WorkerAttribute.HAULING, time, 1);
            if (++task.actionProgress < dev.colony.camera.worker.WorkerEffects.haulTicks(worker.state())) return false;
            task.actionProgress = 0;
            var claim = ledger.claim(worker.getUUID());
            if (claim == null || !claim.project().equals(job.id) || !claim.source().equals(sourceId)
                    || item.getItem().getCount() < ledger.reserved(sourceId)) { job.reason = "source_changed"; return true; }
            if (net.minecraftforge.event.ForgeEventFactory.onItemPickup(item, actor) < 0 || !item.isAlive() || !owned(item, worker)
                    || !GroundResources.canPickUp(level, worker, item) || item.getItem().getCount() < ledger.reserved(sourceId)) {
                job.reason = "denied"; return true;
            }
            StorageAccess access = StorageAccess.at(level, destination, job.owner);
            ItemStack offered = item.getItem().copy(); offered.setCount(Math.min(task.slot, offered.getCount()));
            int wanted = offered.getCount() - access.insert(offered, true).getCount();
            if (wanted <= 0) { job.reason = "storage_full_or_unreachable"; return true; }
            ItemStack pickup = item.getItem().split(wanted);
            worker.carry(Objects.requireNonNull(pickup));
            worker.cargoTask(job.id);
            if (item.getItem().isEmpty()) item.discard(); else item.setItem(Objects.requireNonNull(item.getItem()));
            task.source = null; job.reason = "hauling"; return false;
        }
        if (worker.cargoCount() < dev.colony.camera.worker.WorkerEffects.capacity(worker.state())) {
            for (ItemEntity extra : items(level, worker, job)) {
                if (extra.hasPickUpDelay() || !ItemStack.isSameItemSameTags(held, Objects.requireNonNull(extra.getItem())) || !GroundResources.canPickUp(level, worker, Objects.requireNonNull(extra))
                        || !level.mayInteract(actor, Objects.requireNonNull(extra.blockPosition())) || net.minecraftforge.event.ForgeEventFactory.onItemPickup(extra, actor) < 0) continue;
                int quantity = Math.min(extra.getItem().getCount() - ledger.reserved(extra.getUUID()), dev.colony.camera.worker.WorkerEffects.capacity(worker.state()) - worker.cargoCount());
                if (quantity <= 0) continue;
                ItemStack combined = Objects.requireNonNull(held.copy()); combined.setCount(worker.cargoCount() + quantity); worker.carry(combined);
                extra.getItem().shrink(quantity); if (extra.getItem().isEmpty()) extra.discard();
            }
        }
        if (!StorageResources.canTake(level, worker, destination)) {
            if (!WorkPaths.arrive(level, worker, task.stand)) { job.reason = "hauling"; return false; }
            if (!StorageResources.canTake(level, worker, destination)) { job.reason = "unreachable"; return true; }
        }
        // Nearby workers can prevent exact centering. Actual reach and visibility prove the transfer is physical.
        worker.getNavigation().stop(); job.reason = "hauling";
        worker.practice(dev.colony.camera.worker.WorkerAttribute.HAULING, time, 1);
        worker.practice(dev.colony.camera.worker.WorkerAttribute.CAPACITY, time, 1);
        if (++task.actionProgress < dev.colony.camera.worker.WorkerEffects.haulTicks(worker.state())) return false;
        task.actionProgress = 0;
        while (worker.cargoCount() > 0) {
            ItemStack batch = Objects.requireNonNull(worker.getMainHandItem().copy());
            ItemStack remaining = StorageAccess.at(level, destination, job.owner).insert(batch, false);
            int accepted = batch.getCount() - remaining.getCount(); if (accepted <= 0) { job.reason = "storage_full_or_unreachable"; return true; }
            worker.consumeCargo(accepted);
        }
        worker.cargoTask(null);
        job.done++; job.reason = "queued"; return true;
    }
}
