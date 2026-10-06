package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.storage.StorageNetwork;
import dev.colony.camera.tech.TechProgress;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.TierSortingRegistry;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Mining runs the normal survival break pipeline, using one physically obtained tool. */
@ParametersAreNonnullByDefault
public final class MiningService {
    private static final int FINISHED_PROGRESS = 10_000;
    private static final int MAX_PATHS = 16;
    private record ToolSource(@Nonnull UUID identity, @Nullable UUID ground, @Nullable BlockPos container,
            @Nonnull BlockPos pickup, int slot, @Nonnull ItemStack tool, int available) {}
    private record Inventory(@Nonnull BlockPos anchor, @Nullable IItemHandler handler, @Nullable StorageAccess network) {}
    private MiningService() {}

    @Nullable public static WorkTask assign(ServerLevel level, WorkerEntity worker, ColonyJob job,
            Set<BlockPos> claimed, ReservationLedger ledger, long time) {
        if (!GroundResources.searchAreaLoaded(level, worker)) { job.reason = "unloaded"; return null; }
        int size = job.targets.size(); if (size == 0) return null;
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < size; index++) if (!job.completed.get(index)) order.add(index);
        order.sort(Comparator.<Integer>comparingInt(index -> job.targets.get(index).getY()).reversed()
                .thenComparingDouble(index -> worker.distanceToSqr(GroundResources.center(Objects.requireNonNull(job.targets.get(index))))));
        if (order.isEmpty()) return null;
        WorkTask previous = worker.workTask;
        int start = previous != null && previous.job.equals(job.id) && order.contains(previous.targetIndex)
                ? order.indexOf(previous.targetIndex) : Math.floorMod(job.cursor, order.size());
        int[] budget = {MAX_PATHS};
        for (int offset = 0; offset < Math.min(64, order.size()); offset++) {
            int index = order.get((start + offset) % order.size()); BlockPos target = Objects.requireNonNull(job.targets.get(index));
            if (claimed.contains(target)) continue;
            if (!GroundResources.loaded(level, target)) { job.reason = "unloaded"; continue; }
            if (!matching(level, job, index)) { complete(job, index, "changed"); continue; }
            BlockState state = Objects.requireNonNull(level.getBlockState(target));
            if (state.isAir()) { complete(job, index, "changed"); continue; }
            String blocked = blockProblem(level, worker, job, target, state);
            if (!blocked.isEmpty()) { job.reason = blocked; continue; }
            BlockPos stand = previous != null && previous.job.equals(job.id) && previous.targetIndex == index
                    && previous.targetStand != null && ConstructionManager.workStandable(level, Objects.requireNonNull(previous.targetStand))
                    && canReach(level, worker, Objects.requireNonNull(GroundResources.bottom(Objects.requireNonNull(previous.targetStand)).add(0, 1.5, 0)), target)
                    ? previous.targetStand : findStand(level, worker, target, true, budget);
            if (stand == null) { job.reason = budget[0] <= 0 ? "queued" : "unreachable"; if (budget[0] <= 0) break; continue; }
            WorkTask task = new WorkTask(job.id, stand, time + 1200); task.targetIndex = index; task.targetStand = stand;
            ItemStack held = Objects.requireNonNull(worker.getOffhandItem());
            if (usableTool(level, worker, state, held)) { job.cursor = (start + offset) % order.size(); job.reason = "mining"; return task; }
            ToolSource source = findTool(level, worker, job, state, ledger, budget);
            if (source != null && ledger.reserve(worker.getUUID(), job.id, source.identity(), source.available(), 1)) {
                task.toolPickup = true; task.stand = source.pickup(); task.source = source.ground(); task.destination = source.container();
                task.slot = source.slot(); ItemStack template = source.tool().copy(); template.setCount(1); task.toolTemplate = template;
                task.materialClaims.put(source.identity(), 1);
                task.toolItem = String.valueOf(ForgeRegistries.ITEMS.getKey(source.tool().getItem()));
                job.cursor = (start + offset) % order.size(); job.reason = "collecting_tool"; return task;
            }
            if (!state.requiresCorrectToolForDrops()) { job.cursor = (start + offset) % order.size(); job.reason = "mining"; return task; }
            job.reason = "missing_tool";
            if (budget[0] <= 0) break;
        }
        job.cursor = (start + Math.min(64, order.size())) % order.size();
        return null;
    }

    /** True releases the current unit; a false result keeps walking or digging. */
    public static boolean execute(ServerLevel level, WorkerEntity worker, ColonyJob job, WorkTask task, ReservationLedger ledger, long time) {
        if (task.targetIndex < 0 || task.targetIndex >= job.targets.size()) { job.reason = "changed"; return true; }
        BlockPos target = Objects.requireNonNull(job.targets.get(task.targetIndex));
        if (!GroundResources.loaded(level, target)) { job.reason = "unloaded"; return true; }
        if (!matching(level, job, task.targetIndex)) { complete(job, task.targetIndex, "changed"); return true; }
        BlockState state = Objects.requireNonNull(level.getBlockState(target));
        if (state.isAir()) { complete(job, task.targetIndex, "changed"); return true; }
        String blocked = blockProblem(level, worker, job, target, state);
        if (!blocked.isEmpty()) { job.reason = blocked; return true; }
        if (task.toolPickup) return pickupTool(level, worker, job, task, state, ledger, time);
        if (!ConstructionManager.workStandable(level, task.stand)) { job.reason = "unreachable"; return true; }
        if (worker.distanceToSqr(GroundResources.bottom(task.stand)) > 1.2) {
            task.lastWorked = time; job.reason = "walking"; return !move(level, worker, task.stand);
        }
        if (!canReach(level, worker, Objects.requireNonNull(worker.getEyePosition()), target)) { job.reason = "unreachable"; return true; }
        worker.getNavigation().stop();
        ItemStack tool = Objects.requireNonNull(worker.getOffhandItem());
        boolean usingTool = usableTool(level, worker, state, tool);
        if (state.requiresCorrectToolForDrops() && !usingTool) { job.reason = "missing_tool"; return true; }
        FakePlayer actor = actor(level, worker, job);
        ItemStack previous = Objects.requireNonNull(actor.getMainHandItem());
        actor.setItemSlot(EquipmentSlot.MAINHAND, usingTool ? tool : Objects.requireNonNull(ItemStack.EMPTY));
        float perTick;
        try { perTick = state.getDestroyProgress(actor, level, target) * (1 + (worker.miningLevel() - 1) * 0.15F); }
        finally { actor.setItemSlot(EquipmentSlot.MAINHAND, previous); }
        int elapsed = task.lastWorked == 0 ? 1 : (int) Math.min(5, Math.max(0, time - task.lastWorked)); task.lastWorked = time;
        if (perTick <= 0 || Float.isNaN(perTick)) { job.reason = "unbreakable"; return true; }
        int increment = Math.max(1, (int) Math.min(FINISHED_PROGRESS, Math.ceil(perTick * FINISHED_PROGRESS)));
        task.progress = (int) Math.min(FINISHED_PROGRESS, (long) task.progress + (long) increment * elapsed);
        worker.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        worker.swing(Objects.requireNonNull(InteractionHand.OFF_HAND)); job.reason = "mining";
        if (task.progress < FINISHED_PROGRESS) return false;
        if (!breakBlock(level, worker, job, target, usingTool)) { job.reason = "denied"; return true; }
        complete(job, task.targetIndex, job.done + 1 >= job.total() ? "complete" : "mining");
        worker.gainMiningExperience(1);
        return true;
    }

    @Nonnull private static String blockProblem(ServerLevel level, WorkerEntity worker, ColonyJob job, BlockPos pos, BlockState state) {
        if (state.isAir()) return "changed";
        if (state.getDestroySpeed(level, pos) < 0 || !state.getFluidState().isEmpty()) return "unbreakable";
        // Container and facility teardown remains an explicit player action, never a bulk mining side effect.
        if (level.getBlockEntity(pos) != null) return "protected_block";
        if (worker.miningLevel() < minimumLevel(state)) return "mining_level";
        var online = level.getServer().getPlayerList().getPlayer(job.owner);
        if (online != null && (!online.getAbilities().mayBuild || online.isSpectator())) return "denied";
        var actor = actor(level, worker, job);
        if (!level.getWorldBorder().isWithinBounds(pos) || !level.mayInteract(Objects.requireNonNull(online != null ? online : actor), pos)) return "denied";
        return "";
    }

    public static int minimumLevel(BlockState state) {
        if (state.is(Objects.requireNonNull(BlockTags.NEEDS_DIAMOND_TOOL))) return 4;
        if (state.is(Objects.requireNonNull(BlockTags.NEEDS_IRON_TOOL))) return 3;
        if (state.is(Objects.requireNonNull(BlockTags.NEEDS_STONE_TOOL))) return 2;
        return 1;
    }

    public static boolean usableTool(ServerLevel level, WorkerEntity worker, BlockState state, ItemStack stack) {
        UUID owner = worker.owner();
        if (owner == null || stack.isEmpty() || !(stack.getItem() instanceof DiggerItem digger)) return false;
        Tier tier = Objects.requireNonNull(digger.getTier());
        int research = TechProgress.get(level).tier(owner);
        Tier maximum = List.of(Tiers.WOOD, Tiers.STONE, Tiers.IRON, Tiers.DIAMOND).get(Math.min(3, Math.max(0, research)));
        List<Tier> sorted = TierSortingRegistry.getSortedTiers();
        if (!TierSortingRegistry.isTierSorted(tier) || sorted.indexOf(tier) > sorted.indexOf(maximum)
                || tier == Tiers.GOLD && research < 2 || !TierSortingRegistry.isCorrectTierForDrops(tier, state)) return false;
        if (state.requiresCorrectToolForDrops()) return stack.isCorrectToolForDrops(state);
        return stack.getDestroySpeed(state) > 1;
    }

    @Nullable private static ToolSource findTool(ServerLevel level, WorkerEntity worker, ColonyJob job, BlockState state,
            ReservationLedger ledger, int[] budget) {
        List<ItemEntity> ground = level.getEntitiesOfClass(ItemEntity.class, Objects.requireNonNull(worker.getBoundingBox().inflate(WorkerEntity.SEARCH_RANGE)));
        ground.sort(Comparator.comparingDouble(worker::distanceToSqr));
        for (ItemEntity source : ground) {
            ItemStack stack = Objects.requireNonNull(source.getItem());
            if (!eligible(source, worker) || !usableTool(level, worker, state, stack) || stack.getCount() <= ledger.reserved(source.getUUID())) continue;
            BlockPos pickup = Objects.requireNonNull(source.blockPosition());
            if (worker.distanceToSqr(GroundResources.bottom(pickup)) > 1.2 && !path(level, worker, pickup, budget)) continue;
            return new ToolSource(Objects.requireNonNull(source.getUUID()), Objects.requireNonNull(source.getUUID()), null, pickup, -1, Objects.requireNonNull(stack.copy()), stack.getCount());
        }
        for (BlockPos position : StorageResources.scan(level, worker)) {
            BlockPos pos = Objects.requireNonNull(position); Inventory inventory = inventory(level, pos, job.owner);
            if (inventory == null) continue;
            List<ItemStack> tools = new ArrayList<>(); List<Integer> slots = new ArrayList<>();
            StorageAccess network = inventory.network(); IItemHandler handler = inventory.handler();
            if (network != null) for (var entry : network.entries()) { tools.add(entry.item()); slots.add(-1); }
            else if (handler != null) for (int slot = 0; slot < handler.getSlots(); slot++) { tools.add(Objects.requireNonNull(handler.getStackInSlot(slot))); slots.add(slot); }
            for (int i = 0; i < tools.size(); i++) {
                ItemStack tool = Objects.requireNonNull(tools.get(i)); if (!usableTool(level, worker, state, tool)) continue;
                UUID identity = identity(level, inventory.anchor(), tool);
                int count = count(inventory, tool);
                if (count <= ledger.reserved(identity)) continue;
                BlockPos stand = findStand(level, worker, pos, false, budget); if (stand == null) continue;
                return new ToolSource(identity, null, pos, stand, slots.get(i), Objects.requireNonNull(tool.copy()), count);
            }
        }
        return null;
    }

    private static boolean pickupTool(ServerLevel level, WorkerEntity worker, ColonyJob job, WorkTask task, BlockState state, ReservationLedger ledger, long time) {
        ItemStack template = task.toolTemplate;
        if (template == null || !usableTool(level, worker, state, template)) { job.reason = "missing_tool"; return true; }
        UUID sourceId = task.source; BlockPos container = task.destination;
        ItemStack taken;
        if (sourceId != null) {
            if (!(level.getEntity(sourceId) instanceof ItemEntity source) || !eligible(source, worker)
                    || !ItemStack.isSameItemSameTags(template, Objects.requireNonNull(source.getItem()))
                    || !claimValid(ledger, worker, job, sourceId, source.getItem().getCount())) { job.reason = "source_changed"; return true; }
            if (!GroundResources.canPickUp(level, worker, source)) { task.lastWorked = time; job.reason = "collecting_tool"; return !move(level, worker, Objects.requireNonNull(source.blockPosition())); }
            if (!delivery(level, worker, task)) { job.reason = "unreachable"; return true; }
            FakePlayer actor = actor(level, worker, job);
            if (ForgeEventFactory.onItemPickup(source, actor) < 0 || !eligible(source, worker)
                    || !ItemStack.isSameItemSameTags(template, Objects.requireNonNull(source.getItem()))) { job.reason = "denied"; return true; }
            if (!claimValid(ledger, worker, job, sourceId, source.getItem().getCount())) { job.reason = "source_changed"; return true; }
            if (!dropOldTool(worker, job.owner)) { job.reason = "inventory_full"; return true; }
            if (!eligible(source, worker) || !ItemStack.isSameItemSameTags(template, Objects.requireNonNull(source.getItem()))
                    || !claimValid(ledger, worker, job, sourceId, source.getItem().getCount())) { job.reason = "source_changed"; return true; }
            taken = source.getItem().copy(); taken.setCount(1);
            ItemStack remaining = source.getItem().copy(); remaining.shrink(1);
            if (remaining.isEmpty()) source.discard(); else source.setItem(remaining);
        } else if (container != null) {
            Inventory inventory = inventory(level, container, job.owner);
            if (inventory == null || !claimValid(ledger, worker, job, identity(level, inventory.anchor(), template), count(inventory, template))) { job.reason = "source_changed"; return true; }
            if (!StorageResources.canTake(level, worker, container)) { task.lastWorked = time; job.reason = "collecting_tool"; return !move(level, worker, task.stand); }
            if (!level.mayInteract(actor(level, worker, job), container)) { job.reason = "denied"; return true; }
            if (!delivery(level, worker, task)) { job.reason = "unreachable"; return true; }
            if (!dropOldTool(worker, job.owner)) { job.reason = "inventory_full"; return true; }
            // Spawning an old tool may invoke other mods; re-resolve topology and stock after that hook.
            inventory = inventory(level, container, job.owner);
            if (inventory == null || !claimValid(ledger, worker, job, identity(level, inventory.anchor(), template), count(inventory, template))) { job.reason = "source_changed"; return true; }
            taken = take(inventory, template, task.slot);
            if (taken.isEmpty()) { job.reason = "source_changed"; return true; }
        } else { job.reason = "source_changed"; return true; }
        worker.setItemSlot(EquipmentSlot.OFFHAND, Objects.requireNonNull(taken));
        BlockPos targetStand = task.targetStand;
        if (targetStand == null) { job.reason = "unreachable"; return true; }
        task.stand = targetStand; task.toolPickup = false; task.source = null; task.destination = null;
        task.deadline = time + 1200; task.lastWorked = time; job.reason = "walking";
        return false;
    }

    @Nullable private static Inventory inventory(ServerLevel level, BlockPos pos, UUID owner) {
        if (!GroundResources.loaded(level, pos)) return null;
        var entity = level.getBlockEntity(pos); if (entity == null) return null;
        BlockPos canonical = pos;
        StorageAccess network = null; IItemHandler handler = null;
        if (entity instanceof MachineBlockEntity machine) {
            if (!machine.permits(owner) || machine.inventory.readOnly()) return null;
            if (machine.kind() == ColonyContent.Kind.NETWORK_PORT) {
                var component = StorageNetwork.find(level, pos, owner);
                if (!component.valid() || component.controller() == null) return null;
                canonical = Objects.requireNonNull(component.controller()); network = StorageAccess.at(level, pos, owner);
            } else if (machine.kind() == ColonyContent.Kind.WAREHOUSE) {
                var component = StorageNetwork.find(level, pos, owner);
                if (component.valid() || component.status().equals("multiple_controllers") || component.status().equals("too_large")) return null;
                handler = machine.inventory;
            } else return null;
        } else {
            if (entity instanceof BaseContainerBlockEntity chest && !chest.saveWithoutMetadata().getString("Lock").isEmpty()) return null;
            BlockState state = Objects.requireNonNull(entity.getBlockState());
            if (state.getBlock() instanceof ChestBlock && state.getValue(Objects.requireNonNull(ChestBlock.TYPE)) != ChestType.SINGLE) {
                BlockPos other = Objects.requireNonNull(pos.relative(Objects.requireNonNull(ChestBlock.getConnectedDirection(state))));
                if (!GroundResources.loaded(level, other)) return null;
                if (level.getBlockEntity(other) instanceof BaseContainerBlockEntity chest && !chest.saveWithoutMetadata().getString("Lock").isEmpty()) return null;
                if (other.asLong() < canonical.asLong()) canonical = other;
            }
            handler = entity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        }
        if (handler == null && network == null) return null;
        return new Inventory(canonical, handler, network);
    }

    @Nonnull private static UUID identity(ServerLevel level, BlockPos canonical, ItemStack item) {
        var tag = item.getTag();
        // CompoundTag's SNBT visitor sorts keys; equal damage/name/enchantment variants share a claim.
        String variant = tag == null || tag.isEmpty() ? "" : "/" + tag;
        return Objects.requireNonNull(UUID.nameUUIDFromBytes((level.dimension().location() + "/" + canonical.asLong() + "/"
                + ForgeRegistries.ITEMS.getKey(item.getItem()) + variant).getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean claimValid(ReservationLedger ledger, WorkerEntity worker, ColonyJob job, UUID source, int stock) {
        var claim = ledger.claim(worker.getUUID());
        return claim != null && claim.project().equals(job.id) && claim.source().equals(source) && claim.quantity() >= 1
                && stock >= ledger.reserved(source);
    }

    private static int count(Inventory inventory, ItemStack template) {
        StorageAccess network = inventory.network(); if (network != null) return network.count(template);
        IItemHandler handler = inventory.handler(); int total = 0;
        if (handler != null) for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = Objects.requireNonNull(handler.getStackInSlot(slot));
            if (ItemStack.isSameItemSameTags(template, stack)) total = Math.addExact(total, stack.getCount());
        }
        return total;
    }

    @Nonnull private static ItemStack take(Inventory inventory, ItemStack template, int preferredSlot) {
        StorageAccess network = inventory.network(); if (network != null) return network.extract(template, 1, false);
        IItemHandler handler = inventory.handler();
        if (handler != null) {
            if (preferredSlot >= 0 && preferredSlot < handler.getSlots()
                    && ItemStack.isSameItemSameTags(template, Objects.requireNonNull(handler.getStackInSlot(preferredSlot)))) return Objects.requireNonNull(handler.extractItem(preferredSlot, 1, false));
            for (int slot = 0; slot < handler.getSlots(); slot++) if (ItemStack.isSameItemSameTags(template, Objects.requireNonNull(handler.getStackInSlot(slot)))) return Objects.requireNonNull(handler.extractItem(slot, 1, false));
        }
        return Objects.requireNonNull(ItemStack.EMPTY);
    }

    private static boolean eligible(ItemEntity source, WorkerEntity worker) {
        if (!source.isAlive() || source.hasPickUpDelay() || source.getItem().isEmpty()) return false;
        CompoundTag tag = new CompoundTag(); source.saveWithoutId(tag);
        return !tag.hasUUID("Owner") || tag.getUUID("Owner").equals(worker.owner());
    }

    private static boolean delivery(ServerLevel level, WorkerEntity worker, WorkTask task) {
        BlockPos stand = task.targetStand;
        return stand != null && ConstructionManager.workStandable(level, stand)
                && (worker.distanceToSqr(GroundResources.bottom(stand)) <= 1.2 || GroundResources.path(level, worker, stand) != null);
    }

    private static boolean dropOldTool(WorkerEntity worker, UUID owner) {
        ItemStack old = Objects.requireNonNull(worker.getOffhandItem()); if (old.isEmpty()) return true;
        ItemEntity dropped = worker.spawnAtLocation(Objects.requireNonNull(old.copy())); if (dropped == null) return false;
        dropped.setTarget(owner); worker.setItemSlot(EquipmentSlot.OFFHAND, Objects.requireNonNull(ItemStack.EMPTY)); return true;
    }

    @Nonnull private static FakePlayer actor(ServerLevel level, WorkerEntity worker, ColonyJob job) {
        FakePlayer actor = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(job.owner, job.ownerName)));
        actor.gameMode.changeGameModeForPlayer(Objects.requireNonNull(GameType.SURVIVAL));
        actor.setPos(worker.getX(), worker.getY(), worker.getZ()); actor.setOnGround(worker.onGround());
        return actor;
    }

    private static boolean breakBlock(ServerLevel level, WorkerEntity worker, ColonyJob job, BlockPos target, boolean usingTool) {
        FakePlayer actor = actor(level, worker, job);
        ItemStack previous = Objects.requireNonNull(actor.getMainHandItem());
        ItemStack actual = Objects.requireNonNull(worker.getOffhandItem());
        if (usingTool) worker.setItemSlot(EquipmentSlot.OFFHAND, Objects.requireNonNull(ItemStack.EMPTY));
        actor.setItemSlot(EquipmentSlot.MAINHAND, usingTool ? actual : Objects.requireNonNull(ItemStack.EMPTY));
        try {
            boolean result = actor.gameMode.destroyBlock(target);
            // Some modded blocks refuse removal without cancelling the Forge event.
            return result && !matching(level, job, job.targets.indexOf(target));
        } finally {
            if (usingTool) worker.setItemSlot(EquipmentSlot.OFFHAND, Objects.requireNonNull(actor.getMainHandItem()));
            actor.setItemSlot(EquipmentSlot.MAINHAND, previous);
        }
    }

    private static boolean matching(ServerLevel level, ColonyJob job, int index) {
        return index >= 0 && index < job.expectedStates.size()
                && BlockStateParser.serialize(Objects.requireNonNull(level.getBlockState(Objects.requireNonNull(job.targets.get(index))))).equals(job.expectedStates.get(index));
    }

    private static void complete(ColonyJob job, int index, String reason) {
        job.completed.set(index); job.done = job.completed.cardinality(); job.reason = reason;
    }

    @Nullable private static BlockPos findStand(ServerLevel level, WorkerEntity worker, BlockPos target, boolean mining, int[] budget) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -2; dy <= 1; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx == 0 && dz == 0 || Math.abs(dx) + Math.abs(dz) > 3) continue;
            BlockPos pos = Objects.requireNonNull(target.offset(dx, dy, dz));
            if (!ConstructionManager.workStandable(level, pos)) continue;
            if (mining && !canReach(level, worker, Objects.requireNonNull(GroundResources.bottom(pos).add(0, 1.5, 0)), target)) continue;
            candidates.add(pos);
        }
        candidates.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos)))));
        for (BlockPos pos : candidates) {
            if (worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos))) <= 1.2
                    || path(level, worker, pos, budget)
                    || !worker.onGround() && worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos)))
                    <= (long) WorkerEntity.NAVIGATION_RANGE * WorkerEntity.NAVIGATION_RANGE) return pos;
            if (budget[0] <= 0) return null;
        }
        return null;
    }

    private static boolean canReach(ServerLevel level, WorkerEntity worker, Vec3 eyes, BlockPos target) {
        Vec3 center = GroundResources.center(target); if (eyes.distanceToSqr(center) > 16) return false;
        var hit = level.clip(new ClipContext(eyes, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, worker));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static boolean path(ServerLevel level, WorkerEntity worker, BlockPos target, int[] budget) {
        if (budget[0] <= 0) return false; budget[0]--;
        return GroundResources.path(level, worker, target) != null;
    }

    private static boolean move(ServerLevel level, WorkerEntity worker, BlockPos target) {
        if (!worker.getNavigation().isDone()) return true;
        Path path = GroundResources.path(level, worker, target);
        if (path == null) return false;
        return GroundResources.follow(level, worker, path);
    }
}
