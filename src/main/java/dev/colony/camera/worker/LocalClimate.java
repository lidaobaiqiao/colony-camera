package dev.colony.camera.worker;

import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.tech.TechProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

/** Bounded local exposure, not a world-wide heat diffusion simulation. */
@ParametersAreNonnullByDefault
public final class LocalClimate {
    private LocalClimate() {}
    public static double sample(ServerLevel level, WorkerEntity worker) {
        BlockPos origin = Objects.requireNonNull(worker.blockPosition());
        if (!GroundResources.loaded(level, origin)) return worker.needs().temperature();
        double ambient = level.dimensionType().ultraWarm() ? 45 : 8 + level.getBiome(origin).value().getBaseTemperature() * 20;
        boolean exposed = level.canSeeSky(origin);
        if (exposed && !level.dimensionType().hasFixedTime()) {
            if (level.isNight()) ambient -= 6;
            if (level.isRaining()) ambient -= 4;
        }
        if (worker.isInWater()) ambient -= 5;
        double heat = 0;
        for (BlockPos pos : BlockPos.betweenClosed(Objects.requireNonNull(origin.offset(-4, -2, -4)), Objects.requireNonNull(origin.offset(4, 2, 4)))) {
            if (!GroundResources.loaded(level, Objects.requireNonNull(pos))) continue;
            var state = level.getBlockState(pos);
            double power = state.is(Objects.requireNonNull(Blocks.LAVA)) ? 24
                    : (state.is(Objects.requireNonNull(Blocks.CAMPFIRE)) || state.is(Objects.requireNonNull(Blocks.SOUL_CAMPFIRE)))
                    && state.getValue(Objects.requireNonNull(BlockStateProperties.LIT)) ? 14
                    : (state.is(Objects.requireNonNull(Blocks.FURNACE)) || state.is(Objects.requireNonNull(Blocks.BLAST_FURNACE)) || state.is(Objects.requireNonNull(Blocks.SMOKER)))
                    && state.getValue(Objects.requireNonNull(BlockStateProperties.LIT)) ? 8 : 0;
            if (power == 0 || !GroundResources.searchAreaLoaded(level, worker) || !visible(level, worker, pos)) continue;
            heat = Math.max(heat, power / (1 + Math.sqrt(pos.distSqr(origin)) * 0.5));
        }
        ambient += heat;
        var owner = worker.owner(); var home = worker.home();
        if (owner != null && home != null && TechProgress.get(level).unlocked(owner, TechProgress.INSULATION)) {
            var room = Residences.home(level, worker);
            if (room != null && room.floor().contains(origin)) ambient = 20 + (ambient - 20) * 0.35;
        }
        return Math.max(-100, Math.min(200, ambient));
    }
    static boolean visible(ServerLevel level, WorkerEntity worker, BlockPos target) {
        var hit = level.clip(new net.minecraft.world.level.ClipContext(Objects.requireNonNull(worker.getEyePosition()), GroundResources.center(target),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, worker));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || target.equals(hit.getBlockPos());
    }
}
