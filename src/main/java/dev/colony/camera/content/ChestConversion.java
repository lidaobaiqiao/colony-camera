package dev.colony.camera.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import java.util.ArrayList;
import java.util.Objects;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class ChestConversion {
    private ChestConversion() {}

    public static boolean convert(ServerPlayer player, BlockPos pos) {
        var level = player.serverLevel();
        var state = level.getBlockState(pos);
        if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)
                || state.getValue(Objects.requireNonNull(ChestBlock.TYPE)) != ChestType.SINGLE
                || player.isSpectator() || !player.getAbilities().mayBuild || !level.mayInteract(player, pos)
                || !chest.saveWithoutMetadata().getString("Lock").isEmpty()) return false;
        var snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        var contents = new ArrayList<ItemStack>();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) contents.add(chest.getItem(slot).copy());
        var name = chest.getCustomName();
        // ChestBlock.onRemove normally spills its inventory; the snapshot is the rollback copy.
        chest.clearContent();
        try {
            var next = Objects.requireNonNull(ColonyContent.WAREHOUSE.get()).defaultBlockState()
                    .setValue(Objects.requireNonNull(ChestBlock.FACING), Objects.requireNonNull(state.getValue(Objects.requireNonNull(ChestBlock.FACING))));
            if (!level.setBlock(pos, Objects.requireNonNull(next), 3)
                    || !(level.getBlockEntity(pos) instanceof MachineBlockEntity machine)) {
                snapshot.restore(true, false);
                return false;
            }
            machine.converting = true;
            if (ForgeEventFactory.onBlockPlace(player, snapshot, Direction.UP)) {
                snapshot.restore(true, false);
                return false;
            }
            machine.owner(Objects.requireNonNull(player.getUUID()));
            machine.customName = name;
            machine.inventory.upgrade(false, 1);
            for (int slot = 0; slot < contents.size(); slot++) machine.inventory.setStackInSlot(slot, contents.get(slot));
            machine.setChanged();
            machine.converting = false;
            return true;
        } catch (RuntimeException failure) {
            snapshot.restore(true, false);
            return false;
        }
    }
}
