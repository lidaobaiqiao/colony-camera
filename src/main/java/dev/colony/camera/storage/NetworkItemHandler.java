package dev.colony.camera.storage;

import dev.colony.camera.content.MachineBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import java.util.List;
import java.util.Objects;

/** Port automation uses the same finite warehouse inventory as workers and the menu. */
public final class NetworkItemHandler implements IItemHandler {
    private final MachineBlockEntity port;
    public NetworkItemHandler(MachineBlockEntity port) { this.port = port; }
    private StorageAccess access() {
        var owner = port.owner();
        return port.getLevel() instanceof ServerLevel level && owner != null ? StorageAccess.at(level, Objects.requireNonNull(port.getBlockPos()), owner) : StorageAccess.empty();
    }
    @Override public int getSlots() { return access().inventories.isEmpty() ? 0 : access().entries().size() + 1; }
    @Override public ItemStack getStackInSlot(int slot) {
        List<StorageAccess.Entry> entries = access().entries(); if (slot < 0 || slot >= entries.size()) return ItemStack.EMPTY;
        var entry = entries.get(slot); ItemStack item = entry.item().copy(); item.setCount(Math.min(entry.count(), item.getMaxStackSize())); return item;
    }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return slot < 0 || slot >= getSlots() ? stack : access().insert(Objects.requireNonNull(stack), simulate); }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { ItemStack key = getStackInSlot(slot); return key.isEmpty() ? ItemStack.EMPTY : access().extract(key, amount, simulate); }
    @Override public int getSlotLimit(int slot) { return 64; }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return true; }
}
