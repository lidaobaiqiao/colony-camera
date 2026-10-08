package dev.colony.camera.content;

import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.storage.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkHooks;
import java.util.Objects;
import javax.annotation.Nullable;

@javax.annotation.ParametersAreNonnullByDefault
public final class MachineBlock extends BaseEntityBlock {
    private static final net.minecraft.world.level.block.state.properties.BooleanProperty LIT = Objects.requireNonNull(BlockStateProperties.LIT);
    private static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING = Objects.requireNonNull(BlockStateProperties.HORIZONTAL_FACING);
    public final ColonyContent.Kind kind;
    public MachineBlock(ColonyContent.Kind kind) {
        super(Properties.of().strength(3.5F).lightLevel(state -> state.getValue(Objects.requireNonNull(LIT)) ? 10 : 0)); this.kind = kind;
        registerDefaultState(Objects.requireNonNull(stateDefinition.any().setValue(Objects.requireNonNull(FACING), Direction.NORTH).setValue(Objects.requireNonNull(LIT), false)));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) { builder.add(FACING, LIT); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(Objects.requireNonNull(FACING), Objects.requireNonNull(context.getHorizontalDirection().getOpposite())); }
    @Override public BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) { return state.setValue(Objects.requireNonNull(FACING), Objects.requireNonNull(rotation.rotate(Objects.requireNonNull(state.getValue(Objects.requireNonNull(FACING)))))); }
    @Override public BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) { return rotate(state, Objects.requireNonNull(mirror.getRotation(Objects.requireNonNull(state.getValue(Objects.requireNonNull(FACING)))))); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MachineBlockEntity(pos, state); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity entity, ItemStack stack) {
        if (entity instanceof Player player && level.getBlockEntity(pos) instanceof MachineBlockEntity machine) machine.owner(Objects.requireNonNull(player.getUUID()));
    }
    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof WarehouseUpgradeItem || player.getItemInHand(hand).getItem() instanceof AcademyUpgradeItem) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer server) || !(level.getBlockEntity(pos) instanceof MachineBlockEntity machine)) return InteractionResult.FAIL;
        if (!machine.claim(server)) { server.displayClientMessage(Objects.requireNonNull(Component.translatable("colonycamera.machine.denied")), true); return InteractionResult.CONSUME; }
        if (kind == ColonyContent.Kind.PRINTING_CORE) {
            String result = ConstructionManager.get(Objects.requireNonNull(server.serverLevel())).printWorker(server, pos);
            server.displayClientMessage(Objects.requireNonNull(Component.translatable("colonycamera.print." + result)), true);
        } else if (kind == ColonyContent.Kind.COLONY_WORKBENCH || kind == ColonyContent.Kind.RESEARCH_DESK) {
            dev.colony.camera.work.WorkNetwork.openStation(server, pos, kind == ColonyContent.Kind.RESEARCH_DESK);
        } else if (kind == ColonyContent.Kind.RESIDENCE) {
            dev.colony.camera.work.WorkNetwork.openResidence(server, pos);
        } else if (kind == ColonyContent.Kind.ACADEMY) {
            dev.colony.camera.work.WorkNetwork.openPeople(server);
        } else if (kind != ColonyContent.Kind.NETWORK_CABLE) NetworkHooks.openScreen(server, machine, pos);
        return InteractionResult.CONSUME;
    }
    @Override public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(Objects.requireNonNull(LIT))) level.setBlock(pos, Objects.requireNonNull(state.setValue(Objects.requireNonNull(LIT), false)), 3);
    }
    @SuppressWarnings("deprecation")
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (state.getBlock() != next.getBlock()) {
            if (level.getBlockEntity(pos) instanceof MachineBlockEntity machine && !level.isClientSide) machine.dropContents();
            if (level instanceof ServerLevel server) StorageNetwork.invalidate(server);
        }
        super.onRemove(state, level, pos, next, moving);
    }
    @SuppressWarnings("deprecation")
    @Override public java.util.List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder context) {
        var entity = context.getOptionalParameter(Objects.requireNonNull(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY));
        if (entity instanceof MachineBlockEntity machine && (kind == ColonyContent.Kind.WAREHOUSE && machine.inventory.readOnly() || machine.facilityReadOnly())) {
            ItemStack preserved = new ItemStack(this); preserved.getOrCreateTag().put("BlockEntityTag", Objects.requireNonNull(machine.saveWithoutMetadata())); return java.util.List.of(preserved);
        }
        return super.getDrops(state, context);
    }
}
