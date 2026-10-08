package dev.colony.camera.work;

import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Selects a safe work position and a local batch; global priority and FIFO stay in ConstructionManager. */
@ParametersAreNonnullByDefault
final class MiningScheduler {
    private record Station(@Nonnull BlockPos position, @Nonnull List<Integer> coverage, double value) {}
    private MiningScheduler() {}
    @Nonnull static MiningPlan plan(ColonyJob job) {
        MiningPlan plan = job.miningPlan;
        if (plan == null) job.miningPlan = plan = new MiningPlan(job);
        return plan;
    }
    static void dispatch(ServerLevel level, ColonyJob job, List<WorkerEntity> workers, Set<UUID> assigned,
            Set<BlockPos> claimed, ReservationLedger ledger, long time) {
        List<WorkerEntity> available = Objects.requireNonNull(workers.stream()
                .filter(worker -> Objects.requireNonNull(worker).available() && worker.preferences().allows(dev.colony.camera.worker.WorkerPreferences.Task.MINE)
                        && !assigned.contains(worker.getUUID()) && job.owner.equals(worker.owner())).toList());
        if (available.isEmpty()) return;
        MiningPlan plan = plan(job); plan.prepare(level, time); plan.workers(level, available, time);
        Map<UUID, BlockPos> active = new HashMap<>();
        var ordered = new ArrayList<>(available);
        ordered.sort(Comparator.comparingInt((WorkerEntity worker) -> {
            WorkTask task = Objects.requireNonNull(worker).workTask;
            return task != null && task.job.equals(job.id) ? -task.progress - 1 : 0;
        }).thenComparing(worker -> Objects.requireNonNull(worker).getUUID()));
        for (WorkerEntity worker : ordered) {
            WorkTask previous = worker.workTask;
            if (previous == null || !previous.job.equals(job.id) || previous.targetIndex < 0 || previous.targetIndex >= job.targets.size()
                    || job.completed.get(previous.targetIndex)) continue;
            job.miningProgress().remember(previous.targetIndex, previous.progress);
            BlockPos target = Objects.requireNonNull(job.targets.get(previous.targetIndex));
            if (claimed.add(target)) {
                active.put(Objects.requireNonNull(worker.getUUID()), target);
                BlockPos stand = previous.targetStand; if (stand != null) plan.claim(worker, stand);
            }
        }
        String running = "";
        for (WorkerEntity worker : ordered) {
            BlockPos owned = active.remove(worker.getUUID()); if (owned != null) claimed.remove(owned);
            plan.unclaim(worker);
            WorkTask next = assign(level, worker, job, claimed, ledger, time);
            if (next == null) { if (owned != null) plan.unclaim(worker); continue; }
            WorkTask previous = worker.workTask;
            if (previous == null || !previous.job.equals(next.job) || !previous.stand.equals(next.stand)) worker.getNavigation().stop();
            worker.assignment = null; worker.workTask = next;
            assigned.add(Objects.requireNonNull(worker.getUUID())); claimed.add(Objects.requireNonNull(job.targets.get(next.targetIndex)));
            plan.claim(worker, Objects.requireNonNull(next.targetStand)); running = job.reason;
        }
        if (!running.isEmpty()) job.reason = running;
    }
    @Nullable static WorkTask assign(ServerLevel level, WorkerEntity worker, ColonyJob job, Set<BlockPos> claimed,
            ReservationLedger ledger, long time) {
        if (!job.runnable()) return null;
        if (!job.owner.equals(worker.owner())) { job.reason = "denied"; return null; }
        if (!GroundResources.searchAreaLoaded(level, worker)) { job.reason = "unloaded"; return null; }
        MiningPlan plan = plan(job); plan.prepare(level, time);
        // Retire only changed/empty targets. Membership in a geometric frontier never grants permission to break.
        String blocked = ""; boolean eligible = false;
        for (int i = 0; i < job.targets.size(); i++) if (!job.completed.get(i)) {
            BlockPos pos = Objects.requireNonNull(job.targets.get(i));
            if (GroundResources.loaded(level, pos) && (!MiningService.matching(level, job, i) || level.getBlockState(pos).isAir())) {
                MiningService.complete(level, job, i, "changed");
            } else if (!claimed.contains(pos)) {
                String problem = GroundResources.loaded(level, pos)
                        ? MiningService.blockProblem(level, worker, job, pos, Objects.requireNonNull(level.getBlockState(pos))) : "unloaded";
                if (problem.isEmpty()) problem = plan.deferredReason(i, time);
                if (problem.isEmpty()) eligible = true;
                else if (blocked.isEmpty()) blocked = problem;
            }
        }
        if (job.finished()) return null;
        if (!eligible) { job.reason = blocked.isEmpty() ? "queued" : blocked; return null; }
        job.reason = "unreachable";
        WorkTask previous = worker.workTask;
        BlockPos current = Objects.requireNonNull(worker.blockPosition());
        // Tool readiness precedes retained progress and sector preference. Waiting work stays in the contract.
        for (boolean ready : new boolean[]{true, false}) {
            if (previous != null && previous.job.equals(job.id) && previous.targetIndex >= 0 && previous.targetIndex < job.targets.size()
                    && !job.completed.get(previous.targetIndex) && !claimed.contains(job.targets.get(previous.targetIndex))
                    && plan.deferredReason(previous.targetIndex, time).isEmpty()
                    && MiningService.ready(level, worker, Objects.requireNonNull(level.getBlockState(Objects.requireNonNull(job.targets.get(previous.targetIndex))))) == ready) {
                int index = previous.targetIndex;
                job.miningProgress().remember(index, previous.progress);
                BlockPos stand = previous.targetStand;
                boolean stalled = plan.stalled(worker, previous, time);
                if (!stalled && stand != null && plan.available(worker, stand, time) && validTarget(level, worker, job, index, stand, time)) {
                    WorkTask next = MiningService.task(level, worker, job, index, stand, previous, ledger, time);
                    if (next != null) return next;
                }
                WorkTask relocated = chooseStation(level, worker, job, claimed, ledger, time, index, false, ready);
                if (relocated != null) return relocated;
            }
            if (ConstructionManager.workStandable(level, current) && plan.available(worker, current, time)) {
                for (boolean preferred : new boolean[]{true, false}) {
                    List<Integer> targets = coverage(level, worker, job, current, claimed, preferred, ready, time);
                    for (int index : targets) {
                        WorkTask next = MiningService.task(level, worker, job, index, current, null, ledger, time);
                        if (next != null) return next;
                    }
                }
            }
            WorkTask next = chooseStation(level, worker, job, claimed, ledger, time, -1, true, ready);
            if (next == null) next = chooseStation(level, worker, job, claimed, ledger, time, -1, false, ready);
            if (next != null) return next;
        }
        return null;
    }
    @Nullable static WorkTask continueHere(ServerLevel level, WorkerEntity worker, ColonyJob job, WorkTask previous,
            List<WorkerEntity> workers, ReservationLedger ledger, long time) {
        if (!job.runnable() || previous.toolPickup || !worker.preferences().allows(dev.colony.camera.worker.WorkerPreferences.Task.MINE)) return null;
        var claimed = new HashSet<BlockPos>();
        for (WorkerEntity other : workers) {
            WorkTask task = Objects.requireNonNull(other).workTask;
            if (other != worker && task != null && task.job.equals(job.id) && task.targetIndex >= 0 && task.targetIndex < job.targets.size()) {
                claimed.add(Objects.requireNonNull(job.targets.get(task.targetIndex)));
            }
        }
        BlockPos stand = previous.targetStand;
        if (stand == null || !ConstructionManager.workStandable(level, stand)) return null;
        for (boolean preferred : new boolean[]{true, false}) for (int index : coverage(level, worker, job, stand, claimed, preferred, true, time)) {
            BlockPos target = Objects.requireNonNull(job.targets.get(index));
            BlockState state = Objects.requireNonNull(level.getBlockState(target));
            if (!MiningService.canReach(level, worker, Objects.requireNonNull(worker.getEyePosition()), target)
                    || state.requiresCorrectToolForDrops() && !MiningService.usableTool(level, worker, state, Objects.requireNonNull(worker.getOffhandItem()))) continue;
            WorkTask next = new WorkTask(job.id, stand, time + 1200);
            next.targetIndex = index; next.targetStand = stand; next.progress = job.miningProgress().get(index); next.lastWorked = time;
            job.reason = "mining"; return next;
        }
        return null;
    }
    @Nullable private static WorkTask chooseStation(ServerLevel level, WorkerEntity worker, ColonyJob job, Set<BlockPos> claimed,
            ReservationLedger ledger, long time, int retainedIndex, boolean preferred, boolean ready) {
        MiningPlan plan = plan(job); var ranked = new ArrayList<Station>();
        for (BlockPos position : plan.stations(level)) {
            BlockPos stand = Objects.requireNonNull(position);
            if (!plan.available(worker, stand, time)) continue;
            List<Integer> targets = coverage(level, worker, job, stand, claimed, preferred, ready, time);
            if (targets.isEmpty() || retainedIndex >= 0 && !targets.contains(retainedIndex)) continue;
            double travel = Math.sqrt(worker.distanceToSqr(GroundResources.bottom(stand))) / 0.2;
            double work = 0;
            for (int index : targets) work += MiningService.estimatedTicks(level, worker, job, index);
            // Water keeps the same real standing/reach rules, without a top-layer peeling preference.
            double value = targets.size() / Math.max(1, travel + work);
            ranked.add(new Station(stand, targets, value));
        }
        ranked.sort(Comparator.comparingDouble((Station site) -> Objects.requireNonNull(site).value()).reversed()
                .thenComparingDouble(site -> worker.distanceToSqr(GroundResources.bottom(Objects.requireNonNull(site).position())))
                .thenComparingInt(site -> Objects.requireNonNull(site).position().getX()).thenComparingInt(site -> Objects.requireNonNull(site).position().getZ()));
        int attempts = 0;
        for (Station site : ranked) {
            if (++attempts > 16) break;
            BlockPos stand = site.position();
            if (worker.distanceToSqr(GroundResources.bottom(stand)) > 1.2 && GroundResources.path(level, worker, stand) == null) {
                plan.unreachable(worker, stand, time); continue;
            }
            for (int index : site.coverage()) {
                if (retainedIndex >= 0 && retainedIndex != index) continue;
                WorkTask task = MiningService.task(level, worker, job, index, stand, worker.workTask, ledger, time);
                if (task != null) return task;
            }
        }
        return null;
    }
    @Nonnull private static List<Integer> coverage(ServerLevel level, WorkerEntity worker, ColonyJob job, BlockPos stand,
            Set<BlockPos> claimed, boolean preferred, boolean ready, long time) {
        MiningPlan plan = plan(job); var result = new ArrayList<Integer>();
        for (int index : plan.nearby(stand)) {
            if (job.completed.get(index) || claimed.contains(job.targets.get(index)) || preferred && !plan.preferred(worker, index)) continue;
            if (validTarget(level, worker, job, index, stand, time)
                    && MiningService.ready(level, worker, Objects.requireNonNull(level.getBlockState(Objects.requireNonNull(job.targets.get(index))))) == ready) result.add(index);
        }
        boolean water = plan.water(level, stand);
        result.sort(Comparator.<Integer>comparingInt(index -> job.miningProgress().get(index) > 0 ? 0 : 1)
                .thenComparing(Comparator.<Integer>comparingInt(index -> plan.frontier.exposure(index)).reversed())
                .thenComparingInt(index -> water ? 0 : -job.targets.get(index).getY())
                .thenComparingDouble(index -> MiningService.estimatedTicks(level, worker, job, index)).thenComparingInt(index -> Objects.requireNonNull(index).intValue()));
        return result;
    }
    private static boolean validTarget(ServerLevel level, WorkerEntity worker, ColonyJob job, int index, BlockPos stand, long time) {
        BlockPos target = Objects.requireNonNull(job.targets.get(index));
        if (job.completed.get(index) || !plan(job).deferredReason(index, time).isEmpty()
                || !GroundResources.loaded(level, target) || !ConstructionManager.workStandable(level, stand)
                || target.equals(stand.below()) || !MiningService.matching(level, job, index)) return false;
        BlockState state = Objects.requireNonNull(level.getBlockState(target));
        return !state.isAir() && MiningService.blockProblem(level, worker, job, target, state).isEmpty()
                && !MiningService.supportsOtherWorker(level, worker, target)
                && MiningService.canReach(level, worker, MiningService.standEyes(worker, stand), target);
    }
}
