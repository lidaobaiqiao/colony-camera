package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEffects;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class SweepRecoveryGameTests {
    record Scene(@Nonnull FakePlayer player, @Nonnull WorkerEntity worker, @Nonnull MachineBlockEntity warehouse) {}

    @GameTest(template = "construction", timeoutTicks = 20, batch = "sweep_recovery_checks")
    public static void aReachableWarehouseAcceptsCargoWithoutAnExactCellCenter(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        ColonyJob job = carriedJob(helper, scene);
        ItemStack named = Objects.requireNonNull(scene.worker().getMainHandItem().copy());
        BlockPos stand = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 3)));
        scene.worker().moveTo(stand.getX() + .82, stand.getY(), stand.getZ() + .5);
        helper.assertTrue(scene.worker().distanceToSqr(GroundResources.bottom(stand)) > .01
                && StorageResources.canTake(level, scene.worker(), Objects.requireNonNull(scene.warehouse().getBlockPos())), "Fixture was centered or could not physically reach the warehouse");
        WorkTask task = new WorkTask(job.id, stand, level.getGameTime() + 1200);
        task.destination = Objects.requireNonNull(scene.warehouse().getBlockPos());
        task.actionProgress = WorkerEffects.haulTicks(scene.worker().state()) - 1;
        helper.assertTrue(SweepService.execute(level, scene.worker(), job, task, new ReservationLedger(), level.getGameTime())
                && scene.worker().cargoCount() == 0 && scene.worker().cargoTask() == null
                && StorageAccess.at(level, Objects.requireNonNull(task.destination), job.owner).count(named) == 13,
                "Physically reachable off-center cargo worker waited for a cell center instead of depositing");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "sweep_recovery_checks")
    public static void redispatchKeepsTheReachableStandAndDeliveryAlreadyChosen(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        helper.setBlock(new BlockPos(2, 1, 4), Objects.requireNonNull(Blocks.AIR));
        helper.setBlock(new BlockPos(5, 1, 5), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        MachineBlockEntity warehouse = Objects.requireNonNull((MachineBlockEntity) helper.getBlockEntity(new BlockPos(5, 1, 5)));
        warehouse.owner(Objects.requireNonNull(scene.player().getUUID()));
        ColonyJob job = carriedJob(helper, scene); ReservationLedger ledger = new ReservationLedger();
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(5, 1, 2)));
        scene.worker().moveTo(start.getX() + .5, start.getY(), start.getZ() + .5);
        WorkTask first = Objects.requireNonNull(SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime()));
        scene.worker().workTask = first;
        BlockPos later = Objects.requireNonNull(helper.absolutePos(new BlockPos(8, 1, 5)));
        scene.worker().moveTo(later.getX() + .5, later.getY(), later.getZ() + .5);
        helper.assertTrue(GroundResources.path(level, scene.worker(), first.stand) != null, "Original delivery stand ceased to be reachable");
        WorkTask next = Objects.requireNonNull(SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime()));
        helper.assertTrue(next.stand.equals(first.stand) && Objects.equals(next.destination, first.destination),
                "Redispatch replaced a valid delivery stand merely because another was now nearer");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "sweep_known_destination")
    public static void anAlreadyChosenWarehouseIsRetainedBeyondFreshSearchRange(GameTestHelper helper) {
        Scene scene = scene(helper); corridor(helper);
        var level = Objects.requireNonNull(helper.getLevel()); ColonyJob job = carriedJob(helper, scene);
        ReservationLedger ledger = new ReservationLedger();
        WorkTask first = Objects.requireNonNull(SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime()));
        scene.worker().workTask = first;
        BlockPos far = Objects.requireNonNull(helper.absolutePos(new BlockPos(30, 1, 3)));
        scene.worker().moveTo(far.getX() + .5, far.getY(), far.getZ() + .5);
        helper.assertTrue(!StorageResources.scan(level, scene.worker()).contains(first.destination)
                && GroundResources.path(level, scene.worker(), first.stand) != null, "Fixture did not leave fresh search range while retaining a real return route");
        WorkTask next = SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime());
        helper.assertTrue(next != null && Objects.equals(next.destination, first.destination) && next.stand.equals(first.stand)
                && scene.worker().cargoCount() == 13, "Worker forgot its known reachable warehouse as soon as it left local search range");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "sweep_recovery_checks")
    public static void retainedDeliveriesRecheckObstaclesAndOwnershipWithoutLosingCargo(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        ColonyJob job = carriedJob(helper, scene); ReservationLedger ledger = new ReservationLedger();
        WorkTask first = Objects.requireNonNull(SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime()));
        scene.worker().workTask = first;
        level.setBlock(first.stand, Objects.requireNonNull(Blocks.STONE.defaultBlockState()), 3);
        helper.assertTrue(level.getBlockState(first.stand).is(Objects.requireNonNull(Blocks.STONE)), "Obstacle fixture did not block the actual world stand");
        BlockPos safe = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 1, 3)));
        scene.worker().moveTo(safe.getX() + .5, safe.getY(), safe.getZ() + .5);
        WorkTask replanned = Objects.requireNonNull(SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime()));
        helper.assertTrue(!replanned.stand.equals(first.stand) && scene.worker().cargoCount() == 13,
                "Blocked delivery stand was retained or cargo was lost: old=" + first.stand + ", next=" + replanned.stand
                        + ", oldBlock=" + level.getBlockState(first.stand) + ", cargo=" + scene.worker().cargoCount());
        scene.worker().workTask = replanned; scene.warehouse().owner(Objects.requireNonNull(UUID.randomUUID()));
        helper.assertTrue(SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime()) == null
                && scene.worker().cargoCount() == 13 && job.id.equals(scene.worker().cargoTask())
                && scene.warehouse().inventory.getStackInSlot(0).isEmpty(), "Changed warehouse ownership allowed a stale delivery or destroyed cargo");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "sweep_pickup_continuity")
    public static void aNearerNewDropDoesNotRestartAnAlreadyReservedPickup(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        ColonyJob job = carriedJob(helper, scene); scene.worker().consumeCargo(13);
        job.targets = Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(8, 0, 3)), helper.absolutePos(new BlockPos(3, 0, 3))));
        BlockPos far = Objects.requireNonNull(helper.absolutePos(new BlockPos(8, 1, 3)));
        ItemEntity original = new ItemEntity(level, far.getX() + .5, far.getY() + .02, far.getZ() + .5, new ItemStack(Objects.requireNonNull(Items.DIRT), 13));
        original.setNoPickUpDelay(); original.setNoGravity(true); original.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); level.addFreshEntity(original);
        ReservationLedger ledger = new ReservationLedger();
        WorkTask first = Objects.requireNonNull(SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime()));
        scene.worker().workTask = first;
        BlockPos near = Objects.requireNonNull(helper.absolutePos(new BlockPos(3, 1, 3)));
        ItemEntity nearer = new ItemEntity(level, near.getX() + .5, near.getY() + .02, near.getZ() + .5, new ItemStack(Objects.requireNonNull(Items.DIAMOND), 4));
        nearer.setNoPickUpDelay(); nearer.setNoGravity(true); nearer.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); level.addFreshEntity(nearer);
        ledger.clear();
        WorkTask resumed = Objects.requireNonNull(SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime()));
        helper.assertTrue(original.getUUID().equals(resumed.source) && resumed.stand.equals(first.stand)
                && Objects.equals(resumed.targetStand, first.targetStand) && ledger.reserved(original.getUUID()) == 13
                && ledger.reserved(nearer.getUUID()) == 0 && original.getItem().getCount() == 13 && nearer.getItem().getCount() == 4,
                "Fresh nearer drop redirected a valid reserved pickup, changed its stands, or moved material while planning");
        ledger.release(Objects.requireNonNull(scene.worker().getUUID())); original.discard(); nearer.discard(); scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 300, batch = "sweep_crowded_deposit")
    public static void fiveCrowdedWorkersDepositAllTheirRealCargoAndFinish(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        var workers = new java.util.ArrayList<WorkerEntity>(); workers.add(scene.worker());
        BlockPos front = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 3)));
        ItemStack named = new ItemStack(Objects.requireNonNull(Items.DIRT), 13);
        named.setHoverName(Objects.requireNonNull(net.minecraft.network.chat.Component.literal("Crowded delivery")));
        for (int index = 0; index < 5; index++) {
            WorkerEntity worker = index == 0 ? scene.worker() : Objects.requireNonNull(ColonyEntities.WORKER.get().create(level));
            if (index > 0) { worker.owner(Objects.requireNonNull(scene.player().getUUID())); workers.add(worker); }
            worker.moveTo(front.getX() + .2 + index * .15, front.getY(), front.getZ() + .4);
            worker.carry(Objects.requireNonNull(named.copy()));
            if (index > 0) level.addFreshEntity(worker);
        }
        ColonyWorkManager manager = ColonyWorkManager.get(level);
        manager.sweep(scene.player(), Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(3, 0, 3)))));
        ColonyJob job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Crowded deposit waited for exact stand centers: " + job.reason);
            helper.assertTrue(workers.stream().allMatch(worker -> worker.cargoCount() == 0 && worker.cargoTask() == null)
                    && StorageAccess.at(level, Objects.requireNonNull(scene.warehouse().getBlockPos()), job.owner).count(named) == 65,
                    "Crowded deposit finished early, lost tagged cargo, or duplicated inventory");
            workers.forEach(worker -> Objects.requireNonNull(worker).discard());
        });
    }

    @GameTest(template = "construction", timeoutTicks = 600, batch = "sweep_long_route")
    public static void aDistantSelectedDropIsCollectedAndReturnedWithoutForgettingTheWarehouse(GameTestHelper helper) {
        Scene scene = scene(helper); corridor(helper); var level = Objects.requireNonNull(helper.getLevel());
        BlockPos floor = Objects.requireNonNull(helper.absolutePos(new BlockPos(30, 0, 3)));
        ItemStack named = new ItemStack(Objects.requireNonNull(Items.DIRT), 13);
        named.setHoverName(Objects.requireNonNull(net.minecraft.network.chat.Component.literal("Remember the warehouse")));
        ItemStack expected = Objects.requireNonNull(named.copy());
        ItemEntity item = new ItemEntity(level, floor.getX() + .5, floor.getY() + 1.02, floor.getZ() + .5, named);
        item.setNoGravity(true); item.setNoPickUpDelay(); item.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); level.addFreshEntity(item);
        ColonyWorkManager manager = ColonyWorkManager.get(level); manager.sweep(scene.player(), Objects.requireNonNull(List.of(floor)));
        ColonyJob job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Distant sweep stopped mid-route: " + job.reason + " at " + scene.worker().position());
            helper.assertTrue(!item.isAlive() && scene.worker().cargoCount() == 0
                    && StorageAccess.at(level, Objects.requireNonNull(scene.warehouse().getBlockPos()), job.owner).count(expected) == 13,
                    "Distant sweeping lost cargo tags, failed to return, or duplicated material");
            scene.worker().discard();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 700, batch = "sweep_remote_resume")
    public static void aPausedRemoteDeliveryRecoversItsWarehouseAfterTemporaryTaskClearing(GameTestHelper helper) {
        Scene scene = scene(helper); corridor(helper); var level = Objects.requireNonNull(helper.getLevel());
        BlockPos floor = Objects.requireNonNull(helper.absolutePos(new BlockPos(30, 0, 3)));
        ItemStack stack = new ItemStack(Objects.requireNonNull(Items.IRON_INGOT), 13);
        ItemEntity item = new ItemEntity(level, floor.getX() + .5, floor.getY() + 1.02, floor.getZ() + .5, stack);
        item.setNoGravity(true); item.setNoPickUpDelay(); item.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); level.addFreshEntity(item);
        ColonyWorkManager manager = ColonyWorkManager.get(level);
        manager.sweep(scene.player(), Objects.requireNonNull(List.of(floor)));
        ColonyJob job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        boolean[] paused = {false}, resumed = {false};
        helper.onEachTick(() -> {
            if (!paused[0] && scene.worker().cargoCount() == 13
                    && scene.worker().distanceToSqr(GroundResources.center(Objects.requireNonNull(scene.warehouse().getBlockPos()))) > (double) scene.worker().searchRange() * scene.worker().searchRange()) {
                paused[0] = true; manager.update(job.owner, job.id, "pause", 0);
                helper.runAfterDelay(25, () -> {
                    helper.assertTrue(job.paused && scene.worker().workTask == null && scene.worker().cargoCount() == 13
                            && job.id.equals(scene.worker().cargoTask()), "Pause did not clear only the temporary delivery, or lost cargo");
                    manager.update(job.owner, job.id, "pause", 0); resumed[0] = true;
                });
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(resumed[0] && job.finished(), "Remote paused delivery could not recover its destination: " + job.reason + " at " + scene.worker().position());
            helper.assertTrue(!item.isAlive() && scene.worker().cargoCount() == 0
                    && StorageAccess.at(level, Objects.requireNonNull(scene.warehouse().getBlockPos()), job.owner).count(new ItemStack(Objects.requireNonNull(Items.IRON_INGOT))) == 13,
                    "Remote resumed delivery lost or duplicated its cargo");
            scene.worker().discard();
        });
    }

    @Nonnull private static ColonyJob carriedJob(GameTestHelper helper, Scene scene) {
        ColonyJob job = new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(scene.player().getUUID()), "SweepRecovery", ColonyJob.Kind.SWEEP, 5, 0);
        job.surfaceSweep = true; job.targets = Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(3, 0, 3))));
        ItemStack cargo = new ItemStack(Objects.requireNonNull(Items.DIRT), 13);
        cargo.setHoverName(Objects.requireNonNull(net.minecraft.network.chat.Component.literal("Remember the warehouse")));
        scene.worker().carry(cargo); scene.worker().cargoTask(job.id); return job;
    }
    private static void corridor(GameTestHelper helper) {
        // This batch runs alone; extend the fixture while retaining physical ground and loaded-area paths.
        for (int x = 0; x <= 32; x++) for (int z = 2; z <= 5; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y <= 3; y++) if (x > 11) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
    }
    @Nonnull static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        var level = Objects.requireNonNull(helper.getLevel());
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "SweepRecoveryTest")));
        BlockPos pos = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 3))); player.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        player.getAbilities().mayBuild = true;
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(level));
        worker.owner(Objects.requireNonNull(player.getUUID())); worker.moveTo(player.getX(), player.getY(), player.getZ()); level.addFreshEntity(worker);
        // Synchronous planning checks start on the verified stone floor, before the first physics tick.
        worker.setOnGround(true);
        helper.setBlock(new BlockPos(2, 1, 4), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        MachineBlockEntity warehouse = Objects.requireNonNull((MachineBlockEntity) helper.getBlockEntity(new BlockPos(2, 1, 4)));
        warehouse.owner(Objects.requireNonNull(player.getUUID())); return new Scene(player, worker, warehouse);
    }
}
