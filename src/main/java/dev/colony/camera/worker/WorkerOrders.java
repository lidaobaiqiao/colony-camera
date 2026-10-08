package dev.colony.camera.worker;

import com.google.gson.JsonObject;
import dev.colony.camera.config.WorkerConfig;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.work.ColonyWorkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

/** Special orders are issued exclusively by a validated player request, never by the ordinary scheduler. */
@ParametersAreNonnullByDefault
public final class WorkerOrders {
    private WorkerOrders() {}
    public static void request(ServerPlayer player, JsonObject data) {
        ServerLevel level = Objects.requireNonNull(player.serverLevel());
        UUID id = Objects.requireNonNull(UUID.fromString(data.get("id").getAsString()));
        if (player.isSpectator() || !player.getAbilities().mayBuild || !(level.getEntity(id) instanceof WorkerEntity worker)
                || !worker.isAlive() || !player.getUUID().equals(worker.owner()) || worker.distanceToSqr(player) > 128 * 128
                || !level.mayInteract(player, Objects.requireNonNull(worker.blockPosition()))) throw new IllegalArgumentException("denied");
        if (worker.stateReadOnly()) throw new IllegalArgumentException("data");
        String operation = Objects.requireNonNull(data.get("operation").getAsString());
        switch (operation) {
            case "task" -> worker.preferences().allows(Objects.requireNonNull(WorkerPreferences.Task.valueOf(Objects.requireNonNull(data.get("task").getAsString()))), data.get("enabled").getAsBoolean());
            case "automatic_tools" -> worker.preferences().automaticTools(data.get("enabled").getAsBoolean());
            case "equipment" -> WorkerEquipment.exchange(player, worker, data);
            case "home" -> { if (!Residences.assign(level, worker)) throw new IllegalArgumentException("home"); }
            case "stop" -> worker.directive(null);
            case "profession", "study" -> {
                BlockPos pos = Objects.requireNonNull(BlockPos.of(data.get("station").getAsLong()));
                if (Residences.home(level, worker) == null) throw new IllegalArgumentException("home");
                if (!GroundResources.loaded(level, pos) || !level.mayInteract(player, pos) || player.distanceToSqr(GroundResources.center(pos)) > 128 * 128
                        || !(level.getBlockEntity(pos) instanceof MachineBlockEntity school) || school.kind() != ColonyContent.Kind.ACADEMY
                        || !school.permits(Objects.requireNonNull(player.getUUID())) || school.facilityReadOnly()) throw new IllegalArgumentException("school");
                String course = Objects.requireNonNull(data.get("course").getAsString());
                boolean additional = data.has("additional") && data.get("additional").getAsBoolean();
                int tier = operation.equals("profession") ? additional ? 3 : WorkerProfile.schoolLevel(WorkerProfile.parse(WorkerProfile.Profession.class, course))
                        : data.has("tier") ? data.get("tier").getAsInt() : 1;
                if (tier < 1 || tier > 3 || school.schoolLevel() < tier || WorkerLiving.approach(level, worker, pos) == null) throw new IllegalArgumentException("school");
                if (operation.equals("profession")) {
                    WorkerProfile.Profession profession = WorkerProfile.parse(WorkerProfile.Profession.class, course);
                    if (profession == WorkerProfile.Profession.NONE || profession == worker.state().primary() || profession == worker.state().secondary()
                            || additional && (worker.state().primary() == WorkerProfile.Profession.NONE || worker.state().secondary() != WorkerProfile.Profession.NONE)) throw new IllegalArgumentException("course");
                }
                worker.directive(new WorkerDirective(operation.equals("profession") ? WorkerDirective.Kind.STUDY_PROFESSION : WorkerDirective.Kind.STUDY_ATTRIBUTE,
                        pos, null, course, additional, tier));
            }
            case "execute", "sacrifice" -> {
                UUID targetId = Objects.requireNonNull(UUID.fromString(data.get("target").getAsString()));
                if (operation.equals("execute")) {
                    if (!(level.getEntity(targetId) instanceof LivingEntity target) || !target.isAlive() || target.distanceToSqr(player) > 128 * 128
                            || worker.distanceToSqr(target) > worker.navigationRange() * worker.navigationRange()
                            || !level.mayInteract(player, Objects.requireNonNull(target.blockPosition()))) throw new IllegalArgumentException("location");
                } else if (!runnableTarget(level, worker, targetId)) throw new IllegalArgumentException("invalid");
                double required = operation.equals("execute") ? WorkerConfig.ORDER_LOYALTY.get() : WorkerConfig.SACRIFICE_LOYALTY.get();
                if (worker.state().loyalty() < required) { worker.state().addPressure(WorkerProfile.StressCause.REFUSED_ORDER, WorkerConfig.REFUSAL_STRESS.get()); throw new IllegalArgumentException("loyalty"); }
                worker.directive(new WorkerDirective(operation.equals("execute") ? WorkerDirective.Kind.EXECUTION : WorkerDirective.Kind.SACRIFICE, null, targetId, "", false, 1));
            }
            default -> throw new IllegalArgumentException("invalid");
        }
        ColonyWorkManager.get(level).redispatch();
    }
    public static void execute(ServerLevel level, WorkerEntity worker, WorkerDirective directive, long time) {
        UUID targetId = directive.target;
        if (targetId == null || !(level.getEntity(targetId) instanceof LivingEntity target) || !target.isAlive()) { worker.directive(null); return; }
        var actor = Objects.requireNonNull(net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(Objects.requireNonNull(worker.owner()), "ColonyWorker")));
        if (!GroundResources.loaded(level, Objects.requireNonNull(target.blockPosition())) || !level.mayInteract(actor, Objects.requireNonNull(target.blockPosition()))
                || target instanceof ServerPlayer && !level.getServer().isPvpAllowed()) { worker.lifeReason("order_blocked"); return; }
        if (target instanceof WorkerEntity subject) subject.threatened(Objects.requireNonNull(worker.getUUID()), time + 40);
        worker.lifeReason("executing");
        if (worker.distanceToSqr(target) > 4 || !worker.hasLineOfSight(target)) {
            if (time % 20 == 0) {
                BlockPos stand = WorkerLiving.approach(level, worker, Objects.requireNonNull(target.blockPosition()));
                if (stand != null) WorkerLiving.follow(level, worker, stand);
            }
            return;
        }
        worker.getNavigation().stop(); if (time % 20 != 0) return;
        Set<UUID> witnesses = witnesses(level, worker, target);
        if (!target.hurt(Objects.requireNonNull(worker.damageSources().playerAttack(actor)), (float) WorkerEffects.attack(worker.state()))) { worker.lifeReason("order_blocked"); return; }
        worker.practice(WorkerAttribute.ATTACK, time, 20); worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (!target.isAlive()) { executionSeen(level, worker, target, witnesses); worker.directive(null); }
    }
    @Nonnull private static Set<UUID> witnesses(ServerLevel level, WorkerEntity worker, LivingEntity target) {
        Set<UUID> visible = new HashSet<>();
        for (WorkerEntity bystander : ConstructionManager.get(level).workers(level)) if (bystander != worker && bystander != target
                && bystander.distanceToSqr(target) <= Math.pow(WorkerEffects.witnessRange(bystander.state(), target), 2)
                && bystander.hasLineOfSight(target)) visible.add(Objects.requireNonNull(bystander.getUUID()));
        return visible;
    }
    public static void executionSeen(ServerLevel level, WorkerEntity executor, LivingEntity target, Set<UUID> visible) {
        Set<UUID> affected = new HashSet<>(visible);
        for (UUID id : visible) if (level.getEntity(Objects.requireNonNull(id)) instanceof WorkerEntity witness) {
            var relationship = witness.needs().relations().get(target.getUUID());
            double bond = relationship == null ? 0 : relationship.affinity() / 100.0;
            shock(witness, WorkerProfile.StressCause.WITNESSED_EXECUTION, WorkerConfig.EXECUTION_STRESS.get() * (1 + bond * 0.5));
            witness.practice(WorkerAttribute.PERCEPTION, level.getGameTime(), 20);
            if (witness.state().loyalty() >= WorkerConfig.ORDER_LOYALTY.get()) continue;
            for (WorkerEntity hearer : ConstructionManager.get(level).workers(level)) if (hearer != executor && hearer != target
                    && Objects.equals(witness.owner(), hearer.owner()) && hearer.distanceToSqr(witness) <= 16 * 16 && hearer.hasLineOfSight(witness)
                    && affected.add(Objects.requireNonNull(hearer.getUUID()))) shock(hearer, WorkerProfile.StressCause.HEARD_EXECUTION, WorkerConfig.EXECUTION_STRESS.get() * 0.25);
        }
    }
    private static void shock(WorkerEntity worker, WorkerProfile.StressCause cause, double amount) {
        if (worker.stateReadOnly()) return;
        // The bounded event shock cannot itself wipe accrued loyalty by crossing the crisis threshold.
        double remaining = Math.max(0, 99 * WorkerEffects.tolerance(worker.state()) - worker.state().rawPressure());
        worker.state().addPressure(cause, Math.min(remaining, amount));
    }
    private static boolean runnableTarget(ServerLevel level, WorkerEntity worker, UUID target) {
        var project = ConstructionManager.get(level).project(target);
        if (project != null) return project.runnable() && project.owner.equals(worker.owner());
        return ColonyWorkManager.get(level).all().stream().anyMatch(job -> job.id.equals(target) && job.owner.equals(worker.owner()) && job.runnable());
    }
    public static void checkSacrifice(ServerLevel level, WorkerEntity worker) {
        WorkerDirective directive = worker.directive(); if (directive == null || directive.kind != WorkerDirective.Kind.SACRIFICE) return;
        UUID target = directive.target;
        if (target == null) return;
        if (ConstructionManager.get(level).readOnly() || ColonyWorkManager.get(level).readOnly()) { worker.lifeReason("order_blocked"); return; }
        var project = ConstructionManager.get(level).project(target);
        var job = ColonyWorkManager.get(level).all().stream().filter(entry -> entry.id.equals(target)).findFirst().orElse(null);
        var assignment = worker.assignment;
        var task = worker.workTask;
        if (worker.recentlyWorking(level.getGameTime()) && (assignment != null && assignment.project.equals(target)
                || task != null && task.job.equals(target))) directive.progress = 1;
        if (project != null && project.finished() || job != null && job.finished()) { if (directive.progress > 0) sacrificeEnded(level, worker, true); worker.directive(null); }
        else if (project == null && job == null || project != null && project.cancelled || job != null && job.cancelled) worker.directive(null);
    }
    public static void sacrificeEnded(ServerLevel level, WorkerEntity worker, boolean success) {
        WorkerDirective directive = worker.directive(); if (directive == null || directive.kind != WorkerDirective.Kind.SACRIFICE) return;
        for (WorkerEntity witness : ConstructionManager.get(level).workers(level)) if (!witness.stateReadOnly() && witness.state().loyalty() >= WorkerConfig.ORDER_LOYALTY.get()
                && witness.distanceToSqr(worker) <= Math.pow(WorkerEffects.witnessRange(witness.state(), worker), 2) && witness.hasLineOfSight(worker))
            witness.state().morale(level.getGameTime() + WorkerConfig.MORALE_TICKS.get(), success);
    }
    public static boolean react(ServerLevel level, WorkerEntity subject, long time) {
        UUID threat = subject.executionThreat(time);
        if (threat == null || !(level.getEntity(threat) instanceof WorkerEntity executor) || executor.directive() == null
                || !subject.getUUID().equals(Objects.requireNonNull(executor.directive()).target)) return false;
        if (subject.state().loyalty() >= WorkerConfig.ORDER_LOYALTY.get()) {
            subject.getNavigation().stop(); subject.lifeReason("submitted"); return true;
        }
        subject.lifeReason("escaping");
        if (time % 20 == 0) {
            var direction = subject.position().subtract(Objects.requireNonNull(executor.position())).normalize();
            for (int distance : new int[]{6, 4, 2}) {
                BlockPos stand = Objects.requireNonNull(BlockPos.containing(subject.getX() + (direction.x == 0 ? 1 : direction.x) * distance,
                        subject.getY(), subject.getZ() + direction.z * distance));
                if (ConstructionManager.workStandable(level, stand) && GroundResources.path(level, subject, stand) != null) { WorkerLiving.follow(level, subject, stand); break; }
            }
        }
        return true;
    }
}
