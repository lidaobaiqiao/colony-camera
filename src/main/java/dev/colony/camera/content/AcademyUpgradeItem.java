package dev.colony.camera.content;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

@ParametersAreNonnullByDefault
public final class AcademyUpgradeItem extends Item {
    public AcademyUpgradeItem() { super(new Properties()); }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockEntity(Objects.requireNonNull(context.getClickedPos())) instanceof MachineBlockEntity machine)
                || machine.kind() != ColonyContent.Kind.ACADEMY) return InteractionResult.PASS;
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player) || !machine.claim(player)) return InteractionResult.FAIL;
        if (machine.facilityReadOnly() || machine.schoolLevel() >= 3) return InteractionResult.FAIL;
        machine.schoolLevel(machine.schoolLevel() + 1);
        if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
        player.displayClientMessage(Objects.requireNonNull(Component.translatable("colonycamera.academy.upgraded", machine.schoolLevel())), true);
        return InteractionResult.CONSUME;
    }
}
