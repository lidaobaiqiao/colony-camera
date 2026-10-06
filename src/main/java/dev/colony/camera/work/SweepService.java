package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayerFactory;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pickup and deposit are separate transfers. A full or disconnected destination leaves cargo in hand. */
@ParametersAreNonnullByDefault
public final class SweepService {
    private SweepService() {}
    public static AABB bounds(ColonyJob job) {
        BlockPos min = job.targets.get(0), max = job.targets.get(1);
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
    }
    private static boolean owned(ItemEntity item, WorkerEntity worker) {
        CompoundTag tag = new CompoundTag(); item.saveWithoutId(tag);
        return !tag.hasUUID("Owner") || tag.getUUID("Owner").equals(worker.owner());
    }
    private static List<ItemEntity> items(ServerLevel level, WorkerEntity worker, ColonyJob job) {
        return level.getEntitiesOfClass(ItemEntity.class, Objects.requireNonNull(bounds(job))).stream()
                .filter(item -> item.isAlive() && !item.getItem().isEmpty() && owned(Objects.requireNonNull(item), worker))
                .sorted(Comparator.comparingDouble(worker::distanceToSqr)).toList();
    }
    @Nullable public static WorkTask assign(ServerLevel level, WorkerEntity worker, ColonyJob job, ReservationLedger ledger, long time) {
        BlockPos min = job.targets.get(0), max = job.targets.get(1);
        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) {
            if (!level.getChunkSource().hasChunk(x, z)) { job.reason = "unloaded"; return null; }
        }
        ItemStack cargo = Objects.requireNonNull(worker.getMainHandItem());
        var items = items(level, worker, job);
        if (cargo.isEmpty() && items.isEmpty()) {
            job.completed.set(0); job.requested = Math.max(1, job.done); job.done = job.requested; job.reason = "complete"; return null;
        }
        job.requested = Math.max(job.requested, job.done + items.size());
        for (BlockPos destination : StorageResources.scan(level, worker)) {
            if (!(level.getBlockEntity(Objects.requireNonNull(destination)) instanceof MachineBlockEntity machine)
                    || machine.kind() != ColonyContent.Kind.WAREHOUSE && machine.kind() != ColonyContent.Kind.NETWORK_PORT) continue;
            StorageAccess access = StorageAccess.at(level, destination, job.owner);
            if (access.inventories.isEmpty()) continue;
            BlockPos deposit = WorkPaths.beside(level, worker, destination); if (deposit == null) continue;
            if (!cargo.isEmpty()) {
                if (access.insert(cargo, true).getCount() >= cargo.getCount()) continue;
                WorkTask task = new WorkTask(job.id, deposit, time + 1200); task.destination = destination; return task;
            }
            for (ItemEntity item : items.stream().limit(128).toList()) {
                if (item.hasPickUpDelay() || worker.distanceToSqr(item) > WorkerEntity.NAVIGATION_RANGE * WorkerEntity.NAVIGATION_RANGE) continue;
                var target = Objects.requireNonNull(item.blockPosition());
                if (!GroundResources.loaded(level, target)) continue;
                if (!GroundResources.canPickUp(level, worker, item) && GroundResources.path(level, worker, target) == null
                        && (worker.onGround() || worker.distanceToSqr(GroundResources.bottom(target))
                        > (long) WorkerEntity.NAVIGATION_RANGE * WorkerEntity.NAVIGATION_RANGE)) continue;
                ItemStack offered = item.getItem().copy(); offered.setCount(Math.min(offered.getCount(), offered.getMaxStackSize()));
                int wanted = Math.min(offered.getCount() - access.insert(offered, true).getCount(), item.getItem().getCount() - ledger.reserved(item.getUUID()));
                if (wanted < 1 || !ledger.reserve(worker.getUUID(), job.id, item.getUUID(), item.getItem().getCount(), wanted)) continue;
                WorkTask task = new WorkTask(job.id, deposit, time + 1200);
                task.destination = destination; task.targetStand = target; task.source = item.getUUID(); task.slot = wanted; return task;
            }
        }
        job.reason = cargo.isEmpty() && items.stream().anyMatch((ItemEntity checkedElement) -> Objects.requireNonNull(checkedElement).hasPickUpDelay()) ? "pickup_delay" : "storage_full_or_unreachable";
        return null;
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
                    || !bounds(job).contains(Objects.requireNonNull(item.position())) || !owned(item, worker)) { job.reason = "source_changed"; return true; }
            if (!GroundResources.loaded(level, Objects.requireNonNull(item.blockPosition())) || !level.mayInteract(actor, Objects.requireNonNull(item.blockPosition()))) { job.reason = "denied"; return true; }
            if (!GroundResources.canPickUp(level, worker, item)) {
                job.reason = "collecting"; WorkPaths.arrive(level, worker, Objects.requireNonNull(item.blockPosition())); return false;
            }
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
            worker.setItemSlot(EquipmentSlot.MAINHAND, Objects.requireNonNull(pickup));
            if (item.getItem().isEmpty()) item.discard(); else item.setItem(Objects.requireNonNull(item.getItem()));
            task.source = null; job.reason = "hauling"; return false;
        }
        if (!WorkPaths.arrive(level, worker, task.stand)) { job.reason = "hauling"; return false; }
        if (!StorageResources.canTake(level, worker, destination)) { job.reason = "unreachable"; return true; }
        ItemStack remaining = StorageAccess.at(level, destination, job.owner).insert(held, false);
        worker.setItemSlot(EquipmentSlot.MAINHAND, remaining);
        if (!remaining.isEmpty()) { job.reason = "storage_full_or_unreachable"; return true; }
        job.done++; job.reason = "queued"; return true;
    }
}
