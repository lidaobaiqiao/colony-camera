package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.construction.BuildCatalogService;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
public final class SweepGameTests {
    private record Scene(FakePlayer player, WorkerEntity worker, MachineBlockEntity warehouse) {}

    @GameTest(template = "construction", timeoutTicks = 20, batch = "sweep_regression")
    public static void legacyConstructionCargoIsNotClaimedByAnEmptySweep(GameTestHelper helper) {
        Scene scene = scene(helper);
        ColonyJob sweep = queue(helper, scene);
        BuildCatalogService.place(Objects.requireNonNull(scene.player), Objects.requireNonNull(UUID.randomUUID()),
                Objects.requireNonNull(Items.DIRT), Objects.requireNonNull(helper.absolutePos(new BlockPos(8, 1, 8))), 0, "", null);
        scene.worker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Objects.requireNonNull(Items.DIRT)));
        WorkTask stolen = SweepService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker),
                Objects.requireNonNull(sweep), new ReservationLedger(), helper.getLevel().getGameTime());
        helper.assertTrue(stolen == null && sweep.finished(), "An empty sweep claimed a construction delivery and stayed active");
        helper.assertTrue(scene.worker.getMainHandItem().getCount() == 1, "Legacy carried construction material was changed");
        scene.worker.discard();
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "sweep_regression")
    public static void pausedConstructionKeepsCargoAndCancelledConstructionCanReturnIt(GameTestHelper helper) {
        Scene scene = scene(helper); UUID project = Objects.requireNonNull(UUID.randomUUID());
        var manager = dev.colony.camera.construction.ConstructionManager.get(Objects.requireNonNull(helper.getLevel()));
        BuildCatalogService.place(Objects.requireNonNull(scene.player), project, Objects.requireNonNull(Items.DIRT),
                Objects.requireNonNull(helper.absolutePos(new BlockPos(8, 1, 8))), 0, "", null);
        helper.assertTrue(manager.project(project) != null, "Construction was not created");
        scene.worker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Objects.requireNonNull(Items.DIRT)));
        scene.worker.cargoTask(project); manager.update(Objects.requireNonNull(scene.player.getUUID()), project, "pause", 0);
        ColonyJob paused = Objects.requireNonNull(standalone(helper, scene));
        helper.assertTrue(SweepService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), paused,
                new ReservationLedger(), helper.getLevel().getGameTime()) == null && scene.worker.getMainHandItem().getCount() == 1,
                "Sweep took material from a paused construction project");
        manager.update(Objects.requireNonNull(scene.player.getUUID()), project, "cancel", 0);
        ColonyJob cancelled = Objects.requireNonNull(standalone(helper, scene)); var ledger = new ReservationLedger();
        WorkTask delivery = Objects.requireNonNull(SweepService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker),
                cancelled, ledger, helper.getLevel().getGameTime()));
        helper.assertTrue(completeAction(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), cancelled, delivery, ledger, helper.getLevel().getGameTime())
                && scene.worker.getMainHandItem().isEmpty() && scene.worker.cargoTask() == null
                && StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.warehouse.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(new ItemStack(Objects.requireNonNull(Items.DIRT))) == 1,
                "Cancelled construction material could not be returned without loss");
        scene.worker.discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 450)
    public static void selectedGroundCargoReachesFiniteWarehouseWithTags(GameTestHelper helper) {
        Scene scene = scene(helper);
        ItemStack named = new ItemStack(Objects.requireNonNull(Items.DIRT), 19); named.setHoverName(net.minecraft.network.chat.Component.literal("Colony cargo"));
        ItemStack expected = named.copy();
        ItemEntity item = drop(helper, scene, new BlockPos(8, 1, 8), named.copy(), scene.player.getUUID());
        ColonyJob job = queue(helper, scene);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Selected sweeping did not finish: " + job.reason);
            helper.assertTrue(!item.isAlive() && scene.worker.getMainHandItem().isEmpty()
                    && StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.warehouse.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(Objects.requireNonNull(expected)) == 19
                    && scene.warehouse.inventory.getStackInSlot(0).hasCustomHoverName()
                    && scene.warehouse.inventory.getStackInSlot(0).getHoverName().getString().equals(expected.getHoverName().getString()),
                    "Ground to worker to warehouse changed tags or quantity: reason=" + job.reason + ", itemAlive=" + item.isAlive()
                            + ", itemCount=" + item.getItem().getCount() + ", warehouse=" + StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.warehouse.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(Objects.requireNonNull(expected))
                            + ", slot0=" + scene.warehouse.inventory.getStackInSlot(0)
                            + ", destination=" + scene.warehouse.getBlockPos());
        });
    }

    @GameTest(template = "construction", timeoutTicks = 500)
    public static void fullWarehouseKeepsGroundItemsUntilSpaceIsFreed(GameTestHelper helper) {
        Scene scene = scene(helper);
        for (int slot = 0; slot < scene.warehouse.inventory.getSlots(); slot++) scene.warehouse.inventory.setStackInSlot(slot, new ItemStack(Objects.requireNonNull(Items.STONE), 64));
        ItemEntity item = drop(helper, scene, new BlockPos(3, 1, 3), new ItemStack(Objects.requireNonNull(Items.DIAMOND), 7), scene.player.getUUID());
        ColonyJob job = queue(helper, scene);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(item.isAlive() && item.getItem().getCount() == 7 && scene.worker.getMainHandItem().isEmpty() && !job.finished(),
                    "Full warehouse lost, duplicated or prematurely completed the drop");
            scene.warehouse.inventory.setStackInSlot(0, ItemStack.EMPTY);
        });
        helper.succeedWhen(() -> helper.assertTrue(job.finished() && !item.isAlive()
                && StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.warehouse.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(new ItemStack(Objects.requireNonNull(Items.DIAMOND))) == 7,
                "Free warehouse capacity did not resume the queued sweep: " + job.reason));
    }

    @GameTest(template = "construction", timeoutTicks = 250)
    public static void disconnectedNetworkPreservesHeldCargoAndReconnects(GameTestHelper helper) {
        Scene scene = scene(helper);
        helper.setBlock(new BlockPos(3, 1, 4), Objects.requireNonNull(ColonyContent.CABLE.get()));
        helper.setBlock(new BlockPos(4, 1, 4), Objects.requireNonNull(ColonyContent.PORT.get()));
        helper.setBlock(new BlockPos(5, 1, 4), Objects.requireNonNull(ColonyContent.CONTROLLER.get()));
        for (BlockPos pos : List.of(new BlockPos(3, 1, 4), new BlockPos(4, 1, 4), new BlockPos(5, 1, 4))) ((MachineBlockEntity) Objects.requireNonNull(helper.getBlockEntity(Objects.requireNonNull(pos)))).owner(scene.player.getUUID());
        ItemEntity item = drop(helper, scene, new BlockPos(2, 1, 3), new ItemStack(Objects.requireNonNull(Items.IRON_INGOT), 11), scene.player.getUUID());
        ColonyJob job = standalone(helper, scene); ReservationLedger ledger = new ReservationLedger();
        WorkTask task = Objects.requireNonNull(SweepService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), ledger, helper.getLevel().getGameTime()));
        // Force this already reachable pickup to use the physical network port for its deposit.
        task.destination = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos portStand = helper.absolutePos(new BlockPos(4, 1, 3));
        helper.assertTrue(!completeAction(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), task, ledger, helper.getLevel().getGameTime()) && !item.isAlive()
                && scene.worker.getMainHandItem().getCount() == 11, "Worker did not physically pick up cargo");
        task.stand = Objects.requireNonNull(portStand); scene.worker.moveTo(portStand.getX() + 0.5, portStand.getY(), portStand.getZ() + 0.5);
        helper.setBlock(new BlockPos(3, 1, 4), Objects.requireNonNull(Blocks.AIR));
        completeAction(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), task, ledger, helper.getLevel().getGameTime());
        helper.assertTrue(scene.worker.getMainHandItem().getCount() == 11 && scene.warehouse.inventory.getStackInSlot(0).isEmpty(),
                "Disconnect destroyed or teleported held cargo");
        helper.setBlock(new BlockPos(3, 1, 4), Objects.requireNonNull(ColonyContent.CABLE.get()));
        ((MachineBlockEntity) Objects.requireNonNull(helper.getBlockEntity(new BlockPos(3, 1, 4)))).owner(scene.player.getUUID());
        WorkTask resumed = Objects.requireNonNull(SweepService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), ledger, helper.getLevel().getGameTime()));
        resumed.stand = portStand; scene.worker.moveTo(portStand.getX() + 0.5, portStand.getY(), portStand.getZ() + 0.5);
        helper.assertTrue(completeAction(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), resumed, ledger, helper.getLevel().getGameTime())
                && scene.worker.getMainHandItem().isEmpty()
                && StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.warehouse.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(new ItemStack(Objects.requireNonNull(Items.IRON_INGOT))) == 11,
                "Reconnect did not deposit the preserved cargo: reason=" + job.reason + ", network=" + StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(task.destination), Objects.requireNonNull(scene.player.getUUID())).status);
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void pickupProtectionAndForeignOwnersKeepTheirItems(GameTestHelper helper) {
        Scene scene = scene(helper);
        ItemEntity foreign = drop(helper, scene, new BlockPos(2, 1, 3), new ItemStack(Objects.requireNonNull(Items.DIAMOND), 4), UUID.randomUUID());
        ColonyJob job = standalone(helper, scene); ReservationLedger ledger = new ReservationLedger();
        helper.assertTrue(SweepService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), ledger, helper.getLevel().getGameTime()) == null && foreign.isAlive(), "Foreign restricted cargo was claimed");
        job = standalone(helper, scene); ItemEntity own = drop(helper, scene, new BlockPos(2, 1, 3), new ItemStack(Objects.requireNonNull(Items.DIRT), 8), scene.player.getUUID());
        WorkTask task = Objects.requireNonNull(SweepService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), ledger, helper.getLevel().getGameTime()));
        Consumer<EntityItemPickupEvent> protection = event -> { if (event.getItem() == own) event.setCanceled(true); };
        MinecraftForge.EVENT_BUS.addListener(protection);
        try { helper.assertTrue(completeAction(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), task, ledger, helper.getLevel().getGameTime()), "Protected pickup did not release"); }
        finally { MinecraftForge.EVENT_BUS.unregister(protection); }
        helper.assertTrue(own.isAlive() && own.getItem().getCount() == 8 && foreign.getItem().getCount() == 4 && scene.worker.getMainHandItem().isEmpty(), "Cancelled pickup changed inventory");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void workDataRoundTripPauseAndMalformedProtection(GameTestHelper helper) {
        Scene scene = scene(helper); ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        BlockPos selected = helper.absolutePos(new BlockPos(6, 1, 6)); helper.setBlock(new BlockPos(6, 1, 6), Objects.requireNonNull(Blocks.STONE));
        manager.mine(Objects.requireNonNull(scene.player), Objects.requireNonNull(List.of(selected))); ColonyJob job = manager.jobs(Objects.requireNonNull(scene.player.getUUID())).get(0);
        manager.update(Objects.requireNonNull(scene.player.getUUID()), job.id, "priority", 9); manager.update(Objects.requireNonNull(scene.player.getUUID()), job.id, "pause", 0);
        boolean blocked = false; try { manager.mine(Objects.requireNonNull(scene.player), Objects.requireNonNull(List.of(selected))); } catch (IllegalArgumentException expected) { blocked = expected.getMessage().equals("overlap"); }
        helper.assertTrue(blocked, "Paused excavation lost its selection claim");
        CompoundTag saved = manager.save(new CompoundTag()); ColonyWorkManager restored = ColonyWorkManager.load(saved);
        ColonyJob copy = restored.jobs(Objects.requireNonNull(scene.player.getUUID())).get(0);
        helper.assertTrue(!restored.readOnly() && copy.priority == 9 && copy.paused && copy.targets.equals(List.of(selected)) && copy.sequence == job.sequence,
                "Work reload lost queue order, selection or pause");
        CompoundTag malformed = saved.copy(); malformed.putString("schemaVersion", "future");
        ColonyWorkManager protectedData = ColonyWorkManager.load(malformed);
        helper.assertTrue(protectedData.readOnly() && protectedData.save(new CompoundTag()).equals(malformed), "Malformed work data was overwritten");
        manager.update(Objects.requireNonNull(scene.player.getUUID()), job.id, "cancel", 0); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "sweep_regression")
    public static void anIdleWorkerCannotCompleteASweepWhileAnotherCarriesItsCargo(GameTestHelper helper) {
        Scene scene = scene(helper);
        ColonyJob job = Objects.requireNonNull(standalone(helper, scene));
        WorkerEntity idle = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel())));
        idle.owner(Objects.requireNonNull(scene.player.getUUID()));
        BlockPos pos = Objects.requireNonNull(helper.absolutePos(new BlockPos(7, 1, 3)));
        idle.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(idle);
        scene.worker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Objects.requireNonNull(Items.DIRT), 8));
        var ledger = new ReservationLedger();
        SweepService.assign(Objects.requireNonNull(helper.getLevel()), idle, job, ledger, helper.getLevel().getGameTime());
        helper.assertTrue(!job.finished(), "Idle worker marked the job complete before the last cargo was deposited");
        var delivery = Objects.requireNonNull(SweepService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), job, ledger, helper.getLevel().getGameTime()));
        helper.assertTrue(scene.worker.getMainHandItem().getCount() == 8, "Held cargo did not retain a delivery assignment");
        helper.assertTrue(completeAction(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), job, delivery, ledger, helper.getLevel().getGameTime())
                && scene.worker.getMainHandItem().isEmpty()
                && StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.warehouse.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(new ItemStack(Objects.requireNonNull(Items.DIRT))) == 8,
                "Last cargo was not physically deposited without loss");
        SweepService.assign(Objects.requireNonNull(helper.getLevel()), idle, job, ledger, helper.getLevel().getGameTime());
        helper.assertTrue(job.finished(), "Sweep did not finish after the last cargo was deposited");
        idle.discard();
        scene.worker.discard();
        helper.succeed();
    }

    // Transfer/protection fixtures start with the hauling action already worked; duration is covered in worker-state scenes.
    private static boolean completeAction(net.minecraft.server.level.ServerLevel level, WorkerEntity worker, ColonyJob job, WorkTask task, ReservationLedger ledger, long time) {
        task.actionProgress = dev.colony.camera.worker.WorkerEffects.haulTicks(worker.state()) - 1;
        return SweepService.execute(Objects.requireNonNull(level), worker, Objects.requireNonNull(job), task, Objects.requireNonNull(ledger), time);
    }

    private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) { helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE)); for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR)); }
        AABB bounds = new AABB(Objects.requireNonNull(helper.absolutePos(Objects.requireNonNull(BlockPos.ZERO))), Objects.requireNonNull(helper.absolutePos(new BlockPos(12, 6, 12))));
        helper.getLevel().getEntitiesOfClass(WorkerEntity.class, bounds).forEach((WorkerEntity checkedElement) -> Objects.requireNonNull(checkedElement).discard()); helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds).forEach((ItemEntity checkedElement) -> Objects.requireNonNull(checkedElement).discard());
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "SweepTest")); BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 3)); player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); worker.owner(Objects.requireNonNull(player.getUUID())); worker.moveTo(player.getX(), player.getY(), player.getZ(), 0, 0); helper.getLevel().addFreshEntity(worker);
        helper.setBlock(new BlockPos(2, 1, 4), Objects.requireNonNull(ColonyContent.WAREHOUSE.get())); var warehouse = (MachineBlockEntity) helper.getBlockEntity(new BlockPos(2, 1, 4)); Objects.requireNonNull(warehouse).owner(player.getUUID()); return new Scene(player, worker, warehouse);
    }
    private static ItemEntity drop(GameTestHelper helper, Scene scene, BlockPos relative, ItemStack stack, UUID owner) {
        BlockPos pos = helper.absolutePos(Objects.requireNonNull(relative)); ItemEntity item = new ItemEntity(Objects.requireNonNull(helper.getLevel()), pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5, Objects.requireNonNull(stack));
        item.setTarget(owner); item.setNoPickUpDelay(); item.setNoGravity(true); item.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); helper.getLevel().addFreshEntity(item); return item;
    }
    private static ColonyJob queue(GameTestHelper helper, Scene scene) {
        ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel())); manager.sweep(Objects.requireNonNull(scene.player), Objects.requireNonNull(helper.absolutePos(new BlockPos(0, 1, 0))), Objects.requireNonNull(helper.absolutePos(new BlockPos(11, 3, 11))));
        return manager.jobs(Objects.requireNonNull(scene.player.getUUID())).stream().filter(job -> job.kind == ColonyJob.Kind.SWEEP).findFirst().orElseThrow();
    }
    private static ColonyJob standalone(GameTestHelper helper, Scene scene) {
        ColonyJob job = new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(scene.player.getUUID()), "SweepTest", ColonyJob.Kind.SWEEP, 5, 0);
        job.targets = Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(11, 3, 11)))); return job;
    }
}
