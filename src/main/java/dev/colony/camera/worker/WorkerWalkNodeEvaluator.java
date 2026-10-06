package dev.colony.camera.worker;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import javax.annotation.Nonnull;
import java.util.Objects;

/** Ground routes can cross water at foot height, without amphibious mobs' preference for deep water. */
public final class WorkerWalkNodeEvaluator extends WalkNodeEvaluator {
    @Override protected boolean isAmphibious() { return true; }
    @Override @Nonnull public Node getStart() {
        return mob.isInWater() ? Objects.requireNonNull(getStartNode(new BlockPos(Mth.floor(mob.getX()),
                Mth.floor(mob.getY() + 0.5), Mth.floor(mob.getZ())))) : Objects.requireNonNull(super.getStart());
    }
}
