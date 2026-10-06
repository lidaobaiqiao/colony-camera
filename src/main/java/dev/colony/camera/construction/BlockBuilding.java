package dev.colony.camera.construction;

import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.preset.BlockPreset;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server and client derive the same small design from a registered item; no client block data is trusted. */
@ParametersAreNonnullByDefault
public final class BlockBuilding {
    private BlockBuilding() {}
    @Nonnull public static BlockPreset design(Item item) {
        if (!(item instanceof BlockItem blockItem)) throw new IllegalArgumentException("unsupported");
        String id = Objects.requireNonNull(ForgeRegistries.ITEMS.getKey(item)).toString();
        BlockState state = Objects.requireNonNull(blockItem.getBlock().defaultBlockState());
        if (state.hasProperty(Objects.requireNonNull(BlockStateProperties.WATERLOGGED))) state = Objects.requireNonNull(state.setValue(Objects.requireNonNull(BlockStateProperties.WATERLOGGED), false));
        if (state.hasProperty(Objects.requireNonNull(BlockStateProperties.HORIZONTAL_FACING))) state = Objects.requireNonNull(state.setValue(Objects.requireNonNull(BlockStateProperties.HORIZONTAL_FACING), Direction.SOUTH));
        var cells = new ArrayList<BlockPreset.Cell>(); int height = 1, depth = 1;
        if (state.getBlock() instanceof DoorBlock) {
            cells.add(new BlockPreset.Cell(0, 0, 0, saved(Objects.requireNonNull(state.setValue(Objects.requireNonNull(DoorBlock.HALF), DoubleBlockHalf.LOWER)))));
            cells.add(new BlockPreset.Cell(0, 1, 0, saved(Objects.requireNonNull(state.setValue(Objects.requireNonNull(DoorBlock.HALF), DoubleBlockHalf.UPPER))))); height = 2;
        } else if (state.getBlock() instanceof BedBlock) {
            cells.add(new BlockPreset.Cell(0, 0, 0, saved(Objects.requireNonNull(state.setValue(Objects.requireNonNull(BedBlock.PART), BedPart.FOOT)))));
            cells.add(new BlockPreset.Cell(0, 0, 1, saved(Objects.requireNonNull(state.setValue(Objects.requireNonNull(BedBlock.PART), BedPart.HEAD))))); depth = 2;
        } else cells.add(new BlockPreset.Cell(0, 0, 0, saved(state)));
        String name = Objects.requireNonNull(item.getDescriptionId()); if (name.length() > 48) name = id.substring(0, Math.min(id.length(), 48));
        BlockPreset preset = new BlockPreset(Objects.requireNonNull(UUID.nameUUIDFromBytes(("palette/" + id).getBytes(StandardCharsets.UTF_8))), Objects.requireNonNull(name), 1, height, depth, Objects.requireNonNull(cells));
        BuildPlan.compile(new ProjectionPlacement(preset.id(), preset, 0, 0, 0, 0, false, true)); return preset;
    }
    @Nonnull private static BlockPreset.State saved(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        state.getValues().forEach((property, value) -> properties.put(property.getName(), value.toString()));
        return new BlockPreset.State(Objects.requireNonNull(Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(state.getBlock())).toString()), Objects.requireNonNull(properties));
    }
}
