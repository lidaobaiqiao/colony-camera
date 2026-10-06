package dev.colony.camera.content;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class MachineBlockItem extends BlockItem {
    @javax.annotation.Nonnull private final String tooltip;

    public MachineBlockItem(Block block, ColonyContent.Kind kind) {
        super(block, new Properties());
        tooltip = "colonycamera.tooltip." + kind.name().toLowerCase(Locale.ROOT);
    }

    @Override public void appendHoverText(ItemStack item, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(item, level, lines, flag);
        lines.add(Objects.requireNonNull(Component.translatable(tooltip)));
        lines.add(Objects.requireNonNull(Component.translatable("colonycamera.tooltip.crafting")));
    }
}
