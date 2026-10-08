package dev.colony.camera.worker;

import dev.colony.camera.config.WorkerConfig;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.resources.StorageResources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import static dev.colony.camera.worker.WorkerProfile.StressCause;

/** Physiology owns navigation while resting/eating/studying; the scheduler only assigns available workers. */
@ParametersAreNonnullByDefault
public final class WorkerLiving {
    private WorkerLiving() {}
    public static void tick(ServerLevel level, WorkerEntity worker, long time) {
        WorkerState state = worker.state();
        if (WorkerOrders.react(level, worker, time)) { suspend(worker, level); return; }
        if (time % 20 == 0) {
            long ticks = worker.lifeElapsed(time); boolean working = worker.recentlyWorking(time);
            var home = Residences.home(level, worker);
            if (home == null && time % 100 == 0) { Residences.assign(level, worker); home = Residences.home(level, worker); }
            double foodUse = WorkerConfig.HUNGER_PER_TICK.get() / (1 + Math.log1p(state.points(WorkerAttribute.HUNGER).min(java.math.BigInteger.valueOf(1000000)).doubleValue() - 1) * WorkerConfig.coefficient(WorkerAttribute.HUNGER));
            state.metabolism(ticks, working, foodUse, WorkerConfig.FATIGUE_PER_TICK.get() / WorkerEffects.energy(state));
            double rate = WorkerConfig.STRESS_PER_DAY.get() * ticks / 24000.0;
            if (home == null) state.addPressure(StressCause.HOMELESS, rate * 0.5);
            if (state.hunger() < 6) state.addPressure(StressCause.HUNGER, rate);
            if (state.foodQuality() < 1) state.addPressure(StressCause.POOR_FOOD, rate * 0.25);
            if (state.rest() < 0.4) state.addPressure(StressCause.FATIGUE, rate);
            if (working && state.continuousWork() > 12000) state.addPressure(StressCause.OVERWORK, rate);
            if (worker.isInWater() || worker.isOnFire()) state.addPressure(StressCause.BAD_ENVIRONMENT, rate * 0.5);
            if (working && unsuited(worker)) state.addPressure(StressCause.UNSUITED_WORK, rate * 0.25);
            state.mood(WorkerEffects.tolerance(state), WorkerConfig.LOYALTY_CRISIS.get() * ticks / 20.0);
            boolean needsRest = state.striking() || state.rest() < 0.4 || state.stress(WorkerEffects.tolerance(state)) >= 70 || state.continuousWork() >= 12000;
            if (needsRest) worker.resting(true);
            if (worker.resting() && !state.striking() && state.rest() >= 0.95 && state.stress(WorkerEffects.tolerance(state)) <= 35 && state.continuousWork() == 0)
                worker.resting(false);
            // Improvements are observed only after providing a home/food/rest, never from the passage of idle time.
            double quality = (home != null ? 3 + Math.min(1, home.floor().size() / 32.0) : 0) + state.foodQuality() + (state.rest() >= 0.9 ? 1 : 0);
            state.lifeImproved(quality, WorkerConfig.LOYALTY_IMPROVEMENT.get());
            worker.applyStateEffects();
        }
        if (state.hunger() < Math.min(10, WorkerEffects.hunger(state) / 2) && time % 20 == 0 && eatOrSeek(level, worker, time)) {
            suspend(worker, level); return;
        }
        if (worker.resting() || state.striking() || state.hunger() <= 1) {
            suspend(worker, level); worker.lifeReason(state.striking() ? "strike" : state.hunger() <= 1 ? "hungry" : "resting");
            if (time % 20 == 0) rest(level, worker, time);
            return;
        }
        if (worker.isSleeping()) worker.stopSleeping();
            WorkerDirective directive = worker.directive();
        if (directive != null && directive.kind != WorkerDirective.Kind.SACRIFICE) {
            suspend(worker, level);
            if (directive.kind == WorkerDirective.Kind.EXECUTION) WorkerOrders.execute(level, worker, directive, time);
            else study(level, worker, directive, time);
        } else {
            worker.lifeReason(worker.workTask != null || worker.assignment != null ? "working" : "idle");
            if (directive != null) WorkerOrders.checkSacrifice(level, worker);
        }
    }
    private static boolean unsuited(WorkerEntity worker) {
        var task = worker.workTask; var attribute = worker.assignment != null ? WorkerAttribute.BUILDING : WorkerAttribute.HAULING;
        if (task != null) {
            var job = dev.colony.camera.work.ColonyWorkManager.get(Objects.requireNonNull((ServerLevel) worker.level())).all().stream().filter(entry -> entry.id.equals(task.job)).findFirst().orElse(null);
            if (job != null) attribute = switch (job.kind) { case MINE -> WorkerAttribute.MINING; case SWEEP -> WorkerAttribute.HAULING; case CRAFT, RESEARCH -> WorkerAttribute.MANUFACTURING; };
        }
        return worker.state().interest() != WorkerProfile.Interest.NONE && !worker.state().professionFocus(attribute)
                && WorkerProfile.birthBonus(worker.state().interest(), attribute) == 0;
    }
    private static void suspend(WorkerEntity worker, ServerLevel level) {
        worker.assignment = null; worker.workTask = null; ConstructionManager.get(level).ledger().release(Objects.requireNonNull(worker.getUUID()));
    }
    private static void rest(ServerLevel level, WorkerEntity worker, long time) {
        var room = Residences.home(level, worker); boolean atHome = false;
        if (room != null) {
            if (worker.distanceToSqr(GroundResources.center(room.bed())) < 2.25 && StorageResources.canTake(level, worker, room.bed())) {
                worker.getNavigation().stop(); if (!worker.isSleeping()) worker.startSleeping(room.bed()); atHome = true;
            } else {
                BlockPos stand = approach(level, worker, room.bed());
                if (stand != null) follow(level, worker, stand);
            }
        }
        worker.state().rest(20, atHome, WorkerEffects.energy(worker.state()));
        worker.state().recover(WorkerEffects.recovery(worker.state()) * 20 * (atHome ? 1 : 0.5));
        worker.practice(WorkerAttribute.RESILIENCE, time, 20);
        worker.practice(WorkerAttribute.ENERGY, time, 20);
        if (atHome && worker.getHealth() < worker.getMaxHealth()) { worker.heal(0.2F); worker.practice(WorkerAttribute.HEALTH, time, 20); }
    }
    private static boolean eatOrSeek(ServerLevel level, WorkerEntity worker, long time) {
        UUID owner = worker.owner(); if (owner == null) return false;
        ReservationLedger ledger = ConstructionManager.get(level).ledger();
        for (var entry : GroundResources.scan(level, worker).entrySet()) if (entry.getKey().isEdible()) for (ItemEntity source : entry.getValue()) {
            if (source.getItem().getCount() <= ledger.reserved(source.getUUID())) continue;
            if (!GroundResources.canPickUp(level, worker, Objects.requireNonNull(source))) {
                Path path = GroundResources.path(level, worker, Objects.requireNonNull(source.blockPosition()));
                if (path != null) { GroundResources.follow(level, worker, path); worker.lifeReason("seeking_food"); worker.resting(true); return true; }
                continue;
            }
            var actor = Objects.requireNonNull(net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(owner, "ColonyWorker")));
            if (!level.mayInteract(actor, Objects.requireNonNull(source.blockPosition())) || net.minecraftforge.event.ForgeEventFactory.onItemPickup(source, actor) < 0) continue;
            ItemStack meal = Objects.requireNonNull(source.getItem().copy()); meal.setCount(1);
            source.getItem().shrink(1); if (source.getItem().isEmpty()) source.discard(); consume(level, worker, meal, time); return true;
        }
        for (BlockPos pos : StorageResources.scan(level, worker)) for (var item : net.minecraftforge.registries.ForgeRegistries.ITEMS) {
            if (!item.isEdible()) continue;
            StorageResources.Source source = StorageResources.resolve(level, Objects.requireNonNull(pos), owner, Objects.requireNonNull(item));
            if (source == null || source.count() <= ledger.reserved(source.identity())) continue;
            var actor = Objects.requireNonNull(net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(owner, "ColonyWorker")));
            if (!level.mayInteract(actor, pos)) continue;
            if (!StorageResources.canTake(level, worker, pos)) {
                BlockPos stand = approach(level, worker, pos);
                if (stand != null) { follow(level, worker, stand); worker.lifeReason("seeking_food"); worker.resting(true); return true; }
                continue;
            }
            ItemStack meal = Objects.requireNonNull(source.take(1)); if (meal.isEmpty()) continue;
            consume(level, worker, meal, time); return true;
        }
        return false;
    }
    private static void consume(ServerLevel level, WorkerEntity worker, ItemStack meal, long time) {
        var food = meal.getFoodProperties(worker); if (food == null) return;
        worker.state().eat(food.getNutrition(), WorkerEffects.hunger(worker.state()), Math.max(0.5, food.getSaturationModifier() * 2));
        worker.practice(WorkerAttribute.HUNGER, time, 20); worker.lifeReason("eating");
        ItemStack remainder = meal.getCraftingRemainingItem();
        if (!remainder.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, worker.getX(), worker.getY(), worker.getZ(), remainder);
    }
    private static void study(ServerLevel level, WorkerEntity worker, WorkerDirective directive, long time) {
        BlockPos station = directive.station;
        UUID owner = worker.owner();
        if (station == null || !GroundResources.loaded(level, station) || !(level.getBlockEntity(station) instanceof MachineBlockEntity school)
                || owner == null || school.kind() != ColonyContent.Kind.ACADEMY || school.facilityReadOnly() || !school.permits(owner)
                || school.schoolLevel() < directive.tier || Residences.home(level, worker) == null) { worker.lifeReason("school_blocked"); return; }
        if (!StorageResources.canTake(level, worker, station)) {
            if (time % 20 == 0) { BlockPos stand = approach(level, worker, station); if (stand != null) follow(level, worker, stand); }
            worker.lifeReason("going_school"); return;
        }
        worker.getNavigation().stop(); worker.lifeReason("studying");
        WorkerAttribute attribute = directive.kind == WorkerDirective.Kind.STUDY_ATTRIBUTE ? WorkerProfile.parse(WorkerAttribute.class, directive.course)
                : WorkerProfile.focus(WorkerProfile.parse(WorkerProfile.Profession.class, directive.course));
        boolean mismatch = worker.state().points(attribute).compareTo(java.math.BigInteger.valueOf(directive.additional ? 3 : 2)) < 0
                || directive.additional && worker.state().points(WorkerAttribute.RESILIENCE).compareTo(java.math.BigInteger.valueOf(3)) < 0
                || directive.kind == WorkerDirective.Kind.STUDY_PROFESSION && !worker.state().suited(WorkerProfile.parse(WorkerProfile.Profession.class, directive.course));
        double speed = (1 + school.schoolLevel()) * directive.tier * WorkerEffects.learning(worker.state()) * WorkerEffects.operation(worker.state())
                * teaching(level, worker, station, time) * (mismatch ? 0.5 : 1);
        worker.state().study(attribute, 1, speed, WorkerConfig.growth(worker.state(), attribute)); directive.progress += speed;
        if (attribute != WorkerAttribute.LEARNING) worker.practice(WorkerAttribute.LEARNING, time, 1);
        if (attribute != WorkerAttribute.OPERATION) worker.practice(WorkerAttribute.OPERATION, time, 1);
        worker.markWork(time);
        if (time % 20 == 0) {
            double rate = WorkerConfig.STRESS_PER_DAY.get() / 1200.0;
            if (mismatch) worker.state().addPressure(StressCause.FORCED_STUDY, rate * 2);
            if (directive.additional) worker.state().addPressure(StressCause.DOUBLE_DEGREE, rate * 8);
        }
        double duration = directive.kind == WorkerDirective.Kind.STUDY_ATTRIBUTE ? WorkerState.TICKS_PER_POINT : WorkerConfig.PROFESSION_TICKS.get();
        if (directive.progress >= duration) {
            if (directive.kind == WorkerDirective.Kind.STUDY_PROFESSION) {
                if (directive.additional) worker.state().practice(attribute, WorkerState.TICKS_PER_POINT, 1);
                worker.state().profession(WorkerProfile.parse(WorkerProfile.Profession.class, directive.course), directive.additional);
            }
            worker.directive(null); worker.lifeReason("study_complete");
        }
    }
    private static double teaching(ServerLevel level, WorkerEntity learner, BlockPos station, long time) {
        double bonus = 1;
        for (WorkerEntity teacher : ConstructionManager.get(level).workers(level)) {
            WorkerDirective course = teacher.directive();
            if (teacher == learner || teacher.stateReadOnly() || teacher.state().striking() || teacher.resting() || course == null
                    || !station.equals(course.station) || !Objects.equals(teacher.owner(), learner.owner())
                    || !teacher.lifeReason().equals("studying") || !teacher.state().hasAbility(WorkerProfile.Ability.TEACHING)
                    || !teacher.hasLineOfSight(learner) || teacher.distanceToSqr(learner) > 16) continue;
            bonus = Math.max(bonus, 1 + 0.1 * WorkerEffects.learning(teacher.state()));
            teacher.practice(WorkerAttribute.LEARNING, time, 1);
        }
        return bonus;
    }
    @Nullable public static BlockPos approach(ServerLevel level, WorkerEntity worker, BlockPos target) {
        if (StorageResources.canTake(level, worker, target)) return Objects.requireNonNull(worker.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) for (int dy : new int[]{0, 1, -1}) {
            BlockPos stand = Objects.requireNonNull(target.relative(Objects.requireNonNull(direction)).offset(0, dy, 0));
            if (!ConstructionManager.workStandable(level, stand) || !StorageResources.canTakeFrom(level, worker, GroundResources.bottom(stand),
                    Objects.requireNonNull(GroundResources.bottom(stand).add(0, worker.getEyeHeight(), 0)), target)) continue;
            if (GroundResources.path(level, worker, stand) != null) return stand;
        }
        return null;
    }
    public static void follow(ServerLevel level, WorkerEntity worker, BlockPos stand) {
        if (!worker.getNavigation().isDone() && stand.equals(worker.getNavigation().getTargetPos())) return;
        Path path = GroundResources.path(level, worker, stand); if (path != null) GroundResources.follow(level, worker, path);
    }
}
