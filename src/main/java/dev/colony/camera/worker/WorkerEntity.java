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
    private int miningExperience;
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
                .add(Objects.requireNonNull(ForgeMod.SWIM_SPEED.get()), SWIM_SPEED));
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
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }
    public void owner(UUID value) { owner = Objects.requireNonNull(value); }
    @Nullable public UUID owner() { return owner; }
    @Nullable public UUID cargoTask() { return getMainHandItem().isEmpty() ? null : cargoTask; }
    public void cargoTask(@Nullable UUID value) { cargoTask = value; }
    public int miningLevel() { return MiningSkill.level(miningExperience); }
    public int miningXp() { return miningExperience; }
    public void gainMiningExperience(int amount) { miningExperience = MiningSkill.gain(miningExperience, amount); }
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
        UUID id = owner;
        if (id != null) tag.putUUID("ColonyOwner", id);
        UUID cargo = cargoTask();
        if (cargo != null) tag.putUUID("ColonyCargoTask", cargo);
        tag.putInt("ColonyMiningXp", miningExperience);
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        cargoTask = tag.hasUUID("ColonyCargoTask") ? tag.getUUID("ColonyCargoTask") : null;
        // Saved vanilla attribute bases from older workers otherwise overwrite the new default.
        Objects.requireNonNull(getAttribute(Objects.requireNonNull(ForgeMod.SWIM_SPEED.get()))).setBaseValue(SWIM_SPEED);
        owner = tag.hasUUID("ColonyOwner") ? tag.getUUID("ColonyOwner") : null;
        assignment = null;
        workTask = null;
        miningExperience = MiningSkill.gain(tag.getInt("ColonyMiningXp"), 0);
    }
    @Override @Nonnull public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return Objects.requireNonNull(NetworkHooks.getEntitySpawningPacket(this));
    }
}
