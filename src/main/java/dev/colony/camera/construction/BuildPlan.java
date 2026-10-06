package dev.colony.camera.construction;

import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.preset.BlockStateResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Actual item costs: a door/bed is one item, a double slab is two. */
@ParametersAreNonnullByDefault
public final class BuildPlan {
    public record Block(@Nonnull BlockPos pos, @Nonnull BlockState state) {}
    public record Unit(@Nonnull List<Block> blocks, @Nonnull Item item, int count) {
        public Unit { blocks = Objects.requireNonNull(List.copyOf(blocks)); Objects.requireNonNull(item); }
        @Nonnull public BlockPos anchor() { return Objects.requireNonNull(blocks.get(0).pos()); }
    }
    @Nonnull private final List<Unit> units;
    private BuildPlan(List<Unit> units) { this.units = Objects.requireNonNull(List.copyOf(units)); }
    @Nonnull public List<Unit> units() { return units; }

    @Nonnull public static BuildPlan compile(ProjectionPlacement placement) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        for (var cell : placement.preset().blocks()) {
            var pos = placement.transform(Objects.requireNonNull(cell));
            states.put(new BlockPos(pos.x(), pos.y(), pos.z()), BlockStateResolver.resolve(cell.state(), placement.quarterTurns(), placement.mirrored()));
        }
        var consumed = new HashSet<BlockPos>();
        var units = new ArrayList<Unit>();
        var positions = states.keySet().stream().sorted(Comparator.comparingInt((BlockPos position) -> position.getY())
                .thenComparingInt((BlockPos checkedElement) -> Objects.requireNonNull(checkedElement).getZ()).thenComparingInt((BlockPos checkedElement) -> Objects.requireNonNull(checkedElement).getX())).toList();
        for (BlockPos position : positions) {
            BlockPos pos = Objects.requireNonNull(position);
            if (consumed.contains(pos)) continue;
            BlockState state = Objects.requireNonNull(states.get(pos));
            var block = state.getBlock();
            if (!state.getFluidState().isEmpty() || block instanceof FallingBlock || block instanceof DoublePlantBlock) {
                throw new IllegalArgumentException("Unsupported construction block: " + pos);
            }
            Item item = block.asItem();
            if (!(item instanceof BlockItem blockItem) || blockItem.getBlock() != block) {
                throw new IllegalArgumentException("No direct building item: " + pos);
            }
            var members = new ArrayList<Block>();
            if (block instanceof DoorBlock) {
                if (state.getValue(Objects.requireNonNull(DoorBlock.HALF)) != DoubleBlockHalf.LOWER) throw new IllegalArgumentException("Incomplete door");
                BlockPos upper = Objects.requireNonNull(pos.above());
                requirePair(states, upper, Objects.requireNonNull(state.setValue(Objects.requireNonNull(DoorBlock.HALF), DoubleBlockHalf.UPPER)));
                members.add(new Block(pos, state)); members.add(new Block(upper, Objects.requireNonNull(states.get(upper))));
                consumed.add(upper);
            } else if (block instanceof BedBlock) {
                BlockPos foot = Objects.requireNonNull(state.getValue(Objects.requireNonNull(BedBlock.PART)) == BedPart.FOOT ? pos : pos.relative(Objects.requireNonNull(state.getValue(Objects.requireNonNull(BedBlock.FACING)).getOpposite())));
                BlockState footState = Objects.requireNonNull(states.get(foot), "Incomplete bed");
                BlockPos head = Objects.requireNonNull(foot.relative(Objects.requireNonNull(footState.getValue(Objects.requireNonNull(BedBlock.FACING)))));
                requirePair(states, head, Objects.requireNonNull(footState.setValue(Objects.requireNonNull(BedBlock.PART), BedPart.HEAD)));
                requirePair(states, foot, Objects.requireNonNull(footState.setValue(Objects.requireNonNull(BedBlock.PART), BedPart.FOOT)));
                members.add(new Block(foot, footState)); members.add(new Block(head, Objects.requireNonNull(states.get(head))));
                consumed.add(foot); consumed.add(head);
            } else members.add(new Block(pos, state));
            int count = block instanceof SlabBlock && state.getValue(Objects.requireNonNull(SlabBlock.TYPE)) == SlabType.DOUBLE ? 2 : 1;
            consumed.add(pos); units.add(new Unit(members, Objects.requireNonNull(item), count));
        }
        return new BuildPlan(units);
    }
    private static void requirePair(Map<BlockPos, BlockState> states, BlockPos pos, BlockState expected) {
        if (!expected.equals(states.get(pos))) throw new IllegalArgumentException("Incomplete/inconsistent paired block: " + pos);
    }
}
