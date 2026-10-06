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
    @Nullable private UUID owner;
    @Nullable public ConstructionManager.Assignment assignment;
    @Nullable public WorkTask workTask;
    private int miningExperience;

    public WorkerEntity(EntityType<? extends WorkerEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setDropChance(EquipmentSlot.MAINHAND, 2.0F);
        setDropChance(EquipmentSlot.OFFHAND, 2.0F);
    }
    @Nonnull public static AttributeSupplier.Builder attributes() {
        return Objects.requireNonNull(Mob.createMobAttributes().add(Objects.requireNonNull(Attributes.MAX_HEALTH), 20)
                .add(Objects.requireNonNull(Attributes.MOVEMENT_SPEED), 0.3).add(Objects.requireNonNull(Attributes.FOLLOW_RANGE), NAVIGATION_RANGE));
    }
    @Override @Nonnull protected PathNavigation createNavigation(Level level) { return new LoadedGroundNavigation(this, level); }
    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 6));
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }
    public void owner(UUID value) { owner = Objects.requireNonNull(value); }
    @Nullable public UUID owner() { return owner; }
    public int miningLevel() { return MiningSkill.level(miningExperience); }
    public int miningXp() { return miningExperience; }
    public void gainMiningExperience(int amount) { miningExperience = MiningSkill.gain(miningExperience, amount); }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        UUID id = owner;
        if (id != null) tag.putUUID("ColonyOwner", id);
        tag.putInt("ColonyMiningXp", miningExperience);
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        owner = tag.hasUUID("ColonyOwner") ? tag.getUUID("ColonyOwner") : null;
        assignment = null;
        workTask = null;
        miningExperience = MiningSkill.gain(tag.getInt("ColonyMiningXp"), 0);
    }
    @Override @Nonnull public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return Objects.requireNonNull(NetworkHooks.getEntitySpawningPacket(this));
    }
}
