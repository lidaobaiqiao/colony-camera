package dev.colony.camera.resources;

import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.storage.StorageNetwork;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nullable;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.charset.StandardCharsets;
import java.util.*;

@ParametersAreNonnullByDefault
public final class StorageResources {
    public record Claim(@Nonnull BlockPos access, @Nonnull BlockPos pickupStand) {}
    public record Source(@Nonnull UUID identity, @Nonnull BlockPos pos, @Nonnull Item item, int count, @Nullable IItemHandler handler, @Nullable StorageAccess network) {
        public ItemStack take(int quantity) {
            ItemStack key = new ItemStack(item);
            int obtained = 0;
            StorageAccess currentNetwork = network;
            if (currentNetwork != null) {
                while (obtained < quantity) {
                    ItemStack batch = currentNetwork.extract(key, Math.min(key.getMaxStackSize(), quantity - obtained), false);
                    if (batch.isEmpty()) break; obtained += batch.getCount();
                }
                key.setCount(obtained); return key;
            }
            IItemHandler inventory = handler;
            if (inventory != null) for (int slot = 0; slot < inventory.getSlots() && obtained < quantity; slot++) {
                while (GroundResources.matches(Objects.requireNonNull(inventory.getStackInSlot(slot)), item) && obtained < quantity) {
                    ItemStack batch = inventory.extractItem(slot, Math.min(key.getMaxStackSize(), quantity - obtained), false);
                    if (batch.isEmpty()) break; obtained += batch.getCount();
                }
            }
            key.setCount(obtained); return key;
        }
    }
    private StorageResources() {}
    public static List<BlockPos> scan(ServerLevel level, WorkerEntity worker) {
        return scan(level, worker, worker.searchRange(), false);
    }
    /** Explicit colony deliveries can recover their loaded, owned destination after temporary tasks are cleared. */
    public static List<BlockPos> colonyStores(ServerLevel level, WorkerEntity worker) {
        return scan(level, worker, worker.navigationRange(), true);
    }
    private static List<BlockPos> scan(ServerLevel level, WorkerEntity worker, int radius, boolean colonyOnly) {
        var positions = new ArrayList<BlockPos>(); BlockPos origin = worker.blockPosition();
        UUID owner = worker.owner();
        if (owner == null) return List.of();
        for (int x = (origin.getX() - radius) >> 4; x <= (origin.getX() + radius) >> 4; x++) for (int z = (origin.getZ() - radius) >> 4; z <= (origin.getZ() + radius) >> 4; z++) {
            var chunk = level.getChunkSource().getChunkNow(x, z); if (chunk == null) continue;
            for (var entity : chunk.getBlockEntities().values()) {
                if (entity.getBlockPos().distSqr(origin) > radius * radius) continue;
                if (entity instanceof MachineBlockEntity machine) {
                    if (machine.kind() != ColonyContent.Kind.WAREHOUSE && machine.kind() != ColonyContent.Kind.NETWORK_PORT || !machine.permits(owner)) continue;
                } else if (colonyOnly || !entity.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent()) continue;
                positions.add(entity.getBlockPos());
            }
        }
        positions.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.center(Objects.requireNonNull(pos)))));
        return positions.stream().limit(128).toList();
    }
    @Nullable public static Source resolve(ServerLevel level, BlockPos pos, UUID owner, Item item) {
        if (!GroundResources.loaded(level, pos)) return null;
        var entity = level.getBlockEntity(pos); if (entity == null) return null;
        BlockPos identityPos = pos;
        StorageAccess access = null; IItemHandler handler = null; int count = 0;
        if (entity instanceof MachineBlockEntity machine) {
            if (!machine.permits(owner) || machine.inventory.readOnly()) return null;
            if (machine.kind() == ColonyContent.Kind.NETWORK_PORT) {
                var network = StorageNetwork.find(level, pos, owner); if (!network.valid() || network.controller() == null) return null;
                identityPos = Objects.requireNonNull(network.controller()); access = StorageAccess.at(level, pos, owner); count = access.count(new ItemStack(item));
            } else if (machine.kind() == ColonyContent.Kind.WAREHOUSE) {
                var network = StorageNetwork.find(level, pos, owner);
                if (network.valid() || network.status().equals("multiple_controllers") || network.status().equals("too_large")) return null;
                handler = machine.inventory;
            } else return null;
        } else {
            if (entity instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity container
                    && !container.saveWithoutMetadata().getString("Lock").isEmpty()) return null;
            // Both halves of a vanilla double chest publish the same inventory.
            var state = entity.getBlockState();
            if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && state.getValue(Objects.requireNonNull(net.minecraft.world.level.block.ChestBlock.TYPE)) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                BlockPos other = pos.relative(Objects.requireNonNull(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state)));
                // Vanilla's combined capability queries the other half, so guard it before resolving.
                if (!GroundResources.loaded(level, Objects.requireNonNull(other))) return null;
                if (level.getBlockEntity(Objects.requireNonNull(other)) instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity container
                        && !container.saveWithoutMetadata().getString("Lock").isEmpty()) return null;
                if (other.asLong() < pos.asLong()) identityPos = other;
            }
            handler = entity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        }
        if (handler != null) for (int slot = 0; slot < handler.getSlots(); slot++) if (GroundResources.matches(Objects.requireNonNull(handler.getStackInSlot(slot)), item)) count = Math.addExact(count, handler.getStackInSlot(slot).getCount());
        if (count < 1) return null;
        UUID id = UUID.nameUUIDFromBytes((level.dimension().location() + "/" + identityPos.asLong() + "/" + ForgeRegistries.ITEMS.getKey(item)).getBytes(StandardCharsets.UTF_8));
        return new Source(Objects.requireNonNull(id), pos, item, count, handler, access);
    }
    public static boolean canTake(ServerLevel level, WorkerEntity worker, BlockPos pos) {
        return canTakeFrom(level, worker, Objects.requireNonNull(worker.position()), Objects.requireNonNull(worker.getEyePosition()), pos);
    }
    /** Planning and actual pickup must agree on distance and visibility. */
    public static boolean canTakeFrom(ServerLevel level, WorkerEntity worker, Vec3 feet, Vec3 eyes, BlockPos pos) {
        if (!GroundResources.loaded(level, pos) || feet.distanceToSqr(GroundResources.center(pos)) > 9) return false;
        BlockHitResult hit = level.clip(new ClipContext(eyes, GroundResources.center(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, worker));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }
}
