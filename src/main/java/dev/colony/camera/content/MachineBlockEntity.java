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
    }
    @Override public void load(@Nonnull CompoundTag tag) {
        super.load(tag); owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null; nextPrint = tag.getLong("NextPrint");
        customName = tag.contains("CustomName", net.minecraft.nbt.Tag.TAG_STRING) ? Component.Serializer.fromJson(Objects.requireNonNull(tag.getString("CustomName"))) : null;
        if (kind() == ColonyContent.Kind.WAREHOUSE && tag.contains("Inventory")) inventory.load(tag.getCompound("Inventory"));
    }
    public void dropContents() {
        var currentLevel = level;
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
