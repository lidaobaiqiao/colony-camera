package dev.colony.camera.content;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.Objects;

public final class WarehouseUpgradeItem extends Item {
    private final boolean stack;
    public WarehouseUpgradeItem(boolean stack) { super(new Properties()); this.stack = stack; }
    // Run before ChestBlock.use opens its menu, outside Forge's later item placement capture.
    @Override public InteractionResult onItemUseFirst(ItemStack item, UseOnContext context) { return useOn(Objects.requireNonNull(context)); }
    @Override public void appendHoverText(@javax.annotation.Nonnull ItemStack item, @javax.annotation.Nullable net.minecraft.world.level.Level level,
            @javax.annotation.Nonnull java.util.List<Component> lines, @javax.annotation.Nonnull net.minecraft.world.item.TooltipFlag flag) {
        lines.add(Objects.requireNonNull(Component.translatable("colonycamera.tooltip." + (stack ? "stack_upgrade" : "capacity_upgrade"))));
        lines.add(Objects.requireNonNull(Component.translatable("colonycamera.tooltip.remove_upgrade")));
        lines.add(Objects.requireNonNull(Component.translatable("colonycamera.tooltip.crafting")));
    }
    @Override public InteractionResult useOn(@javax.annotation.Nonnull UseOnContext context) {
        if (!stack && context.getLevel().getBlockEntity(Objects.requireNonNull(context.getClickedPos())) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity) {
            if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
            if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
            boolean converted = !player.isShiftKeyDown() && ChestConversion.convert(player, Objects.requireNonNull(context.getClickedPos()));
            if (converted && !player.getAbilities().instabuild) context.getItemInHand().shrink(1);
            player.displayClientMessage(Objects.requireNonNull(Component.translatable("colonycamera.storage." + (converted ? "upgraded" : "conversion_failed"))), true);
            return InteractionResult.CONSUME;
        }
        if (!(context.getLevel().getBlockEntity(Objects.requireNonNull(context.getClickedPos())) instanceof MachineBlockEntity machine) || machine.kind() != ColonyContent.Kind.WAREHOUSE) return InteractionResult.PASS;
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player) || !machine.claim(player)) return InteractionResult.FAIL;
        boolean down = player.isShiftKeyDown();
        boolean changed = machine.inventory.upgrade(stack, down ? -1 : 1);
        if (changed && !player.getAbilities().instabuild) {
            if (down) { var refund = new ItemStack(this); if (!player.getInventory().add(refund)) player.drop(refund, false); }
            else context.getItemInHand().shrink(1);
        }
        player.displayClientMessage(Objects.requireNonNull(Component.translatable("colonycamera.storage." + (changed ? down ? "downgraded" : "upgraded" : "upgrade_failed"))), true);
        return InteractionResult.CONSUME;
    }
}
