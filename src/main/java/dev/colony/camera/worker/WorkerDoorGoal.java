package dev.colony.camera.worker;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.resources.GroundResources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.eventbus.api.Event;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

/** Vanilla doorway traversal, with the colony owner's actual interaction permissions on both open and close. */
@ParametersAreNonnullByDefault
public final class WorkerDoorGoal extends OpenDoorGoal {
    @Nonnull private final WorkerEntity worker;

    public WorkerDoorGoal(WorkerEntity worker) {
        super(worker, true);
        this.worker = worker;
    }

    @Override public boolean canUse() {
        return worker.level() instanceof ServerLevel && !worker.stateReadOnly() && worker.owner() != null && super.canUse();
    }

    @Override protected void setOpen(boolean open) {
        UUID owner = worker.owner();
        if (owner == null || worker.stateReadOnly() || !(worker.level() instanceof ServerLevel level)) return;
        BlockPos pos = Objects.requireNonNull(doorPos);
        if (!GroundResources.loaded(level, pos)) return;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock door)
                || !DoorBlock.isWoodenDoor(level, pos) || state.getValue(Objects.requireNonNull(DoorBlock.OPEN)) == open) return;
        var actor = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(owner, "ColonyWorker")));
        actor.moveTo(worker.getX(), worker.getY(), worker.getZ());
        if (!level.mayInteract(actor, pos)) return;
        var event = ForgeHooks.onRightClickBlock(actor, InteractionHand.MAIN_HAND, pos,
                new BlockHitResult(GroundResources.center(pos), Direction.UP, pos, false));
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY) return;
        door.setOpen(actor, level, state, pos, open);
    }
}
