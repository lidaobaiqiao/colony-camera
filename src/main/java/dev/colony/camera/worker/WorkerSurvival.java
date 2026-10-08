package dev.colony.camera.worker;

import dev.colony.camera.config.WorkerConfig;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.tech.TechProgress;
import dev.colony.camera.work.WorkStations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Reuses physical navigation and permissions; absent supplies never hold an NPC indefinitely. */
@ParametersAreNonnullByDefault
public final class WorkerSurvival {
    private static final class Visit {
        @Nullable BlockPos target;
        long expires, nextWaterScan, gatheringUntil, nextGathering, lastSocialTime = Long.MIN_VALUE;
    }
    @Nonnull private static final Map<WorkerEntity, Visit> VISITS = new WeakHashMap<>();
    private WorkerSurvival() {}
    public static void metabolism(ServerLevel level, WorkerEntity worker, long time, long ticks, boolean working) {
        WorkerNeeds needs = worker.needs(); needs.elapsed(ticks, working, WorkerConfig.WATER_PER_TICK.get(), WorkerConfig.SOCIAL_PER_TICK.get());
        if (time % 100 == 0) needs.temperature(LocalClimate.sample(level, worker));
        double days = ticks / 24000.0;
        if (needs.hydration() < 20) worker.state().addPressure(WorkerProfile.StressCause.THIRST, days * WorkerConfig.THIRST_STRESS.get());
        if (needs.social() < 20) worker.state().addPressure(WorkerProfile.StressCause.LONELINESS, days * WorkerConfig.LONELY_STRESS.get());
        double discomfort = needs.thermalDiscomfort(WorkerEffects.environmentResistance(worker.state()));
        if (discomfort > 0) {
            worker.state().addPressure(needs.temperature() < 20 ? WorkerProfile.StressCause.COLD : WorkerProfile.StressCause.HEAT,
                    days * discomfort * WorkerConfig.THERMAL_STRESS.get());
            worker.practice(WorkerAttribute.ENVIRONMENT_RESISTANCE, time, ticks);
        }
        double living = (needs.drinks() > 0 && needs.hydration() >= 40 ? needs.waterQuality() : 0)
                + (discomfort == 0 && needs.drinks() > 0 ? 0.5 : 0) + (!needs.relations().isEmpty() && needs.social() >= 60 ? 1 : 0);
        // The existing quality ledger rewards only a new high, never repeated need refills.
        var room = Residences.home(level, worker);
        double base = (room != null ? 3 + Math.min(1, room.floor().size() / 32.0) : 0) + worker.state().foodQuality() + (worker.state().rest() >= 0.9 ? 1 : 0);
        worker.state().lifeImproved(base + living, WorkerConfig.LOYALTY_IMPROVEMENT.get());
    }
    public static boolean tick(ServerLevel level, WorkerEntity worker, long time) {
        if (time % 20 != 0) return worker.lifeBusy();
        worker.lifeBusy(false);
        if (!GroundResources.searchAreaLoaded(level, worker)) return false;
        if (worker.directive() != null || worker.state().striking() || worker.state().hunger() <= 1) return false;
        Visit visit = Objects.requireNonNull(VISITS.computeIfAbsent(worker, ignored -> new Visit()));
        if (worker.needs().hydration() < 65 && seekWater(level, worker, visit, time)) { worker.lifeBusy(true); return true; }
        if (worker.cargoCount() > 0 || worker.state().rest() < 0.5 || worker.state().hunger() < 6) return false;
        if (gather(level, worker, visit, time)) { worker.lifeBusy(true); return true; }
        return false;
    }
    private static boolean seekWater(ServerLevel level, WorkerEntity worker, Visit visit, long time) {
        BlockPos target = visit.target;
        if (target != null && time < visit.expires && drinkable(level, worker, target)) return approachWater(level, worker, visit, target);
        if (target != null && time >= visit.expires) { visit.target = null; visit.nextWaterScan = time + 200; return false; }
        visit.target = null;
        if (time < visit.nextWaterScan) return false;
        visit.nextWaterScan = time + 100;
        var owner = worker.owner(); if (owner == null) return false;
        var candidates = new ArrayList<BlockPos>();
        for (BlockPos pos : WorkStations.owned(level, owner)) if (pos.distSqr(Objects.requireNonNull(worker.blockPosition())) <= worker.navigationRange() * worker.navigationRange()
                && level.getBlockEntity(Objects.requireNonNull(pos)) instanceof MachineBlockEntity machine && machine.kind() == ColonyContent.Kind.WATER_PUMP
                && facilityStatus(level, machine).equals("ready")) candidates.add(pos);
        BlockPos origin = Objects.requireNonNull(worker.blockPosition());
        for (BlockPos pos : BlockPos.betweenClosed(Objects.requireNonNull(origin.offset(-8, -3, -8)), Objects.requireNonNull(origin.offset(8, 3, 8))))
            if (source(level, Objects.requireNonNull(pos))) candidates.add(Objects.requireNonNull(pos.immutable()));
        candidates.sort(Comparator.comparingDouble(pos -> worker.distanceToSqr(GroundResources.center(Objects.requireNonNull(pos)))));
        int paths = 0;
        for (BlockPos candidate : candidates) {
            if (++paths > 12) break;
            if (!drinkable(level, worker, Objects.requireNonNull(candidate))) continue;
            BlockPos stand = WorkerLiving.approach(level, worker, candidate); if (stand == null) continue;
            visit.target = candidate; visit.expires = time + 400;
            return approachWater(level, worker, visit, candidate);
        }
        worker.lifeReason("no_water"); return false;
    }
    private static boolean approachWater(ServerLevel level, WorkerEntity worker, Visit visit, BlockPos target) {
        if (StorageResources.canTake(level, worker, target)) {
            worker.getNavigation().stop();
            boolean centralized = level.getBlockEntity(target) instanceof MachineBlockEntity machine && machine.kind() == ColonyContent.Kind.WATER_PUMP;
            worker.needs().drink(centralized ? 2 : 1); worker.lifeReason("drinking"); visit.target = null;
            worker.state().recover(1); return true;
        }
        BlockPos stand = WorkerLiving.approach(level, worker, target);
        if (stand == null) { visit.target = null; return false; }
        WorkerLiving.follow(level, worker, stand); worker.lifeReason("seeking_water"); return true;
    }
    private static boolean drinkable(ServerLevel level, WorkerEntity worker, BlockPos pos) {
        if (!GroundResources.loaded(level, pos)) return false;
        var owner = worker.owner(); if (owner == null) return false;
        var actor = Objects.requireNonNull(net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(owner, "ColonyWorker")));
        if (!level.mayInteract(actor, pos)) return false;
        return source(level, pos) || level.getBlockEntity(pos) instanceof MachineBlockEntity machine
                && machine.permits(owner) && machine.kind() == ColonyContent.Kind.WATER_PUMP && facilityStatus(level, machine).equals("ready");
    }
    public static boolean source(ServerLevel level, BlockPos pos) {
        if (!GroundResources.loaded(level, pos)) return false;
        var state = level.getBlockState(pos); var fluid = state.getFluidState();
        return fluid.is(Objects.requireNonNull(FluidTags.WATER)) && fluid.isSource() && state.getCollisionShape(level, pos).isEmpty();
    }
    @Nonnull public static String facilityStatus(ServerLevel level, MachineBlockEntity machine) {
        var owner = machine.owner(); if (owner == null || machine.facilityReadOnly()) return "denied";
        int node = machine.kind() == ColonyContent.Kind.WATER_PUMP ? TechProgress.WATERWORKS : TechProgress.COMMUNITY;
        if (TechProgress.get(level).readOnly() || !TechProgress.get(level).unlocked(owner, node)) return "technology";
        if (machine.kind() == ColonyContent.Kind.WATER_PUMP) {
            for (Direction direction : Direction.values()) if (source(level, Objects.requireNonNull(machine.getBlockPos().relative(Objects.requireNonNull(direction))))) return "ready";
            return "no_source";
        }
        return "ready";
    }
    private static boolean gather(ServerLevel level, WorkerEntity worker, Visit visit, long time) {
        var owner = worker.owner(); if (owner == null || !TechProgress.get(level).unlocked(owner, TechProgress.COMMUNITY)) return false;
        boolean visiting = visit.gatheringUntil > time;
        if (!visiting && (worker.needs().social() >= 50 || time < visit.nextGathering)) return false;
        if (!visiting) { visit.nextGathering = time + 600; visit.gatheringUntil = time + 240; }
        for (BlockPos pos : WorkStations.owned(level, owner)) {
            if (!(level.getBlockEntity(Objects.requireNonNull(pos)) instanceof MachineBlockEntity machine) || machine.kind() != ColonyContent.Kind.MEETING_POINT
                    || !facilityStatus(level, machine).equals("ready") || worker.distanceToSqr(GroundResources.center(pos)) > worker.navigationRange() * worker.navigationRange()) continue;
            var partners = ConstructionManager.get(level).workers(level).stream().filter(person -> person != worker && person.isAlive() && !person.stateReadOnly()
                    && owner.equals(person.owner()) && person.directive() == null && person.cargoCount() == 0 && !person.state().striking()
                    && !person.isSleeping() && person.needs().social() < 95
                    && worker.needs().canConverse(Objects.requireNonNull(person.getUUID())) && person.needs().canConverse(Objects.requireNonNull(worker.getUUID()))
                    && person.distanceToSqr(GroundResources.center(pos)) < person.navigationRange() * person.navigationRange()).toList();
            if (partners.isEmpty()) continue;
            if (!StorageResources.canTake(level, worker, pos)) {
                BlockPos stand = WorkerLiving.approach(level, worker, pos); if (stand == null) continue;
                WorkerLiving.follow(level, worker, stand); worker.lifeReason("seeking_company"); return true;
            }
            boolean met = false; double support = 0;
            for (WorkerEntity person : partners) if (person.distanceToSqr(worker) <= 16 && person.hasLineOfSight(worker)
                    && person.distanceToSqr(GroundResources.center(pos)) < 36
                    && (person.lifeBusy() || person.resting() || person.assignment == null && person.workTask == null)) {
                Visit other = Objects.requireNonNull(VISITS.computeIfAbsent(person, ignored -> new Visit()));
                if (visit.lastSocialTime < time && other.lastSocialTime < time) {
                    worker.needs().converse(Objects.requireNonNull(person.getUUID()), 20);
                    person.needs().converse(Objects.requireNonNull(worker.getUUID()), 20);
                    visit.lastSocialTime = other.lastSocialTime = time;
                }
                var relation = worker.needs().relations().get(person.getUUID()); support = relation == null ? 0 : relation.affinity() / 100.0;
                met = true; break;
            }
            if (met) { worker.getNavigation().stop(); worker.lifeReason("socializing"); worker.state().recover(0.2 * (1 + support)); return true; }
        }
        return false;
    }
}
