package dev.colony.camera.client.preset;

import dev.colony.camera.client.selection.SelectionState;
import dev.colony.camera.preset.BlockPreset;
import dev.colony.camera.preset.BlockStateResolver;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@ParametersAreNonnullByDefault
public final class MinecraftPresetAdapter {
    private MinecraftPresetAdapter() {}

    @Nonnull
    public static BlockPreset capture(ClientLevel level, Set<SelectionState.Cell> selection, String name) {
        if (selection.isEmpty() || selection.size() > BlockPreset.MAX_BLOCKS) throw new IllegalArgumentException("Empty or oversized selection");
        int minX = selection.stream().mapToInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).x()).min().orElseThrow();
        int minY = selection.stream().mapToInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).y()).min().orElseThrow();
        int minZ = selection.stream().mapToInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).z()).min().orElseThrow();
        int maxX = selection.stream().mapToInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).x()).max().orElseThrow();
        int maxY = selection.stream().mapToInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).y()).max().orElseThrow();
        int maxZ = selection.stream().mapToInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).z()).max().orElseThrow();
        var blocks = new ArrayList<BlockPreset.Cell>();
        for (SelectionState.Cell cell : selection.stream().sorted(java.util.Comparator.comparingInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).y())
                .thenComparingInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).z()).thenComparingInt((SelectionState.Cell checkedElement) -> Objects.requireNonNull(checkedElement).x())).toList()) {
            BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    || !level.getWorldBorder().isWithinBounds(pos) || level.isOutsideBuildHeight(pos)) {
                throw new IllegalArgumentException("Selection contains unloaded positions");
            }
            BlockState state = level.getBlockState(pos);
            // Reject a stale selection instead of silently saving an incomplete structure.
            if (state.isAir()) throw new IllegalArgumentException("Selection changed before capture");
            Map<String, String> properties = new LinkedHashMap<>();
            for (Property<?> property : state.getProperties()) properties.put(property.getName(), propertyName(state, property));
            String id = Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(Objects.requireNonNull(state.getBlock()))).toString();
            blocks.add(new BlockPreset.Cell(cell.x() - minX, cell.y() - minY, cell.z() - minZ,
                    new BlockPreset.State(Objects.requireNonNull(id), properties)));
        }
        return new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, blocks);
    }

    @Nonnull
    public static BlockState resolve(BlockPreset.State stored, int turns, boolean mirrored) {
        return BlockStateResolver.resolve(stored, turns, mirrored);
    }

    @Nonnull
    private static <T extends Comparable<T>> String propertyName(BlockState state, Property<T> property) {
        return Objects.requireNonNull(property.getName(Objects.requireNonNull(state.getValue(property))));
    }

}
