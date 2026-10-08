package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.tech.TechProgress;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** Same physical fixture before and after the planner change; no simulated digging or movement. */
@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class MiningEfficiencyGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger(MiningEfficiencyGameTests.class);
    // Unchanged 1.6.2 timed out here at 192/256; this is an observation bound, not a completion time.
    private static final long BASELINE_OBSERVATION_TICKS = 90_000;

    @GameTest(template = "construction", timeoutTicks = 12000, batch = "mining_efficiency")
    public static void threeMinersExcavateEightByFourByEightStone(GameTestHelper helper) {
        var level = Objects.requireNonNull(helper.getLevel());
        AABB bounds = new AABB(Objects.requireNonNull(helper.absolutePos(new BlockPos(0, 0, 0))),
                Objects.requireNonNull(helper.absolutePos(new BlockPos(12, 7, 12))));
        level.getEntitiesOfClass(WorkerEntity.class, bounds).forEach(worker -> Objects.requireNonNull(worker).discard());
        level.getEntitiesOfClass(ItemEntity.class, bounds).forEach(item -> Objects.requireNonNull(item).discard());
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y < 7; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(level,
                new GameProfile(Objects.requireNonNull(UUID.randomUUID()), "MiningBenchmark")));
        BlockPos origin = Objects.requireNonNull(helper.absolutePos(new BlockPos(6, 1, 11)));
        player.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        helper.assertTrue(TechProgress.get(level).advance(Objects.requireNonNull(player.getUUID()), 0, () -> {}),
                "Benchmark stone-tool research fixture failed");
        var workers = new ArrayList<WorkerEntity>();
        for (int x : new int[]{3, 6, 9}) {
            WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(level));
            worker.owner(Objects.requireNonNull(player.getUUID()));
            BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(x, 1, 11)));
            worker.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
            worker.setOnGround(true);
            worker.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.STONE_PICKAXE)));
            level.addFreshEntity(worker); workers.add(worker);
        }
        var targets = new ArrayList<BlockPos>();
        for (int x = 2; x < 10; x++) for (int z = 2; z < 10; z++) for (int y = 1; y <= 4; y++) {
            BlockPos relative = new BlockPos(x, y, z);
            helper.setBlock(relative, Objects.requireNonNull(Blocks.STONE));
            targets.add(Objects.requireNonNull(helper.absolutePos(relative)));
        }
        ColonyWorkManager manager = ColonyWorkManager.get(level);
        manager.mine(player, Objects.requireNonNull(targets));
        ColonyJob job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(player.getUUID())).get(0));
        long started = level.getGameTime();
        var previous = new ArrayList<Vec3>();
        for (WorkerEntity worker : workers) previous.add(Objects.requireNonNull(worker.position()));
        double[] distance = {0};
        helper.onEachTick(() -> {
            var claims = new HashSet<Integer>();
            for (int i = 0; i < workers.size(); i++) {
                WorkerEntity worker = Objects.requireNonNull(workers.get(i));
                Vec3 position = Objects.requireNonNull(worker.position());
                distance[0] += position.distanceTo(Objects.requireNonNull(previous.set(i, position)));
                WorkTask task = worker.workTask;
                if (task != null && task.job.equals(job.id)) helper.assertTrue(claims.add(task.targetIndex),
                        "Two miners simultaneously claimed one benchmark block");
            }
            if ((level.getGameTime() - started) % 1000 == 0) LOGGER.info("MINING_BENCHMARK_PROGRESS ticks={} done={} reason={} miners={}",
                    level.getGameTime() - started, job.done, job.reason, workers.stream().map(worker -> {
                        WorkerEntity miner = Objects.requireNonNull(worker); WorkTask task = miner.workTask;
                        return miner.position() + ":" + (task == null ? "idle" : task.targetIndex + "/" + task.progress);
                    }).toList());
        });
        helper.succeedWhen(() -> {
            long ticks = level.getGameTime() - started;
            helper.assertTrue(job.finished() && job.done == 256, "Benchmark did not finish: " + job.done + ", " + job.reason);
            helper.assertTrue(targets.stream().allMatch(pos -> level.getBlockState(Objects.requireNonNull(pos)).isAir()),
                    "Completed benchmark retained a selected block");
            helper.assertTrue(workers.stream().mapToInt(worker -> Objects.requireNonNull(worker).minedBlocks()).sum() == 256, "Benchmark XP did not conserve blocks");
            helper.assertTrue(workers.stream().mapToInt(worker -> Objects.requireNonNull(worker).getOffhandItem().getDamageValue()).sum() == 256,
                    "Benchmark tool wear did not conserve blocks");
            int drops = level.getEntitiesOfClass(ItemEntity.class, Objects.requireNonNull(bounds.inflate(2))).stream()
                    .filter(item -> Objects.requireNonNull(item).getItem().is(Objects.requireNonNull(Items.COBBLESTONE)))
                    .mapToInt(item -> Objects.requireNonNull(item).getItem().getCount()).sum();
            helper.assertTrue(drops == 256, "Benchmark real drops did not conserve blocks: " + drops);
            LOGGER.info("MINING_BENCHMARK_RESULT blocks=256 workers=3 ticks={} distance={} baselineObservationTicks={} baselineCompleted=false",
                    ticks, distance[0], BASELINE_OBSERVATION_TICKS);
            helper.assertTrue(ticks * 100 <= BASELINE_OBSERVATION_TICKS * 60,
                    "Mining exceeded 60% of the observed baseline lower bound: new=" + ticks);
            workers.forEach(worker -> Objects.requireNonNull(worker).discard());
        });
    }
}
