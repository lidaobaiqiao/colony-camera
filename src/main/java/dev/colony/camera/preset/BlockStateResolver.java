package dev.colony.camera.preset;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

/** Shared by detached previews and server validation; never reads or writes a world. */
@ParametersAreNonnullByDefault
public final class BlockStateResolver {
    private BlockStateResolver() {}
    @Nonnull public static BlockState resolve(BlockPreset.State stored, int turns, boolean mirrored) {
        ResourceLocation id = Objects.requireNonNull(ResourceLocation.tryParse(stored.block()));
        if (!ForgeRegistries.BLOCKS.containsKey(id)) throw new IllegalArgumentException("Missing block: " + id);
        Block block = Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(id));
        BlockState state = Objects.requireNonNull(block.defaultBlockState());
        for (var entry : stored.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(Objects.requireNonNull(entry.getKey()));
            if (property == null) throw new IllegalArgumentException("Missing property: " + entry.getKey());
            state = property(state, property, Objects.requireNonNull(entry.getValue()));
        }
        Rotation rotation = switch (Math.floorMod(turns, 4)) {
            case 1 -> Rotation.CLOCKWISE_90; case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE;
        };
        return rotate(Objects.requireNonNull(state.mirror(mirrored ? Mirror.FRONT_BACK : Mirror.NONE)), rotation);
    }
    // These detached states have no placement context; the vanilla transform is intentional.
    @SuppressWarnings("deprecation")
    @Nonnull private static BlockState rotate(BlockState state, Rotation rotation) { return Objects.requireNonNull(state.rotate(rotation)); }
    @Nonnull private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> property, String value) {
        T parsed = Objects.requireNonNull(property.getValue(value).orElseThrow(() -> new IllegalArgumentException("Invalid property: " + value)));
        return Objects.requireNonNull(state.setValue(property, parsed));
    }
}
