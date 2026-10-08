package dev.colony.camera.worker;

import com.google.gson.JsonObject;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.ParametersAreNonnullByDefault;

/** Atomic exchange with the issuing player's real inventory; main-hand cargo is never equipment. */
@ParametersAreNonnullByDefault
public final class WorkerEquipment {
    private WorkerEquipment() {}
    public static void exchange(ServerPlayer player, WorkerEntity worker, JsonObject request) {
        EquipmentSlot slot = Objects.requireNonNull(EquipmentSlot.valueOf(Objects.requireNonNull(request.get("slot").getAsString())));
        if (slot == EquipmentSlot.MAINHAND) throw new IllegalArgumentException("invalid");
        ItemStack previous = Objects.requireNonNull(worker.getItemBySlot(slot));
        if (!player.isCreative() && EnchantmentHelper.hasBindingCurse(previous)) throw new IllegalArgumentException("equipment");
        int source = request.get("source").getAsInt();
        if (source < -1 || source >= 36) throw new IllegalArgumentException("invalid");
        List<ItemStack> planned = new ArrayList<>();
        for (int index = 0; index < 36; index++) planned.add(Objects.requireNonNull(player.getInventory().getItem(index).copy()));
        ItemStack equipped = Objects.requireNonNull(ItemStack.EMPTY);
        if (source >= 0) {
            String expected = Objects.requireNonNull(request.get("expected").getAsString());
            if (expected.length() > 4096) throw new IllegalArgumentException("limit");
            ItemStack supplied = Objects.requireNonNull(planned.get(source));
            try {
                ItemStack advertised = Objects.requireNonNull(ItemStack.of(Objects.requireNonNull(TagParser.parseTag(expected))));
                if (supplied.isEmpty() || !ItemStack.matches(supplied, advertised)) throw new IllegalArgumentException("equipment");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) { throw new IllegalArgumentException("equipment"); }
            if (slot != EquipmentSlot.OFFHAND && !supplied.canEquip(slot, worker)) throw new IllegalArgumentException("equipment");
            equipped = Objects.requireNonNull(supplied.split(1));
        }
        ItemStack returned = Objects.requireNonNull(previous.copy());
        for (ItemStack entry : planned) if (!returned.isEmpty() && !entry.isEmpty() && ItemStack.isSameItemSameTags(entry, returned)) {
            int count = Math.min(returned.getCount(), Math.max(0, entry.getMaxStackSize() - entry.getCount()));
            entry.grow(count); returned.shrink(count);
        }
        for (int index = 0; index < planned.size() && !returned.isEmpty(); index++) if (planned.get(index).isEmpty()) {
            ItemStack batch = Objects.requireNonNull(returned.split(Math.min(returned.getCount(), returned.getMaxStackSize()))); planned.set(index, batch);
        }
        if (!returned.isEmpty()) throw new IllegalArgumentException("inventory_full");
        for (int index = 0; index < planned.size(); index++) player.getInventory().setItem(index, Objects.requireNonNull(planned.get(index)));
        worker.setItemSlot(slot, equipped); worker.setDropChance(slot, 2.0F);
        player.getInventory().setChanged(); player.containerMenu.broadcastChanges();
    }
}
