package dev.colony.camera.content;

import dev.colony.camera.storage.WarehouseInventory;
import dev.colony.camera.storage.StorageMenu;
import dev.colony.camera.storage.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.Nonnull;
import java.util.UUID;

public final class MachineBlockEntity extends BlockEntity implements MenuProvider {
    @Nullable private UUID owner;
    @Nullable public Component customName;
    public final WarehouseInventory inventory = new WarehouseInventory(this::inventoryChanged);
    private LazyOptional<net.minecraftforge.items.IItemHandler> capability = LazyOptional.of(this::handler);
    public long nextPrint;
    @Nullable private UUID resident;
    private int schoolLevel = 1;
    private boolean automaticHousing = true;
    public boolean automaticHousing() { return automaticHousing; }
    public void automaticHousing(boolean value) { if (!facilityReadOnly()) { automaticHousing = value; setChanged(); } }
    @Nullable private net.minecraft.nbt.Tag rejectedFacility;
    public boolean facilityReadOnly() { return rejectedFacility != null; }
    @Nullable public UUID resident() { return resident; }
    public void resident(@Nullable UUID id) { if (!facilityReadOnly()) { resident = id; setChanged(); } }
    public int schoolLevel() { return schoolLevel; }
    public void schoolLevel(int value) { if (!facilityReadOnly() && value >= 1 && value <= 3) { schoolLevel = value; setChanged(); } }
    // A failed chest conversion restores its snapshot instead of dropping the temporary inventory.
    boolean converting;
    public MachineBlockEntity(BlockPos pos, BlockState state) { super(Objects.requireNonNull(ColonyContent.MACHINE.get()), pos, state); }
    public ColonyContent.Kind kind() { return ((MachineBlock) getBlockState().getBlock()).kind; }
    @Nullable public UUID owner() { return owner; }
    public void owner(UUID id) { owner = id; setChanged(); if (level instanceof ServerLevel server) { StorageNetwork.invalidate(server); if (kind() == ColonyContent.Kind.PRINTING_CORE) PrintingCores.add(server, Objects.requireNonNull(worldPosition)); dev.colony.camera.work.WorkStations.add(server, Objects.requireNonNull(worldPosition)); } }
    public boolean permits(UUID id) { return owner != null && owner.equals(id); }
    public boolean claim(ServerPlayer player) {
        if (player.isSpectator() || !player.getAbilities().mayBuild || !player.serverLevel().mayInteract(player, Objects.requireNonNull(worldPosition))) return false;
        if (owner == null) owner(player.getUUID());
        return permits(player.getUUID());
    }
    private void inventoryChanged() { setChanged(); if (level instanceof ServerLevel server) StorageNetwork.invalidate(server); }
    @Override protected void saveAdditional(@Nonnull CompoundTag tag) {
        super.saveAdditional(tag); tag.putInt("schemaVersion", 1);
        if (owner != null) tag.putUUID("Owner", owner);
        if (customName != null) tag.putString("CustomName", Objects.requireNonNull(Component.Serializer.toJson(customName)));
        tag.putLong("NextPrint", nextPrint); if (kind() == ColonyContent.Kind.WAREHOUSE) tag.put("Inventory", Objects.requireNonNull(inventory.save()));
        if (rejectedFacility != null) tag.put("Living", Objects.requireNonNull(rejectedFacility.copy()));
        else if (kind() == ColonyContent.Kind.RESIDENCE || kind() == ColonyContent.Kind.ACADEMY) {
            CompoundTag living = new CompoundTag(); living.putInt("schemaVersion", 2); living.putInt("schoolLevel", schoolLevel); living.putBoolean("automaticHousing", automaticHousing);
            if (resident != null) living.putUUID("resident", resident); tag.put("Living", living);
        }
    }
    @Override public void load(@Nonnull CompoundTag tag) {
        super.load(tag); owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null; nextPrint = tag.getLong("NextPrint");
        customName = tag.contains("CustomName", net.minecraft.nbt.Tag.TAG_STRING) ? Component.Serializer.fromJson(Objects.requireNonNull(tag.getString("CustomName"))) : null;
        if (kind() == ColonyContent.Kind.WAREHOUSE && tag.contains("Inventory")) inventory.load(tag.getCompound("Inventory"));
        resident = null; schoolLevel = 1; automaticHousing = true; rejectedFacility = null;
        if (tag.contains("Living")) {
            var raw = Objects.requireNonNull(tag.get("Living"));
            if (!(raw instanceof CompoundTag living) || !living.contains("schemaVersion", net.minecraft.nbt.Tag.TAG_INT)
                    || !(living.getInt("schemaVersion") == 1 ? java.util.Set.of("schemaVersion", "schoolLevel", "resident") : java.util.Set.of("schemaVersion", "schoolLevel", "resident", "automaticHousing")).containsAll(living.getAllKeys())
                    || living.getInt("schemaVersion") < 1 || living.getInt("schemaVersion") > 2 || !living.contains("schoolLevel", net.minecraft.nbt.Tag.TAG_INT)
                    || living.getInt("schoolLevel") < 1 || living.getInt("schoolLevel") > 3
                    || living.getInt("schemaVersion") == 2 && (!living.contains("automaticHousing", net.minecraft.nbt.Tag.TAG_BYTE) || living.getByte("automaticHousing") < 0 || living.getByte("automaticHousing") > 1)
                    || living.contains("resident") && !living.hasUUID("resident")) rejectedFacility = Objects.requireNonNull(raw.copy());
            else { schoolLevel = living.getInt("schoolLevel"); resident = living.hasUUID("resident") ? living.getUUID("resident") : null; automaticHousing = living.getInt("schemaVersion") == 1 || living.getBoolean("automaticHousing"); }
        }
    }
    public void dropContents() {
        var currentLevel = level;
        if (kind() == ColonyContent.Kind.ACADEMY && currentLevel != null && !facilityReadOnly() && schoolLevel > 1)
            net.minecraft.world.Containers.dropItemStack(currentLevel, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), new net.minecraft.world.item.ItemStack(Objects.requireNonNull(ColonyContent.ACADEMY_UPGRADE.get()), schoolLevel - 1));
        if (converting || kind() != ColonyContent.Kind.WAREHOUSE || currentLevel == null) return;
        if (inventory.readOnly()) return;
        inventory.dropInto(currentLevel, worldPosition);
        for (var upgrade : java.util.List.of(new net.minecraft.world.item.ItemStack(Objects.requireNonNull(ColonyContent.CAPACITY_UPGRADE.get()), inventory.capacityLevel()),
                new net.minecraft.world.item.ItemStack(Objects.requireNonNull(ColonyContent.STACK_UPGRADE.get()), inventory.stackLevel()))) {
            if (!upgrade.isEmpty()) net.minecraft.world.Containers.dropItemStack(currentLevel, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), upgrade);
        }
    }
    @Override public void onLoad() { super.onLoad(); if (level instanceof ServerLevel server) { if (kind() == ColonyContent.Kind.PRINTING_CORE) PrintingCores.add(server, Objects.requireNonNull(worldPosition)); dev.colony.camera.work.WorkStations.add(server, Objects.requireNonNull(worldPosition)); } }
    @Override public void setRemoved() { if (level instanceof ServerLevel server) { PrintingCores.remove(server, Objects.requireNonNull(worldPosition)); dev.colony.camera.work.WorkStations.remove(server, Objects.requireNonNull(worldPosition)); StorageNetwork.invalidate(server); } super.setRemoved(); }
    @Override public Component getDisplayName() { var name = customName; return name != null ? name : getBlockState().getBlock().getName(); }
    @Override public AbstractContainerMenu createMenu(int id, @Nonnull Inventory inventory, @Nonnull Player player) { return new StorageMenu(id, inventory, Objects.requireNonNull(worldPosition)); }
    @Override public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER && (kind() == ColonyContent.Kind.WAREHOUSE && !inventory.readOnly() || kind() == ColonyContent.Kind.NETWORK_PORT)) return capability.cast();
        return super.getCapability(cap, side);
    }
    private net.minecraftforge.items.IItemHandler handler() { return kind() == ColonyContent.Kind.NETWORK_PORT ? new dev.colony.camera.storage.NetworkItemHandler(this) : inventory; }
    @Override public void invalidateCaps() { super.invalidateCaps(); capability.invalidate(); }
    @Override public void reviveCaps() { super.reviveCaps(); capability = LazyOptional.of(this::handler); }
}
