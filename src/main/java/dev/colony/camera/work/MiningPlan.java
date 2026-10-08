package dev.colony.camera.work;

import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Session geometry and stable worker preferences. Actual break progress belongs to ColonyJob. */
@ParametersAreNonnullByDefault
final class MiningPlan {
    @Nonnull final MiningFrontier frontier;
    @Nonnull private final ColonyJob job;
    @Nonnull private final List<MiningFrontier.Cell> cells;
    @Nonnull private List<BlockPos> stations = Objects.requireNonNull(List.of());
    @Nonnull private final Map<UUID, Integer> slots = new LinkedHashMap<>();
    @Nonnull private final Map<UUID, BlockPos> reservedStands = new HashMap<>();
    @Nonnull private final Map<UUID, Motion> motion = new HashMap<>();
    @Nonnull private final Map<UUID, Map<BlockPos, Long>> failedPaths = new HashMap<>();
    @Nonnull private final Map<Integer, Refusal> refusedTargets = new HashMap<>();
    @Nonnull private int[] owners;
    private long nextRefresh = Long.MIN_VALUE;
    private long repartitioned;
    private boolean stationsDirty = true;
    private boolean initialized;

    private record Refusal(long until, @Nonnull String reason) {}

    private static final class Motion {
        @Nonnull Vec3 position;
        int target;
        int progress;
        long changed;
        @Nullable BlockPos failedStand;
        long failedUntil;
        Motion(WorkerEntity worker, WorkTask task, long time) {
            position = Objects.requireNonNull(worker.position()); target = task.targetIndex; progress = task.progress; changed = time;
        }
    }
    MiningPlan(ColonyJob job) {
        this.job = job;
        cells = Objects.requireNonNull(job.targets.stream().map(pos -> cell(Objects.requireNonNull(pos))).toList());
        frontier = new MiningFrontier(cells); owners = new int[cells.size()]; Arrays.fill(owners, -1);
    }
    void prepare(ServerLevel level, long time) {
        if (initialized && time < nextRefresh) return;
        initialized = true; nextRefresh = time + 100;
        for (int i = 0; i < frontier.size(); i++) if (job.completed.get(i) && frontier.pending(i)) frontier.finish(i, pos -> open(level, Objects.requireNonNull(pos)));
        frontier.refresh(pos -> open(level, Objects.requireNonNull(pos))); stationsDirty = true;
    }
    void finished(ServerLevel level, int index) {
        frontier.finish(index, pos -> open(level, Objects.requireNonNull(pos))); stationsDirty = true; failedPaths.clear(); refusedTargets.remove(index);
    }
    void defer(int index, long time, String reason) { refusedTargets.put(index, new Refusal(time + 100, reason)); }
    @Nonnull String deferredReason(int index, long time) {
        Refusal refusal = refusedTargets.get(index);
        if (refusal != null && refusal.until() > time) return refusal.reason();
        refusedTargets.remove(index); return "";
    }
    void workers(ServerLevel level, List<WorkerEntity> workers, long time) {
        Set<UUID> roster = new LinkedHashSet<>(); for (WorkerEntity worker : workers) roster.add(Objects.requireNonNull(worker.getUUID()));
        boolean exhausted = false;
        if (time - repartitioned >= 100) for (int slot : slots.values()) {
            boolean pending = false;
            for (int i = 0; i < owners.length && !pending; i++) pending = owners[i] == slot && !job.completed.get(i);
            if (!pending) { exhausted = true; break; }
        }
        reservedStands.clear();
        motion.keySet().retainAll(roster);
        failedPaths.keySet().retainAll(roster);
        if (slots.keySet().equals(roster) && !exhausted) return;
        var ordered = new ArrayList<>(workers);
        boolean alongX = MiningPartition.alongX(cells);
        ordered.sort(Comparator.comparingDouble((WorkerEntity worker) -> alongX ? Objects.requireNonNull(worker).getX() : Objects.requireNonNull(worker).getZ())
                .thenComparing(worker -> Objects.requireNonNull(worker).getUUID()));
        slots.clear(); double[] speeds = new double[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            WorkerEntity worker = Objects.requireNonNull(ordered.get(i)); slots.put(Objects.requireNonNull(worker.getUUID()), i);
            double toolSpeed = worker.getOffhandItem().getItem() instanceof DiggerItem digger ? digger.getTier().getSpeed() : 1;
            speeds[i] = Math.max(1, toolSpeed) * dev.colony.camera.worker.WorkerEffects.mining(worker.state(), time)
                    * worker.needs().workSpeed(dev.colony.camera.worker.WorkerEffects.environmentResistance(worker.state()));
        }
        if (ordered.isEmpty()) { Arrays.fill(owners, -1); return; }
        double[] costs = new double[cells.size()];
        for (int i = 0; i < costs.length; i++) if (!job.completed.get(i)) {
            BlockPos pos = Objects.requireNonNull(job.targets.get(i));
            costs[i] = GroundResources.loaded(level, pos) ? MiningService.difficulty(level, pos, Objects.requireNonNull(level.getBlockState(pos))) : 1;
        }
        owners = MiningPartition.divide(cells, costs, speeds); repartitioned = time;
    }
    boolean preferred(WorkerEntity worker, int index) { Integer slot = slots.get(worker.getUUID()); return slot == null || owners[index] == slot; }
    void claim(WorkerEntity worker, BlockPos stand) { reservedStands.put(Objects.requireNonNull(worker.getUUID()), stand); }
    void unclaim(WorkerEntity worker) { reservedStands.remove(worker.getUUID()); }
    boolean available(WorkerEntity worker, BlockPos stand, long time) {
        for (var entry : reservedStands.entrySet()) if (!entry.getKey().equals(worker.getUUID()) && entry.getValue().equals(stand)) return false;
        Map<BlockPos, Long> failures = failedPaths.get(worker.getUUID());
        if (failures != null) {
            Long until = failures.get(stand);
            if (until != null && until > time) return false;
            failures.remove(stand);
        }
        Motion lease = motion.get(worker.getUUID());
        return lease == null || lease.failedUntil <= time || !stand.equals(lease.failedStand);
    }
    void unreachable(WorkerEntity worker, BlockPos stand, long time) {
        Map<BlockPos, Long> failures = Objects.requireNonNull(failedPaths.computeIfAbsent(Objects.requireNonNull(worker.getUUID()), ignored -> new HashMap<>()));
        failures.entrySet().removeIf(entry -> Objects.requireNonNull(entry.getValue()) <= time);
        failures.put(stand, time + 400);
    }
    boolean stalled(WorkerEntity worker, WorkTask task, long time) {
        Motion lease = motion.computeIfAbsent(Objects.requireNonNull(worker.getUUID()), ignored -> new Motion(worker, task, time));
        Vec3 position = Objects.requireNonNull(worker.position());
        if (lease.target != task.targetIndex || lease.progress != task.progress || lease.position.distanceToSqr(position) > 0.04) {
            lease.position = position; lease.target = task.targetIndex; lease.progress = task.progress; lease.changed = time;
        }
        if (time - lease.changed < 100) return false;
        lease.failedStand = task.targetStand; lease.failedUntil = time + 100; lease.changed = time;
        return true;
    }
    @Nonnull List<Integer> nearby(BlockPos stand) { return frontier.nearby(cell(stand), 5); }
    @Nonnull List<BlockPos> stations(ServerLevel level) {
        if (!stationsDirty) return stations;
        var candidates = new LinkedHashSet<BlockPos>();
        var visible = frontier.visible();
        for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1)) {
            BlockPos target = Objects.requireNonNull(job.targets.get(i)); int mask = frontier.mask(i);
            if ((mask & (1 << 2)) != 0) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) candidates.add(Objects.requireNonNull(target.offset(x, 1, z)));
            int[][] faces = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}; int[] bits = {0, 1, 4, 5};
            for (int face = 0; face < faces.length; face++) if ((mask & (1 << bits[face])) != 0) {
                int dx = faces[face][0], dz = faces[face][1];
                for (int step = 1; step <= 3; step++) for (int side = -1; side <= 1; side++) for (int dy = -3; dy <= 1; dy++) {
                    candidates.add(Objects.requireNonNull(target.offset(dx * step - dz * side, dy, dz * step + dx * side)));
                }
            }
            // Undersides and irregular openings still need actual standing space, not a directional assumption.
            if ((mask & (1 << 3)) != 0) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                candidates.add(Objects.requireNonNull(target.offset(dx, -2, dz)));
            }
        }
        stations = Objects.requireNonNull(candidates.stream().filter(pos -> ConstructionManager.workStandable(level, Objects.requireNonNull(pos))).toList());
        stationsDirty = false; return stations;
    }
    boolean water(ServerLevel level, BlockPos stand) { return !level.getBlockState(stand).getFluidState().isEmpty(); }
    @Nonnull static MiningFrontier.Cell cell(BlockPos pos) { return new MiningFrontier.Cell(pos.getX(), pos.getY(), pos.getZ()); }
    private static boolean open(ServerLevel level, MiningFrontier.Cell cell) {
        BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
        return GroundResources.loaded(level, pos) && (level.getBlockState(pos).isAir() || level.getBlockState(pos).getCollisionShape(level, pos).isEmpty());
    }
}
