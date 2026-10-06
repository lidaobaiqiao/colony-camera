package dev.colony.camera.storage;

import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Display slots contain normal stacks; only server actions modify the backing large inventory. */
@javax.annotation.ParametersAreNonnullByDefault
public final class StorageMenu extends AbstractContainerMenu {
    public static final int PAGE_SIZE = 36;
    @Nonnull public final BlockPos pos;
    private final Player player;
    @Nonnull private final SimpleContainer display = new SimpleContainer(PAGE_SIZE);
    @Nonnull private final ContainerData data = new SimpleContainerData(PAGE_SIZE * 2 + 5);
    private List<StorageAccess.Entry> entries = List.of();
    public StorageMenu(int id, Inventory inventory, FriendlyByteBuf buffer) { this(id, inventory, Objects.requireNonNull(buffer.readBlockPos())); }
    public StorageMenu(int id, Inventory inventory, BlockPos pos) {
        super(ColonyContent.STORAGE_MENU.get(), id); this.pos = pos; this.player = inventory.player;
        for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(display, row * 9 + col, 17 + col * 18, 36 + row * 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, 17 + col * 18, 148 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 17 + col * 18, 206));
        addDataSlots(data); refresh();
    }
    public int page() { return data.get(PAGE_SIZE * 2); }
    public int pages() { return Math.max(1, data.get(PAGE_SIZE * 2 + 1)); }
    public int capacityLevel() { return data.get(PAGE_SIZE * 2 + 2); }
    public int stackLevel() { return data.get(PAGE_SIZE * 2 + 3); }
    public int status() { return data.get(PAGE_SIZE * 2 + 4); }
    public int count(int slot) { return (data.get(slot * 2) & 65535) | ((data.get(slot * 2 + 1) & 65535) << 16); }
    private StorageAccess access(ServerLevel level) { return StorageAccess.at(level, pos, Objects.requireNonNull(player.getUUID())); }
    private void refresh() {
        if (!(player.level() instanceof ServerLevel level)) return;
        StorageAccess access = access(level); entries = access.entries();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE); int page = Math.min(page(), pages - 1);
        data.set(PAGE_SIZE * 2, page); data.set(PAGE_SIZE * 2 + 1, pages);
        data.set(PAGE_SIZE * 2 + 4, switch (access.status) { case "warehouse" -> 0; case "connected" -> 1; case "no_controller" -> 2; case "multiple_controllers" -> 3; case "too_large" -> 4; default -> 5; });
        if (level.getBlockEntity(pos) instanceof MachineBlockEntity machine && machine.kind() == ColonyContent.Kind.WAREHOUSE) {
            data.set(PAGE_SIZE * 2 + 2, machine.inventory.capacityLevel()); data.set(PAGE_SIZE * 2 + 3, machine.inventory.stackLevel());
        }
        for (int index = 0; index < PAGE_SIZE; index++) {
            int entryIndex = page * PAGE_SIZE + index; int count = 0; ItemStack item = ItemStack.EMPTY;
            if (entryIndex < entries.size()) { var entry = entries.get(entryIndex); item = entry.item().copy(); count = entry.count(); item.setCount(Math.min(count, item.getMaxStackSize())); }
            display.setItem(index, Objects.requireNonNull(item)); data.set(index * 2, count & 65535); data.set(index * 2 + 1, count >>> 16);
        }
    }
    @Override public void broadcastChanges() { refresh(); super.broadcastChanges(); }
    @Override public boolean stillValid(Player player) {
        if (!(this.player.level() instanceof ServerLevel level)) return true;
        return GroundResources.loaded(level, pos) && this.player.level().getBlockEntity(pos) instanceof MachineBlockEntity machine
                && machine.permits(player.getUUID()) && player.distanceToSqr(GroundResources.center(pos)) <= 64;
    }
    @Override public boolean clickMenuButton(Player player, int button) {
        if (!stillValid(player) || button != 0 && button != 1) return false;
        data.set(PAGE_SIZE * 2, Math.max(0, Math.min(pages() - 1, page() + (button == 0 ? -1 : 1)))); refresh(); broadcastChanges(); return true;
    }
    @Override public void clicked(int index, int button, ClickType type, Player player) {
        // Vanilla drag/clone/collect would write the capped display back and lose the hidden count.
        if (type == ClickType.QUICK_CRAFT || type == ClickType.PICKUP_ALL) return;
        if (index < 0 || index >= PAGE_SIZE) { super.clicked(index, button, type, player); return; }
        if (!(player.level() instanceof ServerLevel level) || !stillValid(player)) return;
        refresh(); StorageAccess access = access(level); ItemStack shown = display.getItem(index);
        if (type == ClickType.QUICK_MOVE) quickMoveStack(player, index);
        else if (type == ClickType.PICKUP) {
            ItemStack held = getCarried();
            if (held.isEmpty() && !shown.isEmpty()) setCarried(access.extract(shown, button == 1 ? Math.min((count(index) + 1) / 2, shown.getMaxStackSize()) : shown.getMaxStackSize(), false));
            else if (!held.isEmpty()) {
                ItemStack offered = held.copy(); offered.setCount(button == 1 ? 1 : held.getCount());
                ItemStack remaining = access.insert(offered, false); held.shrink(offered.getCount() - remaining.getCount()); setCarried(held);
            }
        } else if (type == ClickType.SWAP && (button >= 0 && button <= 8 || button == 40)) {
            ItemStack hotbar = player.getInventory().getItem(button);
            if (hotbar.isEmpty() && !shown.isEmpty()) player.getInventory().setItem(button, access.extract(shown, shown.getMaxStackSize(), false));
            else if (!hotbar.isEmpty()) player.getInventory().setItem(button, access.insert(hotbar, false));
        } else if (type == ClickType.THROW && !shown.isEmpty()) player.drop(access.extract(shown, button == 0 ? 1 : shown.getMaxStackSize(), false), true);
        refresh(); broadcastChanges();
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!(player.level() instanceof ServerLevel level) || !stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        refresh(); StorageAccess access = access(level); Slot slot = slots.get(index); ItemStack original = slot.getItem().copy();
        if (original.isEmpty()) return ItemStack.EMPTY;
        if (index < PAGE_SIZE) {
            ItemStack taken = access.extract(original, original.getMaxStackSize(), false);
            int obtained = taken.getCount();
            moveItemStackTo(taken, PAGE_SIZE, slots.size(), true);
            int moved = obtained - taken.getCount();
            if (!taken.isEmpty()) { ItemStack remainder = access.insert(taken, false); if (!remainder.isEmpty()) player.drop(remainder, false); }
            if (moved == 0) return ItemStack.EMPTY;
        } else {
            ItemStack remainder = access.insert(original, false);
            if (remainder.getCount() == original.getCount()) return ItemStack.EMPTY;
            slot.set(remainder); slot.setChanged();
        }
        refresh(); return original;
    }
}
