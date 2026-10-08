package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class MiningStabilityGameTests {
    private record Scene(@Nonnull FakePlayer player, @Nonnull WorkerEntity worker) {}

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_stability")
    // Replaces the 0019 scan-offset test: 0021 permits any ordering, but claims must be exclusive.
    public static void claimedBlockIsExcludedFromStationCoverage(GameTestHelper helper) {
        Scene scene = scene(helper);
        BlockPos lower = new BlockPos(3, 1, 3), upper = new BlockPos(3, 2, 3);
        helper.setBlock(lower, Objects.requireNonNull(Blocks.DIRT)); helper.setBlock(upper, Objects.requireNonNull(Blocks.DIRT));
        ColonyJob job = job(helper, scene, Objects.requireNonNull(List.of(lower, upper)));
        WorkTask first = Objects.requireNonNull(MiningService.assign(Objects.requireNonNull(helper.getLevel()), scene.worker(), job,
                Objects.requireNonNull(Set.of(helper.absolutePos(upper))), new ReservationLedger(), helper.getLevel().getGameTime()));
        helper.assertTrue(first.targetIndex == 0, "Claimed upper target was not left to its miner");
        WorkTask next = Objects.requireNonNull(MiningService.assign(Objects.requireNonNull(helper.getLevel()), scene.worker(), job,
                Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()));
        helper.assertTrue(next.targetIndex >= 0 && next.targetIndex < 2, "No unclaimed target was available to the station planner");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_stability")
    public static void dispatchKeepsTargetsExclusiveAndPartialWorkConserved(GameTestHelper helper) {
        Scene scene = scene(helper);
        BlockPos near = new BlockPos(3, 1, 3), far = new BlockPos(8, 1, 8);
        helper.setBlock(near, Objects.requireNonNull(Blocks.DIRT)); helper.setBlock(far, Objects.requireNonNull(Blocks.DIRT));
        ColonyJob job = job(helper, scene, Objects.requireNonNull(List.of(near, far)));
        WorkerEntity active = worker(helper, scene.player());
        WorkTask previous = Objects.requireNonNull(MiningService.assign(Objects.requireNonNull(helper.getLevel()), active, job,
                Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()));
        previous.progress = 4_000; active.workTask = previous;
        ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel())).dispatchJob(Objects.requireNonNull(helper.getLevel()), job,
                Objects.requireNonNull(List.of(scene.worker(), active)), new HashSet<>(), new HashSet<>(), new ReservationLedger(), helper.getLevel().getGameTime());
        WorkTask retained = active.workTask;
        WorkTask extra = scene.worker().workTask;
        helper.assertTrue(extra != null && retained != null && extra.targetIndex != retained.targetIndex,
                "Two miners simultaneously claimed the same block");
        helper.assertTrue(job.miningProgress().get(previous.targetIndex) >= 4_000, "Block progress was lost during allocation");
        WorkTask continuing = retained != null && retained.targetIndex == previous.targetIndex ? retained : Objects.requireNonNull(extra);
        helper.assertTrue(continuing.targetIndex == previous.targetIndex && continuing.progress >= 4_000,
                "The assigned miner did not inherit the block's accumulated progress");
        active.discard(); scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_stability")
    // Replaces the 0019 cursor/budget test with the outcome: inaccessible sites cannot monopolize planning.
    public static void inaccessibleStationsDoNotStarveReachableWork(GameTestHelper helper) {
        Scene scene = scene(helper);
        for (int x = 3; x <= 7; x++) for (int z = 3; z <= 7; z++) {
            helper.setBlock(new BlockPos(x, 4, z), Objects.requireNonNull(Blocks.STONE));
        }
        BlockPos high = new BlockPos(5, 5, 5), low = new BlockPos(9, 1, 9);
        helper.setBlock(high, Objects.requireNonNull(Blocks.DIRT)); helper.setBlock(low, Objects.requireNonNull(Blocks.DIRT));
        ColonyJob job = job(helper, scene, Objects.requireNonNull(List.of(high, low)));
        WorkTask found = null;
        for (int attempt = 0; attempt < 32 && found == null; attempt++) {
            found = MiningService.assign(Objects.requireNonNull(helper.getLevel()), scene.worker(), job,
                    Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime());
        }
        helper.assertTrue(found != null && found.targetIndex == 1, "Inaccessible stations starved reachable work");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_stability")
    public static void repeatedRouteRequestsPreserveVanillaStuckDetection(GameTestHelper helper) {
        Scene scene = scene(helper);
        WorkerEntity worker = scene.worker(); worker.setNoAi(true); worker.setSpeed(0.3F);
        BlockPos target = Objects.requireNonNull(helper.absolutePos(new BlockPos(9, 1, 3)));
        var planned = Objects.requireNonNull(GroundResources.path(Objects.requireNonNull(helper.getLevel()), worker, target));
        helper.assertTrue(GroundResources.follow(Objects.requireNonNull(helper.getLevel()), worker, planned), "Initial route did not start");
        boolean detected = false;
        for (int tick = 0; tick < 130 && !detected; tick++) {
            GroundResources.follow(Objects.requireNonNull(helper.getLevel()), worker, planned);
            worker.getNavigation().tick();
            detected = worker.getNavigation().isStuck() && worker.getNavigation().isDone();
        }
        helper.assertTrue(detected, "Submitting the same route indefinitely reset the vanilla stuck timer");
        worker.discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_stability")
    public static void finalWaypointMustReachTheWorkCellCenter(GameTestHelper helper) {
        Scene scene = scene(helper);
        WorkerEntity worker = scene.worker(); worker.setNoAi(true);
        BlockPos target = Objects.requireNonNull(helper.absolutePos(new BlockPos(9, 1, 3)));
        var planned = Objects.requireNonNull(GroundResources.path(Objects.requireNonNull(helper.getLevel()), worker, target));
        helper.assertTrue(GroundResources.follow(Objects.requireNonNull(helper.getLevel()), worker, planned), "Initial route did not start");
        var navigation = worker.getNavigation();
        var route = Objects.requireNonNull(navigation.getPath());
        route.setNextNodeIndex(route.getNodeCount() - 1);
        worker.setPos(target.getX() + 0.9, target.getY(), target.getZ() + 0.5);
        navigation.tick();
        helper.assertTrue(!navigation.isDone(), "Route ended short of the work cell and would restart at its previous node");
        helper.assertTrue(target.equals(navigation.getTargetPos())
                && Math.abs(worker.getMoveControl().getWantedX() - target.getX() - 0.5) < 0.001,
                "Final movement no longer points at the work cell center");
        worker.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        navigation.tick();
        helper.assertTrue(navigation.isDone(), "Final route did not complete after reaching the work cell center");
        worker.discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 500, batch = "mining_stability")
    public static void lowerBlockUnderUnselectedRoofFindsActualLineOfSight(GameTestHelper helper) {
        Scene scene = scene(helper);
        BlockPos lower = new BlockPos(3, 1, 3), roof = new BlockPos(3, 2, 3);
        helper.setBlock(lower, Objects.requireNonNull(Blocks.DIRT)); helper.setBlock(roof, Objects.requireNonNull(Blocks.STONE));
        ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        manager.mine(scene.player(), Objects.requireNonNull(List.of(helper.absolutePos(lower))));
        ColonyJob job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished() && helper.getBlockState(lower).isAir(), "Covered block stalled: " + job.reason + ", " + scene.worker().position());
            helper.assertTrue(helper.getBlockState(roof).is(Objects.requireNonNull(Blocks.STONE)) && scene.worker().minedBlocks() == 1,
                    "Miner destroyed an unselected roof or repeated XP");
            scene.worker().discard();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_stability")
    public static void miningOwnFootingMustWalkAsideBeforeBreaking(GameTestHelper helper) {
        Scene scene = scene(helper);
        BlockPos floor = new BlockPos(2, 0, 3); helper.setBlock(floor, Objects.requireNonNull(Blocks.DIRT));
        ColonyJob job = job(helper, scene, Objects.requireNonNull(List.of(floor)));
        WorkTask task = Objects.requireNonNull(MiningService.assign(Objects.requireNonNull(helper.getLevel()), scene.worker(), job,
                Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()));
        task.progress = 10_000;
        boolean ended = MiningService.execute(Objects.requireNonNull(helper.getLevel()), scene.worker(), job, task,
                new ReservationLedger(), helper.getLevel().getGameTime());
        helper.assertTrue(!ended && !job.finished() && helper.getBlockState(floor).is(Objects.requireNonNull(Blocks.DIRT)),
                "Miner broke its own support block instead of stepping to the assigned stand");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 900, batch = "mining_stability")
    public static void twoMinersCompleteVolumeWithoutRepeatedProgressLoss(GameTestHelper helper) {
        Scene scene = scene(helper); WorkerEntity second = worker(helper, scene.player());
        scene.worker().setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        second.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        BlockPos secondStart = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 5)));
        second.moveTo(secondStart.getX() + 0.5, secondStart.getY(), secondStart.getZ() + 0.5, 0, 0); second.setOnGround(true);
        var positions = new ArrayList<BlockPos>();
        for (int x = 5; x <= 8; x++) for (int z = 5; z <= 6; z++) for (int y = 1; y <= 2; y++) {
            BlockPos pos = new BlockPos(x, y, z); helper.setBlock(pos, Objects.requireNonNull(Blocks.STONE));
            positions.add(Objects.requireNonNull(helper.absolutePos(pos)));
        }
        ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        manager.mine(scene.player(), Objects.requireNonNull(positions));
        ColonyJob job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished() && job.done == positions.size(), "Two miners stalled: " + job.reason + ", progress=" + job.done);
            helper.assertTrue(scene.worker().minedBlocks() + second.minedBlocks() == positions.size(), "Completed mining duplicated or lost experience");
            helper.assertTrue(scene.worker().getOffhandItem().getDamageValue() + second.getOffhandItem().getDamageValue() == positions.size(),
                    "Volume mining duplicated or lost real tool wear");
            scene.worker().discard(); second.discard();
        });
    }

    @Nonnull private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y < 7; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        var bounds = new AABB(Objects.requireNonNull(helper.absolutePos(new BlockPos(0, 0, 0))), Objects.requireNonNull(helper.absolutePos(new BlockPos(12, 7, 12))));
        helper.getLevel().getEntitiesOfClass(WorkerEntity.class, bounds).forEach(entity -> Objects.requireNonNull(entity).discard());
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds).forEach(entity -> Objects.requireNonNull(entity).discard());
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(Objects.requireNonNull(helper.getLevel()),
                new GameProfile(Objects.requireNonNull(UUID.randomUUID()), "MineStability")));
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 3)));
        player.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        return new Scene(player, worker(helper, player));
    }

    @Nonnull private static WorkerEntity worker(GameTestHelper helper, FakePlayer player) {
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel())));
        worker.owner(Objects.requireNonNull(player.getUUID()));
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 3)));
        worker.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0); worker.setOnGround(true);
        worker.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_SHOVEL)));
        helper.getLevel().addFreshEntity(worker); return worker;
    }

    @Nonnull private static ColonyJob job(GameTestHelper helper, Scene scene, List<BlockPos> relative) {
        var job = new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(scene.player().getUUID()), "MineStability", ColonyJob.Kind.MINE, 5, 0);
        var targets = new ArrayList<BlockPos>(); var states = new ArrayList<String>();
        for (BlockPos position : relative) {
            BlockPos pos = Objects.requireNonNull(helper.absolutePos(Objects.requireNonNull(position))); targets.add(pos);
            states.add(Objects.requireNonNull(BlockStateParser.serialize(Objects.requireNonNull(helper.getLevel().getBlockState(pos)))));
        }
        job.targets = Objects.requireNonNull(List.copyOf(targets)); job.expectedStates = Objects.requireNonNull(List.copyOf(states)); return job;
    }
}
