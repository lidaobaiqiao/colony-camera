package dev.colony.camera.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import javax.annotation.Nullable;
import java.util.Objects;

/** Large counts stay in memory as ints and persist separately from ItemStack's byte Count. */
public final class WarehouseInventory extends ItemStackHandler {
    private int capacityLevel;
    private int stackLevel;
    private final Runnable changed;
    @Nullable private CompoundTag rejected;
    public WarehouseInventory(Runnable changed) { super(27); this.changed = changed; }
    public int capacityLevel() { return capacityLevel; }
    public int stackLevel() { return stackLevel; }
    public boolean readOnly() { return rejected != null; }
    @Override public int getSlotLimit(int slot) { return 64 << stackLevel; }
    @Override protected int getStackLimit(int slot, ItemStack stack) { return stack.getMaxStackSize() == 1 ? 1 : stack.getMaxStackSize() << stackLevel; }
    @Override protected void onContentsChanged(int slot) { changed.run(); }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return readOnly() ? stack : super.insertItem(slot, stack, simulate); }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return readOnly() ? ItemStack.EMPTY : super.extractItem(slot, Math.min(amount, getStackInSlot(slot).getMaxStackSize()), simulate); }
    @Override public void setStackInSlot(int slot, ItemStack stack) {
        if (readOnly() || stack.getCount() > getStackLimit(slot, stack)) throw new IllegalArgumentException("Invalid warehouse stack");
        super.setStackInSlot(slot, stack);
    }
    public boolean upgrade(boolean stack, int delta) {
        if (readOnly() || Math.abs(delta) != 1) return false;
        int capacity = capacityLevel + (stack ? 0 : delta); int multiplier = stackLevel + (stack ? delta : 0);
        if (capacity < 0 || capacity > 3 || multiplier < 0 || multiplier > 3) return false;
        int size = 27 * (capacity + 1);
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack item = stacks.get(slot);
            int limit = item.getMaxStackSize() == 1 ? 1 : item.getMaxStackSize() << multiplier;
            if (!item.isEmpty() && (slot >= size || item.getCount() > limit)) return false;
        }
        NonNullList<ItemStack> resized = NonNullList.withSize(size, Objects.requireNonNull(ItemStack.EMPTY));
        for (int slot = 0; slot < Math.min(size, stacks.size()); slot++) resized.set(slot, stacks.get(slot));
        stacks = resized; capacityLevel = capacity; stackLevel = multiplier; changed.run(); return true;
    }
    public CompoundTag save() {
        if (rejected != null) return rejected.copy();
        CompoundTag root = new CompoundTag(); root.putInt("schemaVersion", 1);
        root.putInt("capacityLevel", capacityLevel); root.putInt("stackLevel", stackLevel);
        ListTag items = new ListTag();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot); if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag(); entry.putInt("slot", slot); entry.putInt("count", stack.getCount());
            ItemStack template = stack.copy(); template.setCount(1); entry.put("item", Objects.requireNonNull(template.save(new CompoundTag()))); items.add(entry);
        }
        root.put("items", items); return root;
    }
    public void load(CompoundTag root) {
        try {
            int capacity = root.getInt("capacityLevel"); int multiplier = root.getInt("stackLevel");
            if (!root.contains("schemaVersion", Tag.TAG_INT) || !root.contains("capacityLevel", Tag.TAG_INT) || !root.contains("stackLevel", Tag.TAG_INT)
                    || root.getInt("schemaVersion") != 1 || capacity < 0 || capacity > 3 || multiplier < 0 || multiplier > 3
                    || !(root.get("items") instanceof ListTag items) || !items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException();
            NonNullList<ItemStack> restored = NonNullList.withSize(27 * (capacity + 1), Objects.requireNonNull(ItemStack.EMPTY));
            for (int i = 0; i < items.size(); i++) {
                CompoundTag entry = items.getCompound(i); int slot = entry.getInt("slot"); int count = entry.getInt("count");
                if (!entry.contains("slot", Tag.TAG_INT) || !entry.contains("count", Tag.TAG_INT) || !entry.contains("item", Tag.TAG_COMPOUND)) throw new IllegalArgumentException();
                ItemStack item = ItemStack.of(Objects.requireNonNull(entry.getCompound("item"))); int limit = item.getMaxStackSize() == 1 ? 1 : item.getMaxStackSize() << multiplier;
                if (slot < 0 || slot >= restored.size() || count < 1 || count > limit || item.isEmpty() || !restored.get(slot).isEmpty()) throw new IllegalArgumentException();
                item.setCount(count); restored.set(slot, item);
            }
            stacks = restored; capacityLevel = capacity; stackLevel = multiplier; rejected = null;
        } catch (RuntimeException failure) { rejected = root.copy(); }
    }
    public void dropInto(Level level, BlockPos pos) {
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            while (!stack.isEmpty()) {
                ItemStack part = stack.split(Math.min(stack.getCount(), stack.getMaxStackSize()));
                Containers.dropItemStack(Objects.requireNonNull(level), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, Objects.requireNonNull(part));
            }
        }
        changed.run();
    }
}
