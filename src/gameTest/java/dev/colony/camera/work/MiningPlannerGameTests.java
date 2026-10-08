package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class MiningPlannerGameTests {
    private record Scene(@Nonnull FakePlayer player, @Nonnull WorkerEntity worker, @Nonnull ColonyWorkManager manager) {}

    @GameTest(template = "construction", timeoutTicks = 200, batch = "mining_planner")
    public static void oneStationContinuouslyCoversSeveralBlocks(GameTestHelper helper) {
        Scene scene = scene(helper);
        BlockPos start = Objects.requireNonNull(scene.worker().blockPosition());
        List<BlockPos> positions = Objects.requireNonNull(List.of(new BlockPos(3, 1, 3), new BlockPos(3, 2, 3), new BlockPos(4, 1, 3)));
        for (BlockPos relative : positions) helper.setBlock(Objects.requireNonNull(relative), Objects.requireNonNull(Blocks.DIRT));
        scene.worker().setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_SHOVEL)));
        scene.manager().mine(scene.player(), Objects.requireNonNull(positions.stream().map(pos -> helper.absolutePos(Objects.requireNonNull(pos))).toList()));
        ColonyJob job = onlyJob(scene); Set<Integer> worked = new HashSet<>();
        helper.onEachTick(() -> {
            WorkTask task = scene.worker().workTask;
            if (task != null && task.job.equals(job.id)) {
                helper.assertTrue(start.equals(task.targetStand), "Planner moved away from a station with available visible work");
                worked.add(task.targetIndex);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished() && worked.size() == 3 && scene.worker().minedBlocks() == 3,
                    "One station did not cover all three blocks: " + worked + ", " + job.reason);
            helper.assertTrue(scene.worker().distanceToSqr(start.getX() + 0.5, start.getY(), start.getZ() + 0.5) < 0.01,
                    "Station batching caused unnecessary movement");
            scene.worker().discard();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_planner")
    public static void realPartialProgressSurvivesAnotherWorkerAndSameTickCannotRepeat(GameTestHelper helper) {
        Scene scene = scene(helper); ColonyJob job = stoneJob(helper, scene);
        var level = Objects.requireNonNull(helper.getLevel()); long time = level.getGameTime(); ReservationLedger ledger = new ReservationLedger();
        WorkTask first = Objects.requireNonNull(MiningService.assign(level, scene.worker(), job, Objects.requireNonNull(Set.of()), ledger, time));
        helper.assertTrue(!MiningService.execute(level, scene.worker(), job, first, ledger, time), "Single stone tick unexpectedly completed");
        int partial = job.miningProgress().get(0);
        helper.assertTrue(partial > 0 && partial < 10_000, "Real breaking did not produce partial work");
        MiningService.execute(level, scene.worker(), job, first, ledger, time);
        helper.assertTrue(job.miningProgress().get(0) == partial, "Same tick advanced the block twice");
        scene.worker().discard(); WorkerEntity replacement = worker(helper, scene.player());
        WorkTask next = Objects.requireNonNull(MiningService.assign(level, replacement, job, Objects.requireNonNull(Set.of()), new ReservationLedger(), time));
        helper.assertTrue(next.progress == partial, "New worker lost another worker's real partial progress");
        MiningService.execute(level, replacement, job, next, new ReservationLedger(), time);
        helper.assertTrue(job.miningProgress().get(0) == partial, "Worker handover advanced the same tick twice");
        MiningService.execute(level, replacement, job, next, new ReservationLedger(), time + 1);
        helper.assertTrue(job.miningProgress().get(0) > partial && replacement.minedBlocks() == 0 && replacement.getOffhandItem().getDamageValue() == 0,
                "Handover lost progress or charged tool/XP before breaking");
        replacement.discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_planner")
    public static void pauseAndResumeKeepsRealPartialWork(GameTestHelper helper) {
        Scene scene = scene(helper); ColonyJob job = stoneJob(helper, scene);
        var level = Objects.requireNonNull(helper.getLevel()); long time = level.getGameTime(); ReservationLedger ledger = new ReservationLedger();
        WorkTask first = Objects.requireNonNull(MiningService.assign(level, scene.worker(), job, Objects.requireNonNull(Set.of()), ledger, time));
        scene.worker().workTask = first; MiningService.execute(level, scene.worker(), job, first, ledger, time);
        int partial = job.miningProgress().get(0);
        scene.manager().update(Objects.requireNonNull(scene.player().getUUID()), job.id, "pause", 0);
        scene.manager().tick(level, Objects.requireNonNull(List.of(scene.worker())), ledger);
        helper.assertTrue(scene.worker().workTask == null && job.miningProgress().get(0) == partial, "Pausing discarded block progress");
        helper.assertTrue(MiningService.assign(level, scene.worker(), job, Objects.requireNonNull(Set.of()), ledger, time) == null, "Paused task was dispatched");
        scene.manager().update(Objects.requireNonNull(scene.player().getUUID()), job.id, "pause", 0);
        WorkTask resumed = Objects.requireNonNull(MiningService.assign(level, scene.worker(), job, Objects.requireNonNull(Set.of()), ledger, time));
        helper.assertTrue(partial > 0 && resumed.progress == partial, "Resume reset real partial work");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_planner")
    public static void newStationAndHigherPriorityJobKeepEarlierBlockProgress(GameTestHelper helper) {
        Scene scene = scene(helper); ColonyJob lower = stoneJob(helper, scene);
        var level = Objects.requireNonNull(helper.getLevel()); long time = level.getGameTime(); ReservationLedger ledger = new ReservationLedger();
        WorkTask first = Objects.requireNonNull(MiningService.assign(level, scene.worker(), lower, Objects.requireNonNull(Set.of()), ledger, time));
        scene.worker().workTask = first; MiningService.execute(level, scene.worker(), lower, first, ledger, time);
        int partial = lower.miningProgress().get(0);
        BlockPos otherStand = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 4)));
        WorkTask relocated = Objects.requireNonNull(MiningService.task(level, scene.worker(), lower, 0, otherStand, first, ledger, time));
        helper.assertTrue(partial > 0 && relocated.progress == partial && !relocated.stand.equals(first.stand), "Changing stations reset partial work");
        BlockPos dirt = new BlockPos(2, 1, 5); helper.setBlock(dirt, Objects.requireNonNull(Blocks.DIRT));
        scene.manager().mine(scene.player(), Objects.requireNonNull(List.of(helper.absolutePos(dirt))));
        ColonyJob higher = Objects.requireNonNull(scene.manager().jobs(Objects.requireNonNull(scene.player().getUUID())).stream()
                .filter(job -> !Objects.requireNonNull(job).id.equals(lower.id)).findFirst().orElseThrow());
        scene.manager().update(Objects.requireNonNull(scene.player().getUUID()), higher.id, "priority", 9);
        scene.manager().dispatchJob(level, higher, Objects.requireNonNull(List.of(scene.worker())), new HashSet<>(), new HashSet<>(), ledger, time);
        WorkTask preempting = scene.worker().workTask;
        helper.assertTrue(preempting != null && preempting.job.equals(higher.id) && lower.miningProgress().get(0) == partial,
                "Higher-priority work lost the earlier task's partial progress");
        scene.manager().update(Objects.requireNonNull(scene.player().getUUID()), higher.id, "pause", 0);
        WorkTask resumed = Objects.requireNonNull(MiningService.assign(level, scene.worker(), lower, Objects.requireNonNull(Set.of()), ledger, time));
        helper.assertTrue(resumed.progress == partial, "Returning from higher-priority work reset progress");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_planner")
    public static void saveReloadPreservesProgressAndOldSchemasMigrate(GameTestHelper helper) {
        Scene scene = scene(helper); ColonyJob job = stoneJob(helper, scene);
        var level = Objects.requireNonNull(helper.getLevel()); long time = level.getGameTime(); ReservationLedger ledger = new ReservationLedger();
        WorkTask first = Objects.requireNonNull(MiningService.assign(level, scene.worker(), job, Objects.requireNonNull(Set.of()), ledger, time));
        MiningService.execute(level, scene.worker(), job, first, ledger, time);
        int partial = job.miningProgress().get(0); CompoundTag saved = scene.manager().save(new CompoundTag());
        ColonyWorkManager restored = ColonyWorkManager.load(saved);
        ColonyJob copy = Objects.requireNonNull(restored.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        helper.assertTrue(!restored.readOnly() && partial > 0 && copy.miningProgress().get(0) == partial && restored.save(new CompoundTag()).equals(saved),
                "Current work save lost partial progress or changed the mining contract");
        for (int version : new int[]{1, 2}) {
            CompoundTag legacy = Objects.requireNonNull(saved.copy()); legacy.putInt("schemaVersion", version);
            // Only this mining fixture existed in these schemas. Later batches may
            // have persisted schema-five surface sweeps in the shared test world.
            var entries = new net.minecraft.nbt.ListTag(); CompoundTag older = Objects.requireNonNull(jobTag(saved, job.id).copy());
            older.remove("miningProgress"); entries.add(older); legacy.put("jobs", entries);
            ColonyWorkManager migrated = ColonyWorkManager.load(legacy);
            ColonyJob old = Objects.requireNonNull(migrated.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
            helper.assertTrue(!migrated.readOnly() && old.targets.equals(job.targets) && old.miningProgress().get(0) == 0
                    && migrated.save(new CompoundTag()).getInt("schemaVersion") == 6, "Legacy migration changed targets or invented partial work");
        }
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_planner")
    public static void malformedProgressKeepsOriginalSaveReadOnly(GameTestHelper helper) {
        Scene scene = scene(helper); ColonyJob job = stoneJob(helper, scene);
        CompoundTag saved = scene.manager().save(new CompoundTag());
        for (int[] invalid : new int[][]{{0, 1, 0, 2}, {1, 100}, {0, 10_001}, {0}}) {
            CompoundTag bad = Objects.requireNonNull(saved.copy()); jobTag(bad, job.id).putIntArray("miningProgress", Objects.requireNonNull(invalid));
            ColonyWorkManager restored = ColonyWorkManager.load(bad);
            helper.assertTrue(restored.readOnly() && restored.save(new CompoundTag()).equals(bad), "Invalid progress data was silently rewritten");
        }
        CompoundTag missing = Objects.requireNonNull(saved.copy()); jobTag(missing, job.id).remove("miningProgress");
        helper.assertTrue(ColonyWorkManager.load(missing).readOnly(), "Missing current-schema progress was silently treated as legacy");
        CompoundTag completed = Objects.requireNonNull(saved.copy()); CompoundTag entry = jobTag(completed, job.id);
        entry.putLongArray("completed", new long[]{1}); entry.putInt("done", 1); entry.putIntArray("miningProgress", new int[]{0, 100});
        ColonyWorkManager rejected = ColonyWorkManager.load(completed);
        helper.assertTrue(rejected.readOnly() && rejected.save(new CompoundTag()).equals(completed), "Completed target with partial work was rewritten");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_planner")
    public static void airHasZeroCostAndIsExcludedFromAcceptedContract(GameTestHelper helper) {
        Scene scene = scene(helper); BlockPos stone = new BlockPos(3, 1, 3), air = new BlockPos(4, 1, 3);
        helper.setBlock(stone, Objects.requireNonNull(Blocks.STONE));
        scene.manager().mine(scene.player(), Objects.requireNonNull(List.of(helper.absolutePos(air), helper.absolutePos(stone))));
        ColonyJob job = onlyJob(scene);
        helper.assertTrue(job.total() == 1 && job.targets.equals(List.of(helper.absolutePos(stone))) && job.done == 0, "Air became simulated mining work");
        var level = Objects.requireNonNull(helper.getLevel()); BlockPos pos = Objects.requireNonNull(helper.absolutePos(air));
        helper.assertTrue(MiningService.difficulty(level, pos, Objects.requireNonNull(Blocks.AIR.defaultBlockState())) == 0
                && MiningService.difficulty(level, pos, Objects.requireNonNull(Blocks.OAK_LEAVES.defaultBlockState())) == 1
                && MiningService.difficulty(level, pos, Objects.requireNonNull(Blocks.DIRT.defaultBlockState())) == 2
                && MiningService.difficulty(level, pos, Objects.requireNonNull(Blocks.OAK_LOG.defaultBlockState())) == 2
                && MiningService.difficulty(level, pos, Objects.requireNonNull(Blocks.STONE.defaultBlockState())) == 3
                && MiningService.difficulty(level, pos, Objects.requireNonNull(Blocks.DEEPSLATE.defaultBlockState())) == 4, "Design cost mapping drifted");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_planner_water")
    public static void submergedStationsNeverUseDryLandPeeling(GameTestHelper helper) {
        Scene scene = scene(helper); ColonyJob job = stoneJob(helper, scene);
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) for (int y = 1; y <= 2; y++) {
            if (x != 3 || y != 1 || z != 3) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.WATER));
        }
        var level = Objects.requireNonNull(helper.getLevel());
        helper.assertTrue(MiningService.assign(level, scene.worker(), job, Objects.requireNonNull(Set.of()), new ReservationLedger(), level.getGameTime()) == null
                && !job.finished() && helper.getBlockState(new BlockPos(3, 1, 3)).is(Objects.requireNonNull(Blocks.STONE)),
                "Underwater planner invented a dry or submerged station");
        // Remove this deliberately flooded fixture before fluid ticks can affect later batches.
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) for (int y = 1; y <= 2; y++) {
            if (x != 3 || y != 1 || z != 3) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        scene.worker().discard(); helper.succeed();
    }

    @Nonnull private static CompoundTag jobTag(CompoundTag root, UUID id) {
        var jobs = root.getList("jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < jobs.size(); i++) {
            CompoundTag tag = Objects.requireNonNull(jobs.getCompound(i)); if (tag.hasUUID("id") && id.equals(tag.getUUID("id"))) return tag;
        }
        throw new IllegalArgumentException("Fixture job missing");
    }

    @Nonnull private static ColonyJob stoneJob(GameTestHelper helper, Scene scene) {
        BlockPos relative = new BlockPos(3, 1, 3); helper.setBlock(relative, Objects.requireNonNull(Blocks.STONE));
        scene.manager().mine(scene.player(), Objects.requireNonNull(List.of(helper.absolutePos(relative)))); return onlyJob(scene);
    }
    @Nonnull private static ColonyJob onlyJob(Scene scene) { return Objects.requireNonNull(scene.manager().jobs(Objects.requireNonNull(scene.player().getUUID())).get(0)); }
    @Nonnull private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y < 7; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        var level = Objects.requireNonNull(helper.getLevel());
        AABB bounds = new AABB(Objects.requireNonNull(helper.absolutePos(new BlockPos(0, 0, 0))), Objects.requireNonNull(helper.absolutePos(new BlockPos(12, 7, 12))));
        level.getEntitiesOfClass(WorkerEntity.class, bounds).forEach(entity -> Objects.requireNonNull(entity).discard());
        level.getEntitiesOfClass(ItemEntity.class, bounds).forEach(entity -> Objects.requireNonNull(entity).discard());
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(Objects.requireNonNull(UUID.randomUUID()), "MiningPlanner")));
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 3))); player.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        return new Scene(player, worker(helper, player), ColonyWorkManager.get(level));
    }
    @Nonnull private static WorkerEntity worker(GameTestHelper helper, FakePlayer player) {
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel())));
        worker.owner(Objects.requireNonNull(player.getUUID())); BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 3)));
        worker.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0); worker.setOnGround(true);
        worker.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        helper.getLevel().addFreshEntity(worker); return worker;
    }
}
