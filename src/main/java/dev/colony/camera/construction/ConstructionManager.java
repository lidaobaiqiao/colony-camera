package dev.colony.camera.construction;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.colony.camera.network.ConstructionNetwork;
import dev.colony.camera.planning.ProjectionCodec;
import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.content.PrintingCores;
import dev.colony.camera.work.ColonyJob;
import dev.colony.camera.work.ColonyWorkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import org.slf4j.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Per-dimension authority. Reallocation always runs in strict project order before NPC execution. */
@ParametersAreNonnullByDefault
public final class ConstructionManager extends SavedData {
    public static final int MAX_PROJECTS = 32;
    public static final int MAX_BLOCKS = 32768;
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<UUID, ConstructionProject> projects = new LinkedHashMap<>();
    @Nonnull private final ReservationLedger ledger = new ReservationLedger();
    private final Map<UUID, Map<Item, List<ItemEntity>>> ground = new LinkedHashMap<>();
    private final Map<UUID, List<BlockPos>> storage = new LinkedHashMap<>();
    private record SourceAttempt(UUID worker, UUID source) {}
    private final Map<SourceAttempt, Long> failedSources = new LinkedHashMap<>();
    private long nextSequence;
    private boolean reallocate = true;
    @Nullable private CompoundTag rejectedData;
    private int pathBudget;
    @Nonnull private List<WorkerEntity> lastWorkers = Objects.requireNonNull(List.of());
    @Nonnull public ReservationLedger ledger() { return ledger; }
    private record DispatchEntry(ProjectOrder order, @Nullable ConstructionProject construction, @Nullable ColonyJob work) {}
    public long allocateSequence() { long value = nextSequence; nextSequence = Math.addExact(nextSequence, 1); setDirty(); return value; }
    public void sequenceFloor(long floor) { if (floor > nextSequence) { nextSequence = floor; setDirty(); } }
    public static final class Assignment {
        @Nonnull public final UUID project;
        public final int unit;
        @Nonnull public final BlockPos stand;
        @Nullable public UUID source;
        @Nullable public StorageResources.Claim storage;
        public long deadline;
        public int actionProgress;
        public Assignment(UUID project, int unit, BlockPos stand, @Nullable UUID source, long deadline) {
            this.project = project; this.unit = unit; this.stand = stand; this.source = source; this.deadline = deadline;
        }
    }

    @Nonnull public static ConstructionManager get(ServerLevel level) {
        return Objects.requireNonNull(level.getDataStorage().computeIfAbsent(ConstructionManager::load,
                ConstructionManager::new, "colonycamera_construction"));
    }
    @Nonnull public List<ConstructionProject> projects(UUID owner) {
        return Objects.requireNonNull(projects.values().stream().filter(p -> p.owner.equals(owner))
                .sorted(Comparator.comparing((ConstructionProject checkedElement) -> Objects.requireNonNull(checkedElement).order(), ProjectOrder.COMPARATOR)).toList());
    }
    @Nonnull public List<WorkerEntity> workers(ServerLevel level) {
        var result = new ArrayList<WorkerEntity>();
        for (var entity : level.getAllEntities()) if (entity instanceof WorkerEntity worker && worker.isAlive()) result.add(worker);
        result.sort(Comparator.comparingInt((WorkerEntity checkedElement) -> Objects.requireNonNull(checkedElement).getId()));
        return Objects.requireNonNull(result);
    }
    public void create(ServerPlayer player, ProjectionPlacement placement, int priority) {
        if (rejectedData != null) throw new IllegalArgumentException("data");
        ServerLevel level = Objects.requireNonNull(player.serverLevel());
        if (player.isSpectator() || !player.getAbilities().mayBuild) throw new IllegalArgumentException("location");
        if (projects.containsKey(placement.id())) throw new IllegalArgumentException("duplicate");
        List<ConstructionProject> owned = projects(Objects.requireNonNull(player.getUUID()));
        ColonyWorkManager additional = ColonyWorkManager.get(level);
        nextSequence = Math.max(nextSequence, additional.nextSequenceFloor());
        if (owned.size() + additional.jobs(Objects.requireNonNull(player.getUUID())).size() >= MAX_PROJECTS || owned.stream().mapToInt(p -> p.placement.preset().blocks().size()).sum()
                + placement.preset().blocks().size() > MAX_BLOCKS) throw new IllegalArgumentException("limit");
        ConstructionProject project = new ConstructionProject(placement, Objects.requireNonNull(player.getUUID()),
                Objects.requireNonNull(player.getGameProfile().getName()), priority, nextSequence);
        Set<BlockPos> positions = new HashSet<>();
        List<BlockPos> coreOrigins = PrintingCores.owned(level, Objects.requireNonNull(player.getUUID()));
        for (var unit : project.plan.units()) for (var block : unit.blocks()) {
            if (!GroundResources.loaded(level, block.pos()) || level.isOutsideBuildHeight(block.pos()) || !level.getWorldBorder().isWithinBounds(block.pos())
                    || player.distanceToSqr(GroundResources.center(block.pos())) > 128 * 128
                    && coreOrigins.stream().noneMatch(core -> core.distSqr(block.pos()) <= 128 * 128)
                    || !level.mayInteract(player, block.pos())) {
                throw new IllegalArgumentException("location");
            }
            positions.add(block.pos());
        }
        for (var existing : projects.values()) {
            if (existing.cancelled || existing.finished()) continue;
            for (var unit : existing.plan.units()) for (var block : unit.blocks()) {
                if (positions.contains(block.pos())) throw new IllegalArgumentException("overlap");
            }
        }
        nextSequence = Math.addExact(nextSequence, 1);
        projects.put(project.id(), project); reallocate = true; setDirty();
    }
    public void update(UUID owner, UUID id, String action, int value) {
        if (rejectedData != null) return;
        ConstructionProject project = projects.get(id);
        if (project == null || !project.owner.equals(owner)) return;
        switch (action) {
            case "priority" -> project.priority = new ProjectOrder(id, value, project.sequence).priority();
            case "pause" -> project.paused = !project.paused;
            case "cancel" -> project.cancelled = true;
            case "remove" -> { if (project.cancelled || project.finished()) projects.remove(id); }
            default -> { return; }
        }
        reallocate = true; setDirty();
    }
    @Nonnull public String printWorker(ServerPlayer player, BlockPos core) {
        return printWorker(player, core, dev.colony.camera.worker.WorkerProfile.Interest.NONE, dev.colony.camera.worker.WorkerProfile.Temperament.NONE);
    }
    @Nonnull public String printWorker(ServerPlayer player, BlockPos core, dev.colony.camera.worker.WorkerProfile.Interest interest,
            dev.colony.camera.worker.WorkerProfile.Temperament temperament) {
        if (rejectedData != null) return "data";
        if (player.isSpectator() || !player.getAbilities().mayBuild) return "denied";
        ServerLevel level = Objects.requireNonNull(player.serverLevel());
        if (!GroundResources.loaded(level, core) || !(level.getBlockEntity(core) instanceof MachineBlockEntity machine)
                || machine.kind() != ColonyContent.Kind.PRINTING_CORE || !machine.permits(Objects.requireNonNull(player.getUUID())) || !level.mayInteract(player, core)) return "no_core";
        if (level.getGameTime() < machine.nextPrint) return "cooldown";
        if (workers(level).stream().filter(w -> player.getUUID().equals(w.owner())).count() >= dev.colony.camera.config.WorkerConfig.POPULATION_LIMIT.get()) return "limit";
        if (!dev.colony.camera.worker.WorkerProfile.compatible(interest, temperament)) return "profile";
        for (WorkerEntity existing : workers(level)) if (player.getUUID().equals(existing.owner())) dev.colony.camera.worker.Residences.assign(level, Objects.requireNonNull(existing));
        WorkerEntity worker = ColonyEntities.WORKER.get().create(level);
        if (worker == null) return "failed";
        for (int dy : new int[]{0, 1, -1, 2, -2}) for (int ring = 1; ring <= 3; ring++) for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
            BlockPos pos = Objects.requireNonNull(core.offset(dx, dy, dz));
            if (!standable(level, pos) || !level.mayInteract(player, pos) || !level.getEntitiesOfClass(LivingEntity.class, GroundResources.body(pos)).isEmpty()) continue;
            worker.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0);
            worker.owner(Objects.requireNonNull(player.getUUID()));
            worker.state().birth(interest, temperament); worker.applyStateEffects();
            if (level.addFreshEntity(worker)) {
                // Assign housing when available; a vacant room is never a printing prerequisite.
                dev.colony.camera.worker.Residences.assign(level, worker);
                machine.nextPrint = level.getGameTime() + 20; machine.setChanged();
                level.setBlock(core, Objects.requireNonNull(machine.getBlockState().setValue(Objects.requireNonNull(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT), true)), 3);
                level.scheduleTick(core, Objects.requireNonNull(machine.getBlockState().getBlock()), 20);
                reallocate = true; return "success";
            }
        }
        return "blocked";
    }

    public void tick(ServerLevel level) {
        if (rejectedData != null) return;
        long time = level.getGameTime();
        ColonyWorkManager work = ColonyWorkManager.get(level);
        work.synchronizeSupplies(this);
        List<WorkerEntity> workers = time % 5 == 0 ? workers(level) : lastWorkers;
        if (time % 5 == 0) lastWorkers = workers;
        boolean workChanged = work.needsDispatch();
        if (time % 5 == 0 && (reallocate || workChanged || time % 20 == 0)) {
            dispatch(level, workers, time); reallocate = false;
        }
        pathBudget = time % 5 == 0 ? 16 : 4;
        for (WorkerEntity worker : workers) execute(level, Objects.requireNonNull(worker), time);
        work.tick(level, workers, ledger);
        if (time % 20 == 0) {
            for (ServerPlayer player : level.players()) ConstructionNetwork.snapshot(Objects.requireNonNull(player), this, workers);
        }
    }
    private void dispatch(ServerLevel level, List<WorkerEntity> workers, long time) {
        ledger.clear(); ground.clear(); storage.clear(); pathBudget = 40;
        failedSources.values().removeIf(until -> until <= time);
        Set<UUID> assigned = new HashSet<>();
        Set<String> units = new HashSet<>();
        Set<BlockPos> excavation = new HashSet<>();
        ColonyWorkManager work = ColonyWorkManager.get(level);
        var orders = new ArrayList<DispatchEntry>();
        for (var project : projects.values()) orders.add(new DispatchEntry(project.order(), project, null));
        for (var job : work.all()) orders.add(new DispatchEntry(job.order(), null, job));
        orders.sort(Comparator.comparing((DispatchEntry checkedElement) -> Objects.requireNonNull(checkedElement).order(), ProjectOrder.COMPARATOR));
        for (DispatchEntry entry : orders) {
            ColonyJob job = entry.work();
            if (job != null) { work.dispatchJob(level, job, workers, assigned, excavation, ledger, time); continue; }
            ConstructionProject project = Objects.requireNonNull(entry.construction());
            project.activeWorkers = 0; project.groundKnown = 0; project.reserved = 0;
            project.neededItem = ""; project.neededCount = 0;
            project.reason = project.cancelled ? "cancelled" : project.paused ? "paused" : project.finished() ? "complete" : "no_worker";
            if (!project.runnable()) continue;
            int remainingBudget = pathBudget;
            int projectBudget = Math.min(16, pathBudget);
            pathBudget = projectBudget;
            String activeReason = "";
            for (WorkerEntity worker : workers) {
                if (!worker.available() || !worker.preferences().allows(dev.colony.camera.worker.WorkerPreferences.Task.BUILD) && !project.id().equals(worker.cargoTask())
                        || assigned.contains(worker.getUUID()) || !project.owner.equals(worker.owner())) continue;
                project.reason = "queued";
                Assignment previous = worker.assignment;
                Assignment next = findAssignment(level, worker, project, previous, units, time);
                if (next == null) continue;
                assigned.add(worker.getUUID()); units.add(project.id() + ":" + next.unit);
                project.activeWorkers++;
                if (next.source != null) project.reserved += project.plan.units().get(next.unit).count();
                if (previous == null || !previous.project.equals(next.project) || previous.unit != next.unit
                        || !Objects.equals(previous.source, next.source) || !previous.stand.equals(next.stand) || !Objects.equals(previous.storage, next.storage)) {
                    worker.getNavigation().stop();
                    if (!worker.getMainHandItem().isEmpty() && !GroundResources.matches(Objects.requireNonNull(worker.getMainHandItem()), project.plan.units().get(next.unit).item())) dropCarried(worker);
                } else { next.deadline = previous.deadline; next.actionProgress = previous.actionProgress; }
                worker.assignment = next;
                worker.workTask = null;
                if (next.source == null) worker.cargoTask(project.id());
                project.reason = next.source != null ? "collecting" : "building";
                activeReason = project.reason;
            }
            if (!activeReason.isEmpty()) project.reason = activeReason;
            pathBudget = remainingBudget - (projectBudget - pathBudget);
        }
        for (WorkerEntity worker : workers) if (!assigned.contains(worker.getUUID())) {
            worker.assignment = null; worker.workTask = null; if (worker.available()) worker.getNavigation().stop();
        }
    }
    /** Recover untagged pre-1.6.1 deliveries before an older sweep can take them. */
    public boolean recoverConstructionCargo(WorkerEntity worker) {
        ItemStack held = Objects.requireNonNull(worker.getMainHandItem());
        if (held.isEmpty()) return false;
        UUID taskId = worker.cargoTask();
        ConstructionProject tagged = taskId == null ? null : projects.get(taskId);
        if (tagged != null && tagged.owner.equals(worker.owner()) && !tagged.cancelled && !tagged.finished()) return true;
        if (taskId != null) return false;
        for (ConstructionProject project : projects.values()) {
            if (!project.owner.equals(worker.owner()) || project.cancelled || project.finished()) continue;
            for (int index = 0; index < project.plan.units().size(); index++) {
                if (project.completed.get(index)) continue;
                BuildPlan.Unit unit = Objects.requireNonNull(project.plan.units().get(index));
                if (worker.cargoCount() >= unit.count() && GroundResources.matches(held, unit.item())
                        && worker.distanceToSqr(GroundResources.center(unit.anchor())) <= (long) WorkerEntity.NAVIGATION_RANGE * WorkerEntity.NAVIGATION_RANGE) {
                    worker.cargoTask(project.id()); return true;
                }
            }
        }
        return false;
    }
    @Nullable private Assignment findAssignment(ServerLevel level, WorkerEntity worker, ConstructionProject project,
            @Nullable Assignment previous, Set<String> claimed, long time) {
        if (!GroundResources.searchAreaLoaded(level, worker)) { project.reason = "unloaded"; return null; }
        UUID cargoId = worker.cargoTask(); ConstructionProject delivery = cargoId == null ? null : projects.get(cargoId);
        if (delivery != null && !delivery.id().equals(project.id()) && !delivery.cancelled && !delivery.finished()
                && (delivery.paused || ProjectOrder.COMPARATOR.compare(delivery.order(), project.order()) <= 0)) {
            project.reason = "queued"; return null;
        }
        int size = project.plan.units().size();
        int start = previous != null && previous.project.equals(project.id()) ? previous.unit : project.cursor;
        for (int offset = 0; offset < Math.min(64, size); offset++) {
            int index = (start + offset) % size;
            if (project.completed.get(index) || claimed.contains(project.id() + ":" + index)) continue;
            BuildPlan.Unit unit = Objects.requireNonNull(project.plan.units().get(index));
            if (matching(level, unit)) { project.completed.set(index); setDirty(); continue; }
            project.neededItem = Objects.requireNonNull(Objects.requireNonNull(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(unit.item())).toString());
            project.neededCount = unit.count();
            String problem = problem(level, unit, project.palette);
            if (!problem.isEmpty()) { project.reason = problem; continue; }
            ItemStack carried = Objects.requireNonNull(worker.getMainHandItem());
            boolean carrying = GroundResources.matches(carried, unit.item()) && worker.cargoCount() >= unit.count();
            List<ItemEntity> sources = carrying ? Objects.requireNonNull(List.of()) : Objects.requireNonNull(ground
                    .computeIfAbsent(worker.getUUID(), ignored -> GroundResources.scan(level, worker)).getOrDefault(unit.item(), List.of()));
            project.groundKnown = sources.stream().mapToInt(e -> e.getItem().getCount()).sum();
            var stored = new ArrayList<StorageResources.Source>();
            if (!carrying) for (BlockPos pos : storage.computeIfAbsent(worker.getUUID(), ignored -> StorageResources.scan(level, worker))) {
                StorageResources.Source source = StorageResources.resolve(level, Objects.requireNonNull(pos), project.owner, unit.item());
                if (source != null) stored.add(source);
            }
            Map<UUID, Integer> uniqueStock = new LinkedHashMap<>();
            for (var source : stored) uniqueStock.put(source.identity(), source.count());
            project.groundKnown += uniqueStock.values().stream().mapToInt((Integer checkedElement) -> Objects.requireNonNull(checkedElement).intValue()).sum();
            if (!carrying && sources.isEmpty() && stored.isEmpty()) { project.reason = "missing_material"; continue; }
            BlockPos stand = findStand(level, worker, unit);
            if (stand == null) { project.reason = pathBudget <= 0 ? "queued" : "unreachable"; if (pathBudget <= 0) break; continue; }
            if (carrying) {
                project.cursor = index;
                return new Assignment(project.id(), index, stand, null, time + 240);
            }
            project.reason = sources.isEmpty() ? "missing_material" : "unreachable";
            for (ItemEntity source : sources) {
                SourceAttempt attempt = new SourceAttempt(Objects.requireNonNull(worker.getUUID()), Objects.requireNonNull(source.getUUID()));
                if (failedSources.containsKey(attempt)) continue;
                if (source.getItem().getCount() - ledger.reserved(source.getUUID()) < unit.count()) continue;
                if (!usePathBudget()) { project.reason = "queued"; return null; }
                Path path = GroundResources.path(level, worker, Objects.requireNonNull(source.blockPosition()));
                if (path == null) { failedSources.put(attempt, time + 100); continue; }
                int quantity = Math.min(source.getItem().getCount() - ledger.reserved(source.getUUID()), materialBatch(worker, project, unit));
                if (ledger.reserve(worker.getUUID(), project.id(), source.getUUID(), source.getItem().getCount(), quantity)) {
                    project.cursor = index;
                    return new Assignment(project.id(), index, stand, Objects.requireNonNull(source.getUUID()), time + 240);
                }
            }
            for (var source : stored) {
                // Failed access to one port must not blacklist every other port of its network.
                UUID accessAttempt = UUID.nameUUIDFromBytes((source.identity() + "/" + source.pos().asLong()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                SourceAttempt attempt = new SourceAttempt(Objects.requireNonNull(worker.getUUID()), Objects.requireNonNull(accessAttempt));
                if (failedSources.containsKey(attempt)) continue;
                if (source.count() - ledger.reserved(source.identity()) < unit.count()) continue;
                BlockPos pickup = findStorageStand(level, worker, source.pos());
                if (pickup == null) { if (pathBudget > 0) failedSources.put(attempt, time + 100); continue; }
                int quantity = Math.min(source.count() - ledger.reserved(source.identity()), materialBatch(worker, project, unit));
                if (ledger.reserve(worker.getUUID(), project.id(), source.identity(), source.count(), quantity)) {
                    project.cursor = index;
                    Assignment next = new Assignment(project.id(), index, stand, Objects.requireNonNull(source.identity()), time + 240);
                    next.storage = new StorageResources.Claim(source.pos(), pickup); return next;
                }
            }
            if (pathBudget <= 0) break;
        }
        project.cursor = (start + Math.min(64, size)) % size;
        if (project.finished()) project.reason = "complete";
        return null;
    }

    private void execute(ServerLevel level, WorkerEntity worker, long time) {
        if (!worker.available()) return;
        Assignment task = worker.assignment;
        if (task == null) return;
        ConstructionProject project = projects.get(task.project);
        if (project == null || !project.runnable() || time > task.deadline || !GroundResources.searchAreaLoaded(level, worker)) {
            release(worker); reallocate = true; return;
        }
        BuildPlan.Unit unit = Objects.requireNonNull(project.plan.units().get(task.unit));
        if (matching(level, unit)) { project.completed.set(task.unit); setDirty(); release(worker); reallocate = true; return; }
        String blocked = problem(level, unit, project.palette);
        if (!blocked.isEmpty()) { project.reason = blocked; release(worker); reallocate = true; return; }
        StorageResources.Claim storageTask = task.storage;
        if (storageTask != null) {
            StorageResources.Source source = StorageResources.resolve(level, Objects.requireNonNull(storageTask.access()), project.owner, unit.item());
            if (source == null || !source.identity().equals(task.source) || source.count() < ledger.reserved(source.identity()) || ledger.claim(worker.getUUID()) == null) {
                project.reason = "source_changed"; release(worker); reallocate = true; return;
            }
            if (!StorageResources.canTake(level, worker, source.pos())) { move(level, worker, Objects.requireNonNull(storageTask.pickupStand())); return; }
            worker.practice(dev.colony.camera.worker.WorkerAttribute.HAULING, time, 1);
            task.actionProgress += 1;
            if (task.actionProgress < dev.colony.camera.worker.WorkerEffects.haulTicks(worker.state())) return;
            task.actionProgress = 0;
            if (!usePathBudget()) return;
            Path delivery = GroundResources.path(level, worker, task.stand);
            var actor = FakePlayerFactory.get(level, new GameProfile(project.owner, project.ownerName));
            if (delivery == null || !level.mayInteract(Objects.requireNonNull(actor), source.pos())) { project.reason = "unreachable"; release(worker); reallocate = true; return; }
            if (!worker.getMainHandItem().isEmpty()) { dropCarried(worker); if (!worker.getMainHandItem().isEmpty()) return; }
            var claim = ledger.claim(worker.getUUID()); if (claim == null) return;
            ItemStack taken = source.take(claim.quantity());
            if (taken.isEmpty()) { release(worker); reallocate = true; return; }
            worker.carry(taken); ledger.release(worker.getUUID()); task.storage = null; task.source = null;
            worker.cargoTask(project.id());
            task.deadline = time + 240; GroundResources.follow(level, worker, delivery); project.reason = "transporting"; return;
        }
        UUID sourceId = task.source;
        if (sourceId != null) {
            var entity = level.getEntity(sourceId);
            if (!(entity instanceof ItemEntity source) || !GroundResources.eligible(source, worker, unit.item())
                    || source.getItem().getCount() < ledger.reserved(sourceId) || ledger.claim(worker.getUUID()) == null) {
                project.reason = "source_changed"; release(worker); reallocate = true; return;
            }
            if (!GroundResources.canPickUp(level, worker, source)) {
                move(level, worker, Objects.requireNonNull(source.blockPosition())); return;
            }
            worker.practice(dev.colony.camera.worker.WorkerAttribute.HAULING, time, 1);
            task.actionProgress += 1;
            if (task.actionProgress < dev.colony.camera.worker.WorkerEffects.haulTicks(worker.state())) return;
            task.actionProgress = 0;
            // Prove the second leg from the actual pickup position before taking any material.
            if (!usePathBudget()) return;
            Path delivery = GroundResources.path(level, worker, task.stand);
            if (delivery == null) { project.reason = "unreachable"; release(worker); reallocate = true; return; }
            if (!worker.getMainHandItem().isEmpty()) { dropCarried(worker); if (!worker.getMainHandItem().isEmpty()) return; }
            var actor = FakePlayerFactory.get(level, new GameProfile(project.owner, project.ownerName));
            if (ForgeEventFactory.onItemPickup(source, actor) < 0 || !GroundResources.eligible(source, worker, unit.item()) || source.getItem().getCount() < unit.count()) {
                project.reason = "denied"; release(worker); reallocate = true; return;
            }
            var claim = ledger.claim(worker.getUUID()); if (claim == null) return;
            ItemStack taken = source.getItem().copy(); taken.setCount(claim.quantity());
            ItemStack remaining = source.getItem().copy(); remaining.shrink(claim.quantity());
            if (remaining.isEmpty()) source.discard(); else source.setItem(remaining);
            worker.carry(taken);
            worker.cargoTask(project.id());
            ledger.release(worker.getUUID()); task.source = null; task.deadline = time + 240;
            GroundResources.follow(level, worker, delivery); project.reason = "transporting";
            return;
        }
        if (!GroundResources.matches(Objects.requireNonNull(worker.getMainHandItem()), unit.item()) || worker.cargoCount() < unit.count()) {
            release(worker); reallocate = true; return;
        }
        boolean blocksPlacement = unit.blocks().stream().anyMatch(block -> {
            var shape = Objects.requireNonNull(block).state().getCollisionShape(level, block.pos());
            return !shape.isEmpty() && shape.bounds().move(block.pos()).intersects(Objects.requireNonNull(worker.getBoundingBox()));
        });
        // Broad arrival tolerance cannot stop the worker with its body still overlapping the future block.
        if (worker.position().distanceToSqr(GroundResources.bottom(task.stand)) > 1.2 || blocksPlacement) { move(level, worker, task.stand); return; }
        if (!standable(level, task.stand) || worker.distanceToSqr(GroundResources.center(unit.anchor())) > 16) {
            project.reason = "unreachable"; release(worker); reallocate = true; return;
        }
        task.actionProgress += 1;
        worker.practice(dev.colony.camera.worker.WorkerAttribute.BUILDING, time, 1);
        if (task.actionProgress < dev.colony.camera.worker.WorkerEffects.buildTicks(worker.state(), time)) return;
        if (place(level, worker, project, unit)) {
            worker.consumeCargo(unit.count());
            if (worker.getMainHandItem().isEmpty()) {
                worker.setItemSlot(EquipmentSlot.MAINHAND, Objects.requireNonNull(ItemStack.EMPTY));
                worker.cargoTask(null);
            }
            worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            project.completed.set(task.unit); setDirty(); release(worker); reallocate = true;
            if (project.finished()) project.reason = "complete";
        } else { release(worker); reallocate = true; }
    }
    private void move(ServerLevel level, WorkerEntity worker, BlockPos target) {
        if (!worker.getNavigation().isDone() && target.equals(worker.getNavigation().getTargetPos())) return;
        if (!usePathBudget()) return;
        Path path = GroundResources.path(level, worker, target);
        if (path != null) GroundResources.follow(level, worker, path);
    }
    @Nullable private BlockPos findStorageStand(ServerLevel level, WorkerEntity worker, BlockPos source) {
        var candidates = new ArrayList<BlockPos>();
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            BlockPos pos = Objects.requireNonNull(source.offset(dx, dy, dz)); if (standable(level, pos)) candidates.add(pos);
        }
        candidates.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos)))));
        for (BlockPos pos : candidates) {
            if (worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos))) <= 1.2) return pos;
            if (!usePathBudget()) return null;
            if (GroundResources.path(level, worker, pos) != null) return pos;
        }
        return null;
    }
    @Nullable private BlockPos findStand(ServerLevel level, WorkerEntity worker, BuildPlan.Unit unit) {
        var candidates = new ArrayList<BlockPos>();
        BlockPos target = unit.anchor();
        for (int dy = -1; dy <= 1; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx == 0 && dz == 0 || Math.abs(dx) + Math.abs(dz) > 3) continue;
            BlockPos stand = Objects.requireNonNull(target.offset(dx, dy, dz));
            if (unit.blocks().stream().anyMatch(block -> block.pos().equals(stand)) || !standable(level, stand)) continue;
            candidates.add(stand);
        }
        candidates.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(pos)))));
        for (BlockPos stand : candidates) {
            if (worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(stand))) < 1.2) return stand;
            if (!usePathBudget()) return null;
            if (GroundResources.path(level, worker, Objects.requireNonNull(stand)) != null) return stand;
        }
        return null;
    }
    public static boolean standable(ServerLevel level, BlockPos pos) {
        return workStandable(level, pos) && level.getBlockState(pos).getFluidState().isEmpty();
    }
    /** Work may use shallow water with solid footing and an air-filled head space; spawning remains dry. */
    public static boolean workStandable(ServerLevel level, BlockPos pos) {
        return GroundResources.loaded(level, pos) && !level.isOutsideBuildHeight(pos) && !level.isOutsideBuildHeight(Objects.requireNonNull(pos.above()))
                && level.getWorldBorder().isWithinBounds(pos)
                && (level.getBlockState(pos).getFluidState().isEmpty() || level.getBlockState(pos).getFluidState().is(Objects.requireNonNull(FluidTags.WATER)))
                && level.getBlockState(Objects.requireNonNull(pos.above())).getFluidState().isEmpty()
                && level.getBlockState(Objects.requireNonNull(pos.below())).isFaceSturdy(level, Objects.requireNonNull(pos.below()), Direction.UP)
                && level.noCollision(GroundResources.body(pos));
    }
    private static boolean matching(ServerLevel level, BuildPlan.Unit unit) {
        return unit.blocks().stream().allMatch(block -> GroundResources.loaded(level, block.pos()) && level.getBlockState(block.pos()).equals(block.state()));
    }
    @Nonnull private static String problem(ServerLevel level, BuildPlan.Unit unit, boolean palette) {
        for (var block : unit.blocks()) {
            if (!GroundResources.loaded(level, block.pos())) return "unloaded";
            var current = level.getBlockState(block.pos());
            if (!(palette ? BlockBuilding.canReplace(Objects.requireNonNull(current)) : current.isAir()) && !current.equals(block.state())) return "conflict";
        }
        if (!unit.blocks().get(0).state().canSurvive(level, unit.anchor())) return "support";
        return "";
    }
    private static boolean place(ServerLevel level, WorkerEntity worker, ConstructionProject project, BuildPlan.Unit unit) {
        var online = level.getServer().getPlayerList().getPlayer(project.owner);
        if (online != null && !online.getAbilities().mayBuild) { project.reason = "denied"; return false; }
        var actor = FakePlayerFactory.get(level, new GameProfile(project.owner, project.ownerName));
        actor.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        actor.setPos(worker.getX(), worker.getY(), worker.getZ());
        for (var block : unit.blocks()) {
            if (!level.mayInteract(Objects.requireNonNull(online != null ? online : actor), block.pos())) { project.reason = "denied"; return false; }
            var shape = block.state().getCollisionShape(level, block.pos());
            if (!shape.isEmpty() && !level.getEntitiesOfClass(LivingEntity.class, Objects.requireNonNull(shape.bounds().move(block.pos())), (LivingEntity checkedElement) -> Objects.requireNonNull(checkedElement).isAlive()).isEmpty()) {
                project.reason = "occupied"; return false;
            }
        }
        var snapshots = new ArrayList<BlockSnapshot>();
        try {
            for (var block : unit.blocks()) {
                snapshots.add(BlockSnapshot.create(level.dimension(), level, block.pos()));
                if (!level.getBlockState(block.pos()).equals(block.state()) && !level.setBlock(block.pos(), block.state(), 2)) {
                    rollback(snapshots); project.reason = "conflict"; return false;
                }
            }
            boolean invalid = unit.blocks().stream().anyMatch(block -> !block.state().canSurvive(level, block.pos()));
            if (invalid || ForgeEventFactory.onMultiBlockPlace(actor, snapshots, Direction.UP)) {
                rollback(snapshots); project.reason = invalid ? "support" : "denied"; return false;
            }
            for (var block : unit.blocks()) level.updateNeighborsAt(block.pos(), Objects.requireNonNull(block.state().getBlock()));
            for (var block : unit.blocks()) if (level.getBlockEntity(block.pos()) instanceof MachineBlockEntity machine) machine.owner(project.owner);
            return true;
        } catch (RuntimeException failure) {
            rollback(snapshots); LOGGER.error("Construction placement failed for {}", project.id(), failure);
            project.reason = "denied"; return false;
        }
    }
    private static void rollback(List<BlockSnapshot> snapshots) {
        for (int i = snapshots.size() - 1; i >= 0; i--) snapshots.get(i).restore(true, false);
    }
    private void release(WorkerEntity worker) { ledger.release(worker.getUUID()); worker.assignment = null; worker.getNavigation().stop(); }
    private static void dropCarried(WorkerEntity worker) {
        worker.dropCargo();
    }
    private static int materialBatch(WorkerEntity worker, ConstructionProject project, BuildPlan.Unit current) {
        int capacity = Math.max(current.count(), dev.colony.camera.worker.WorkerEffects.capacity(worker.state()));
        int wanted = 0;
        for (int i = 0; i < project.plan.units().size() && wanted < capacity; i++) {
            BuildPlan.Unit unit = Objects.requireNonNull(project.plan.units().get(i));
            if (!project.completed.get(i) && unit.item() == current.item()) wanted = Math.min(capacity, wanted + unit.count());
        }
        return Math.max(current.count(), wanted);
    }
    private boolean usePathBudget() { if (pathBudget <= 0) return false; pathBudget--; return true; }
    public boolean readOnly() { return rejectedData != null; }
    @Nonnull public ReservationLedger reservations() { return ledger; }
    @Nullable public ConstructionProject project(UUID id) { return projects.get(id); }
    @Override @Nonnull public CompoundTag save(CompoundTag root) {
        CompoundTag rejected = rejectedData;
        if (rejected != null) return Objects.requireNonNull(rejected.copy());
        root.putInt("schemaVersion", 2); root.putLong("nextSequence", nextSequence);
        ListTag list = new ListTag();
        for (var project : projects.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("placement", Objects.requireNonNull(ProjectionCodec.encode(project.placement).toString()));
            entry.putUUID("owner", project.owner); entry.putString("ownerName", project.ownerName);
            entry.putInt("priority", project.priority); entry.putLong("sequence", project.sequence);
            entry.putBoolean("paused", project.paused); entry.putBoolean("cancelled", project.cancelled);
            entry.putBoolean("palette", project.palette);
            entry.putLongArray("completed", Objects.requireNonNull(project.completed.toLongArray())); list.add(entry);
        }
        root.put("projects", list); return root;
    }
    @Nonnull public static ConstructionManager load(CompoundTag root) {
        ConstructionManager manager = new ConstructionManager();
        try {
            if (root.getInt("schemaVersion") != 1 && root.getInt("schemaVersion") != 2) throw new IllegalArgumentException("Unsupported construction data version");
            if (!root.contains("projects", Tag.TAG_LIST) || !root.contains("nextSequence", Tag.TAG_LONG) || root.getLong("nextSequence") < 0) {
                throw new IllegalArgumentException("Invalid construction data header");
            }
            manager.nextSequence = root.getLong("nextSequence");
            if (!(root.get("projects") instanceof ListTag list) || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
                throw new IllegalArgumentException("Invalid construction project list");
            }
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.hasUUID("owner") || !entry.contains("completed", Tag.TAG_LONG_ARRAY) || entry.getString("ownerName").isBlank()) {
                    throw new IllegalArgumentException("Invalid construction project data");
                }
                ConstructionProject project = new ConstructionProject(ProjectionCodec.decode(Objects.requireNonNull(
                        JsonParser.parseString(entry.getString("placement")).getAsJsonObject())), Objects.requireNonNull(entry.getUUID("owner")),
                        Objects.requireNonNull(entry.getString("ownerName")), entry.getInt("priority"), entry.getLong("sequence"));
                project.paused = entry.getBoolean("paused"); project.cancelled = entry.getBoolean("cancelled");
                project.palette = root.getInt("schemaVersion") >= 2 && entry.getBoolean("palette");
                if (root.getInt("schemaVersion") >= 2 && entry.contains("palette") && !entry.contains("palette", Tag.TAG_BYTE)
                        || project.palette && project.placement.preset().blocks().size() > 2) throw new IllegalArgumentException("Invalid palette project data");
                project.completed.or(java.util.BitSet.valueOf(entry.getLongArray("completed")));
                if (project.completed.length() > project.plan.units().size() || manager.projects.put(project.id(), project) != null) {
                    throw new IllegalArgumentException("Invalid construction progress");
                }
                manager.nextSequence = Math.max(manager.nextSequence, Math.addExact(project.sequence, 1));
            }
        } catch (RuntimeException failure) {
            // Throwing here would let DimensionDataStorage create a writable empty replacement.
            manager.projects.clear(); manager.rejectedData = Objects.requireNonNull(root.copy());
            LOGGER.error("Construction data cannot be loaded; preserving it and disabling construction", failure);
        }
        return manager;
    }
}
