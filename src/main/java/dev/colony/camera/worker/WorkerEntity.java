package dev.colony.camera.worker;

import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.work.WorkTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.network.NetworkHooks;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import dev.colony.camera.config.WorkerConfig;

/** The held item is the actual transport inventory and is saved by Mob's HandItems NBT. */
@ParametersAreNonnullByDefault
public final class WorkerEntity extends PathfinderMob {
    public static final int SEARCH_RANGE = 24;
    public static final int NAVIGATION_RANGE = 64;
    private static final double SWIM_SPEED = 5.0;
    @Nullable private UUID owner;
    @Nullable private UUID cargoTask;
    @Nullable public ConstructionManager.Assignment assignment;
    @Nullable public WorkTask workTask;
    @Nonnull private WorkerState state = new WorkerState();
    @Nullable private net.minecraft.nbt.Tag rejectedState;
    @Nullable private BlockPos home;
    @Nullable private WorkerDirective directive;
    private int cargoExtra;
    private boolean resting;
    @Nonnull private String lifeReason = "idle";
    @Nullable private UUID executionThreat;
    private long threatUntil;
    private long lastLifeTick;
    private long lastActivity;
    @Nonnull private final java.util.EnumMap<WorkerAttribute, Long> practiced = new java.util.EnumMap<>(WorkerAttribute.class);
    @Nonnull private final PathNavigation planningNavigation;

    public WorkerEntity(EntityType<? extends WorkerEntity> type, Level level) {
        super(type, level);
        // Feasibility queries must not mutate the route or stuck timer of the moving navigator.
        planningNavigation = new LoadedGroundNavigation(this, level);
        setPathfindingMalus(BlockPathTypes.WATER, 1.0F);
        setPersistenceRequired();
        setDropChance(EquipmentSlot.MAINHAND, 2.0F);
        setDropChance(EquipmentSlot.OFFHAND, 2.0F);
    }
    @Nonnull public static AttributeSupplier.Builder attributes() {
        return Objects.requireNonNull(Mob.createMobAttributes().add(Objects.requireNonNull(Attributes.MAX_HEALTH), 20)
                .add(Objects.requireNonNull(Attributes.MOVEMENT_SPEED), 0.3).add(Objects.requireNonNull(Attributes.FOLLOW_RANGE), NAVIGATION_RANGE)
                .add(Objects.requireNonNull(ForgeMod.SWIM_SPEED.get()), SWIM_SPEED)
                .add(Objects.requireNonNull(Attributes.ARMOR), 0).add(Objects.requireNonNull(Attributes.ATTACK_DAMAGE), 2));
    }
    @Override @Nonnull protected PathNavigation createNavigation(Level level) { return new LoadedGroundNavigation(this, level); }
    @Nonnull public PathNavigation planningNavigation() {
        planningNavigation.setCanFloat(getNavigation().canFloat());
        return planningNavigation;
    }
    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this) {
            @Override public boolean canUse() {
                // Keep feet on the bed in shallow water; surface only when the head is submerged.
                return super.canUse() && (!WorkerEntity.this.isInWater()
                        || WorkerEntity.this.isEyeInFluidType(Objects.requireNonNull(ForgeMod.WATER_TYPE.get())));
            }
        });
        goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 6));
        goalSelector.addGoal(1, new WorkerDoorGoal(this));
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }
    public void owner(UUID value) { owner = Objects.requireNonNull(value); }
    @Nullable public UUID owner() { return owner; }
    @Nullable public UUID cargoTask() { return getMainHandItem().isEmpty() ? null : cargoTask; }
    public void cargoTask(@Nullable UUID value) { cargoTask = value; }
    public int cargoCount() { return Math.addExact(getMainHandItem().getCount(), cargoExtra); }
    public void carry(net.minecraft.world.item.ItemStack stack) {
        if (stateReadOnly()) return;
        int count = stack.getCount(); var visible = Objects.requireNonNull(stack.copy()); visible.setCount(Math.min(count, stack.getMaxStackSize()));
        setItemSlot(EquipmentSlot.MAINHAND, visible); cargoExtra = count - visible.getCount();
    }
    public void consumeCargo(int quantity) {
        if (quantity < 0 || quantity > cargoCount()) throw new IllegalArgumentException("cargo");
        var remaining = Objects.requireNonNull(getMainHandItem().copy()); remaining.setCount(cargoCount() - quantity); carry(remaining);
        if (remaining.isEmpty()) cargoTask(null);
    }
    public void dropCargo() {
        if (stateReadOnly()) return;
        while (cargoCount() > 0) {
            var batch = Objects.requireNonNull(getMainHandItem().copy()); batch.setCount(Math.min(batch.getMaxStackSize(), cargoCount()));
            if (spawnAtLocation(batch) == null) return; consumeCargo(batch.getCount());
        }
    }
    @Nonnull public WorkerState state() { return state; }
    @Nonnull private WorkerPreferences preferences = new WorkerPreferences();
    @Nullable private net.minecraft.nbt.Tag rejectedPreferences;
    @Nonnull public WorkerPreferences preferences() { return preferences; }
    public boolean stateReadOnly() { return rejectedState != null || rejectedPreferences != null; }
    @Nullable public BlockPos home() { return home; }
    public void home(@Nullable BlockPos value) { if (!stateReadOnly()) home = value; }
    @Nullable public WorkerDirective directive() { return directive; }
    public void directive(@Nullable WorkerDirective value) {
        if (stateReadOnly()) return;
        directive = value; getNavigation().stop();
        boolean danger = value != null && value.kind == WorkerDirective.Kind.SACRIFICE;
        setPathfindingMalus(BlockPathTypes.DAMAGE_FIRE, danger ? 0 : 16); setPathfindingMalus(BlockPathTypes.DANGER_FIRE, danger ? 0 : 8);
    }
    public boolean available() {
        WorkerDirective currentDirective = directive;
        return !stateReadOnly() && !resting && !state.striking() && threatUntil <= level().getGameTime()
                && (currentDirective == null || currentDirective.kind == WorkerDirective.Kind.SACRIFICE);
    }
    public void threatened(UUID executor, long until) { if (!stateReadOnly()) { executionThreat = executor; threatUntil = until; } }
    @Nullable public UUID executionThreat(long time) { return threatUntil > time ? executionThreat : null; }
    public boolean resting() { return resting; }
    public void resting(boolean value) { resting = value; }
    @Nonnull public String lifeReason() { return lifeReason; }
    public void lifeReason(String value) { lifeReason = value; }
    public int miningLevel() { return state.points(WorkerAttribute.MINING).min(java.math.BigInteger.valueOf(Integer.MAX_VALUE)).intValue(); }
    public int searchRange() { return WorkerEffects.searchRange(state); }
    public int navigationRange() { return WorkerEffects.navigationRange(state); }
    public int minedBlocks() { return state.minedBlocks().min(java.math.BigInteger.valueOf(Integer.MAX_VALUE)).intValue(); }
    public void minedBlock() { if (!stateReadOnly()) state.successfulMining(); }
    public void practice(WorkerAttribute attribute, long time, long elapsed) {
        Long previous = practiced.get(attribute);
        if (stateReadOnly() || elapsed <= 0 || previous != null && previous >= time) return;
        long ticks = previous == null ? Math.min(20, elapsed) : Math.min(elapsed, Math.max(0, time - previous));
        state.practice(attribute, ticks, WorkerConfig.growth(state, attribute)); practiced.put(attribute, time);
        if (attribute == WorkerAttribute.MINING || attribute == WorkerAttribute.BUILDING || attribute == WorkerAttribute.HAULING
                || attribute == WorkerAttribute.CAPACITY || attribute == WorkerAttribute.MANUFACTURING) lastActivity = time;
        WorkerDirective currentDirective = directive;
        ConstructionManager.Assignment currentAssignment = assignment;
        WorkTask currentTask = workTask;
        if (ticks > 0 && currentDirective != null && currentDirective.kind == WorkerDirective.Kind.SACRIFICE
                && (currentAssignment != null && currentAssignment.project.equals(currentDirective.target)
                || currentTask != null && currentTask.job.equals(currentDirective.target))) currentDirective.progress = 1;
    }
    public boolean recentlyWorking(long time) { return lastActivity != 0 && time - lastActivity <= 20; }
    public void markWork(long time) { if (!stateReadOnly()) lastActivity = time; }
    public long lifeElapsed(long time) {
        long ticks = lastLifeTick == 0 ? 20 : Math.min(20, Math.max(0, time - lastLifeTick)); lastLifeTick = time; return ticks;
    }
    public void applyStateEffects() {
        if (stateReadOnly()) return;
        Objects.requireNonNull(getAttribute(Objects.requireNonNull(Attributes.MAX_HEALTH))).setBaseValue(WorkerEffects.health(state));
        Objects.requireNonNull(getAttribute(Objects.requireNonNull(Attributes.MOVEMENT_SPEED))).setBaseValue(WorkerEffects.movement(state));
        Objects.requireNonNull(getAttribute(Objects.requireNonNull(Attributes.ARMOR))).setBaseValue(WorkerEffects.defense(state));
        Objects.requireNonNull(getAttribute(Objects.requireNonNull(Attributes.ATTACK_DAMAGE))).setBaseValue(WorkerEffects.attack(state));
        Objects.requireNonNull(getAttribute(Objects.requireNonNull(Attributes.FOLLOW_RANGE))).setBaseValue(navigationRange());
        setMaxUpStep((float) WorkerEffects.mobility(state));
    }
    @Override public void jumpInFluid(@Nullable FluidType type) {
        FluidType liquid = Objects.requireNonNull(type);
        // Use an ordinary float impulse, or a bounded step impulse when the route climbs out of water.
        if (liquid == ForgeMod.WATER_TYPE.get()) {
            double impulse = !getNavigation().isDone() && getMoveControl().getWantedY() > getY() + 0.5 ? 0.3 : 0.04;
            setDeltaMovement(Objects.requireNonNull(getDeltaMovement().add(0, impulse, 0)));
        } else super.jumpInFluid(liquid);
    }
    @Override public double getFluidMotionScale(@Nullable FluidType type) {
        FluidType liquid = Objects.requireNonNull(type);
        // A working, grounded worker braces against the current after arriving. Walking still uses real fluid push.
        if (liquid == ForgeMod.WATER_TYPE.get() && onGround() && getNavigation().isDone()
                && (workTask != null || assignment != null)) return 0;
        return super.getFluidMotionScale(liquid);
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("ColonyPreferences", rejectedPreferences == null ? WorkerPreferenceStorage.save(preferences) : Objects.requireNonNull(rejectedPreferences.copy()));
        UUID id = owner;
        if (id != null) tag.putUUID("ColonyOwner", id);
        UUID cargo = cargoTask();
        if (cargo != null) tag.putUUID("ColonyCargoTask", cargo);
        if (rejectedState == null) {
            CompoundTag current = WorkerStateStorage.save(state);
            if (home != null) current.putLong("home", home.asLong());
            if (directive != null) current.put("directive", directive.save());
            current.putInt("cargoExtra", cargoExtra);
            tag.put("ColonyState", current);
        } else tag.put("ColonyState", Objects.requireNonNull(rejectedState.copy()));
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        preferences = new WorkerPreferences(); rejectedPreferences = null;
        if (tag.contains("ColonyPreferences")) {
            var rawPreferences = Objects.requireNonNull(tag.get("ColonyPreferences"));
            try {
                if (!(rawPreferences instanceof CompoundTag compound)) throw new IllegalArgumentException("data");
                preferences = WorkerPreferenceStorage.load(compound);
            } catch (RuntimeException invalid) { rejectedPreferences = Objects.requireNonNull(rawPreferences.copy()); }
        }
        cargoTask = tag.hasUUID("ColonyCargoTask") ? tag.getUUID("ColonyCargoTask") : null;
        // Saved vanilla attribute bases from older workers otherwise overwrite the new default.
        Objects.requireNonNull(getAttribute(Objects.requireNonNull(ForgeMod.SWIM_SPEED.get()))).setBaseValue(SWIM_SPEED);
        owner = tag.hasUUID("ColonyOwner") ? tag.getUUID("ColonyOwner") : null;
        assignment = null;
        workTask = null;
        home = null;
        directive = null; resting = false; cargoExtra = 0; threatUntil = 0; executionThreat = null;
        rejectedState = null;
        if (tag.contains("ColonyState")) {
            net.minecraft.nbt.Tag raw = Objects.requireNonNull(tag.get("ColonyState"));
            try {
                if (!(raw instanceof CompoundTag compound)) throw new IllegalArgumentException("worker_data");
                WorkerState restored = WorkerStateStorage.load(compound);
                if (compound.contains("home") && !compound.contains("home", net.minecraft.nbt.Tag.TAG_LONG)) throw new IllegalArgumentException("home");
                if (compound.contains("directive") && !compound.contains("directive", net.minecraft.nbt.Tag.TAG_COMPOUND)) throw new IllegalArgumentException("directive");
                WorkerDirective restoredDirective = compound.contains("directive") ? WorkerDirective.load(Objects.requireNonNull(compound.getCompound("directive"))) : null;
                if (restoredDirective != null && owner == null) throw new IllegalArgumentException("directive_owner");
                if (restoredDirective != null) restoredDirective.validate(restored);
                if (compound.contains("cargoExtra") && !compound.contains("cargoExtra", net.minecraft.nbt.Tag.TAG_INT)) throw new IllegalArgumentException("cargo");
                int extraCargo = compound.getInt("cargoExtra");
                if (extraCargo < 0 || extraCargo > Integer.MAX_VALUE - getMainHandItem().getCount() || extraCargo > 0 && getMainHandItem().isEmpty()) throw new IllegalArgumentException("cargo");
                home = compound.contains("home") ? BlockPos.of(compound.getLong("home")) : null;
                state = restored;
                directive(restoredDirective);
                cargoExtra = extraCargo;
            } catch (RuntimeException invalid) { rejectedState = Objects.requireNonNull(raw.copy()); }
        } else state = WorkerState.migrate(tag.getInt("ColonyMiningXp"));
        practiced.clear(); lastLifeTick = lastActivity = 0;
        if (!level().isClientSide) applyStateEffects();
    }
    @Override public void tick() {
        double beforeX = getX(), beforeY = getY(), beforeZ = getZ(); super.tick();
        if (level() instanceof net.minecraft.server.level.ServerLevel server && isAlive() && !stateReadOnly()) {
            long time = server.getGameTime();
            if ((getX() - beforeX) * (getX() - beforeX) + (getZ() - beforeZ) * (getZ() - beforeZ) > 0.0001) {
                practice(WorkerAttribute.MOVEMENT, time, 1);
                if (!getNavigation().isDone()) practice(WorkerAttribute.PERCEPTION, time, 1);
                if (available() && cargoCount() > 0 && (workTask != null || assignment != null)) {
                    practice(WorkerAttribute.HAULING, time, 1); practice(WorkerAttribute.CAPACITY, time, 1);
                }
            }
            if (Math.abs(getY() - beforeY) > 0.001 && (onClimbable() || onGround())) {
                practice(WorkerAttribute.MOBILITY, time, 1);
                if (onClimbable() && getDeltaMovement().y > 0) setDeltaMovement(getDeltaMovement().x, Math.min(0.4, getDeltaMovement().y * WorkerEffects.climbing(state)), getDeltaMovement().z);
            }
            if (recentlyWorking(time)) practice(WorkerAttribute.ENERGY, time, 1);
            WorkerLiving.tick(server, this, time);
        }
    }
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean environment = source.is(Objects.requireNonNull(net.minecraft.tags.DamageTypeTags.IS_FIRE))
                || source.is(Objects.requireNonNull(net.minecraft.tags.DamageTypeTags.IS_DROWNING))
                || source.is(Objects.requireNonNull(net.minecraft.tags.DamageTypeTags.IS_FALL))
                || source.is(Objects.requireNonNull(net.minecraft.tags.DamageTypeTags.IS_FREEZING))
                || source.is(Objects.requireNonNull(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION));
        boolean injured = super.hurt(source, environment && !stateReadOnly() ? (float) (amount / WorkerEffects.environmentResistance(state)) : amount);
        if (injured && !level().isClientSide && !stateReadOnly()) practice(WorkerAttribute.DEFENSE, level().getGameTime(), 1);
        if (injured && environment && !level().isClientSide && !stateReadOnly()) practice(WorkerAttribute.ENVIRONMENT_RESISTANCE, level().getGameTime(), 1);
        return injured;
    }
    @Override public void die(net.minecraft.world.damagesource.DamageSource source) {
        if (level() instanceof net.minecraft.server.level.ServerLevel server) {
            WorkerOrders.sacrificeEnded(server, this, false); Residences.release(server, this);
            dropCargo();
        }
        super.die(source);
    }
    @Override public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (level() instanceof net.minecraft.server.level.ServerLevel server && (reason.shouldDestroy() || reason == net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION)) {
            Residences.release(server, this); if (reason == net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION) home(null);
        }
        super.remove(reason);
    }
    @Override @Nonnull public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return Objects.requireNonNull(NetworkHooks.getEntitySpawningPacket(this));
    }
}
