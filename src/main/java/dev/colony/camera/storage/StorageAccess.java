package dev.colony.camera.storage;

import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/** A unified list of item types; physical stacks remain in the warehouse nodes. */
@ParametersAreNonnullByDefault
public final class StorageAccess {
    public record Entry(@Nonnull ItemStack item, int count) {}
    private record Key(Item item, @Nullable CompoundTag tag) {}
    public final String status;
    public final List<WarehouseInventory> inventories;
    private StorageAccess(String status, List<WarehouseInventory> inventories) { this.status = status; this.inventories = inventories; }
    public static StorageAccess empty() { return new StorageAccess("denied", Objects.requireNonNull(List.of())); }
    public static StorageAccess at(ServerLevel level, BlockPos pos, UUID owner) {
        if (!GroundResources.loaded(level, pos) || !(level.getBlockEntity(pos) instanceof MachineBlockEntity machine) || !machine.permits(owner)) return empty();
        if (machine.kind() == ColonyContent.Kind.WAREHOUSE) return new StorageAccess(machine.inventory.readOnly() ? "damaged" : "warehouse", Objects.requireNonNull(machine.inventory.readOnly() ? List.of() : List.of(machine.inventory)));
        StorageNetwork.Component network = StorageNetwork.find(level, pos, owner);
        return new StorageAccess(Objects.requireNonNull(network.status()), Objects.requireNonNull(network.valid() ? network.warehouses().stream().map(box -> box.inventory).toList() : List.of()));
    }
    public List<Entry> entries() {
        Map<Key, Entry> items = new LinkedHashMap<>();
        for (WarehouseInventory inventory : inventories) for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot); if (stack.isEmpty()) continue;
            Key key = new Key(stack.getItem(), stack.getTag()); Entry previous = items.get(key);
            ItemStack template = stack.copy(); template.setCount(1);
            items.put(key, new Entry(template, Math.addExact(previous == null ? 0 : previous.count(), stack.getCount())));
        }
        return items.values().stream().sorted(Comparator.comparing(entry -> String.valueOf(ForgeRegistries.ITEMS.getKey(entry.item().getItem())))).toList();
    }
    public int count(ItemStack key) { return entries().stream().filter(entry -> ItemStack.isSameItemSameTags(key, entry.item())).mapToInt((Entry checkedElement) -> Objects.requireNonNull(checkedElement).count()).sum(); }
    @Nonnull public ItemStack insert(ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();
        for (WarehouseInventory inventory : inventories) { remaining = ItemHandlerHelper.insertItemStacked(inventory, remaining, simulate); if (remaining.isEmpty()) break; }
        return Objects.requireNonNull(remaining);
    }
    @Nonnull public ItemStack extract(ItemStack key, int amount, boolean simulate) {
        int wanted = Math.min(amount, key.getMaxStackSize()); int obtained = 0;
        for (WarehouseInventory inventory : inventories) for (int slot = 0; slot < inventory.getSlots() && obtained < wanted; slot++) {
            if (!ItemStack.isSameItemSameTags(key, Objects.requireNonNull(inventory.getStackInSlot(slot)))) continue;
            obtained += inventory.extractItem(slot, wanted - obtained, simulate).getCount();
        }
        ItemStack result = key.copy(); result.setCount(obtained); return result;
    }
}
