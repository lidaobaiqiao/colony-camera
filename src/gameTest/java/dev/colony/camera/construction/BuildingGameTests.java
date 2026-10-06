package dev.colony.camera.construction;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.crafting.CraftingService;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.work.ColonyJob;
import dev.colony.camera.work.ColonyWorkManager;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class BuildingGameTests {
    private record Scene(@Nonnull FakePlayer player, @Nonnull WorkerEntity worker, @Nonnull ConstructionManager construction, @Nonnull ColonyWorkManager work) {}
    @GameTest(template = "construction", timeoutTicks = 300, batch = "building_drop")
    public static void paletteReplacesGrassWhileSolidConflictsRemainProtected(GameTestHelper helper) {
        Scene scene = scene(helper);
        BlockPos target = pos(helper, 8, 1, 7); UUID id = Objects.requireNonNull(UUID.randomUUID());
        helper.setBlock(new BlockPos(8, 1, 7), Objects.requireNonNull(Blocks.GRASS));
        scene.worker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Objects.requireNonNull(Items.STONE)));
        BuildCatalogService.place(scene.player, id, Objects.requireNonNull(Items.STONE), target, 0, "", null);
        BlockPos solid = pos(helper, 9, 1, 7); UUID blocked = Objects.requireNonNull(UUID.randomUUID());
        helper.setBlock(new BlockPos(9, 1, 7), Objects.requireNonNull(Blocks.OBSIDIAN));
        BuildCatalogService.place(scene.player, blocked, Objects.requireNonNull(Items.DIRT), solid, 0, "", null);
        helper.succeedWhen(() -> {
            helper.assertTrue(Objects.requireNonNull(scene.construction.project(id)).finished() && helper.getLevel().getBlockState(target).is(Objects.requireNonNull(Blocks.STONE)), "Replaceable grass blocked palette construction");
            helper.assertTrue(helper.getLevel().getBlockState(solid).is(Objects.requireNonNull(Blocks.OBSIDIAN)) && !Objects.requireNonNull(scene.construction.project(blocked)).finished(), "Solid conflict was overwritten");
        });
    }
    @GameTest(template = "construction", timeoutTicks = 250, batch = "building_drop")
    public static void realGroundStockBuildsOnlyAfterAWorkerFetchesIt(GameTestHelper helper) {
        Scene scene = scene(helper); BlockPos target = pos(helper, 8, 1, 7); UUID projectId = Objects.requireNonNull(UUID.randomUUID());
        ItemEntity drop = new ItemEntity(Objects.requireNonNull(helper.getLevel()), scene.worker.getX() + 1, scene.worker.getY(), scene.worker.getZ(), new ItemStack(Objects.requireNonNull(Items.STONE), 2));
        drop.setNoPickUpDelay(); helper.getLevel().addFreshEntity(drop);
        BuildCatalogService.place(scene.player, projectId, Objects.requireNonNull(Items.STONE), target, 0, "", null);
        helper.assertTrue(helper.getLevel().getBlockState(target).isAir() && Objects.requireNonNull(scene.construction.project(projectId)).palette, "Preview placed real blocks or was not saved as a palette project");
        helper.succeedWhen(() -> {
            helper.assertTrue(Objects.requireNonNull(scene.construction.project(projectId)).finished() && helper.getLevel().getBlockState(target).is(Objects.requireNonNull(Blocks.STONE)), "Worker has not completed ground construction");
            helper.assertTrue(drop.isAlive() && drop.getItem().getCount() == 1 && scene.worker.getMainHandItem().isEmpty(), "Construction fabricated or lost material");
            scene.worker.discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 400, batch = "building_auto")
    public static void blueShapelessBlockAutomaticallyCraftsThenBuildsAndKeepsExtraOutput(GameTestHelper helper) {
        Scene scene = scene(helper); BlockPos station = pos(helper, 5, 1, 5); machine(helper, scene, 5, 1, 5, Objects.requireNonNull(ColonyContent.COLONY_WORKBENCH.get()));
        var box = machine(helper, scene, 5, 1, 3, Objects.requireNonNull(ColonyContent.WAREHOUSE.get())); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_LOG)));
        UUID id = Objects.requireNonNull(UUID.randomUUID()); BlockPos target = pos(helper, 8, 1, 7);
        helper.runAfterDelay(10, () -> {
            BuildCatalogService.place(scene.player, id, Objects.requireNonNull(Items.OAK_PLANKS), target, 0, "minecraft:oak_planks", station);
            var supply = scene.work.jobs(Objects.requireNonNull(scene.player.getUUID())).get(0);
            helper.assertTrue(id.equals(supply.parentProject) && supply.sequence == Objects.requireNonNull(scene.construction.project(id)).sequence, "Supply lost its parent FIFO position");
            helper.assertTrue(helper.getLevel().getBlockState(target).isAir() && box.inventory.getStackInSlot(0).is(Objects.requireNonNull(Items.OAK_LOG)), "Placement consumed stock before worker crafting");
        });
        helper.succeedWhen(() -> {
            var project = scene.construction.project(id); helper.assertTrue(project != null && project.finished(), "Craft prerequisite has not reached construction");
            var stock = StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(box.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()));
            helper.assertTrue(stock.count(new ItemStack(Objects.requireNonNull(Items.OAK_LOG))) == 0 && stock.count(new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS))) == 3 && helper.getLevel().getBlockState(target).is(Objects.requireNonNull(Blocks.OAK_PLANKS)), "Recipe output count or build consumption is wrong"); scene.worker.discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 450, batch = "building_priority")
    public static void highPrioritySupplyRunsBeforeAnOlderLowPriorityRecipe(GameTestHelper helper) {
        Scene scene = scene(helper); BlockPos station = pos(helper, 5, 1, 5); machine(helper, scene, 5, 1, 5, Objects.requireNonNull(ColonyContent.COLONY_WORKBENCH.get()));
        var box = machine(helper, scene, 5, 1, 3, Objects.requireNonNull(ColonyContent.WAREHOUSE.get())); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_LOG)));
        UUID id = Objects.requireNonNull(UUID.randomUUID()); ColonyJob[] low = {null};
        helper.runAfterDelay(10, () -> {
            scene.work.craft(scene.player, station, "minecraft:oak_planks", 1); low[0] = scene.work.jobs(Objects.requireNonNull(scene.player.getUUID())).get(0);
            scene.work.update(Objects.requireNonNull(scene.player.getUUID()), Objects.requireNonNull(low[0]).id, "priority", 1);
            BuildCatalogService.place(scene.player, id, Objects.requireNonNull(Items.OAK_PLANKS), pos(helper, 8, 1, 7), 0, "minecraft:oak_planks", station);
            scene.construction.update(Objects.requireNonNull(scene.player.getUUID()), id, "priority", 9);
        });
        helper.runAtTickTime(200, () -> {
            helper.assertTrue(Objects.requireNonNull(scene.construction.project(id)).finished() && !Objects.requireNonNull(low[0]).finished(), "Older low-priority recipe stole high-priority building ingredients");
            helper.assertTrue(StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(box.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS))) == 3, "Automatic recipe did not retain extra stock");
            helper.assertTrue(StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(box.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).insert(new ItemStack(Objects.requireNonNull(Items.OAK_LOG)), false).isEmpty(), "Fixture could not store replenishment");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(low[0] != null && Objects.requireNonNull(low[0]).finished(), "Low-priority recipe did not resume after new materials arrived: " + (low[0] == null ? "not created" : Objects.requireNonNull(low[0]).reason));
            helper.assertTrue(StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(box.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS))) == 7, "Shared material accounting failed"); scene.worker.discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "building_access")
    public static void catalogRejectsForeignLockedAndEnclosedStockAndPickupOwnership(GameTestHelper helper) {
        Scene scene = scene(helper); var own = machine(helper, scene, 4, 1, 3, Objects.requireNonNull(ColonyContent.WAREHOUSE.get())); own.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.STONE), 2));
        var foreign = machine(helper, scene, 6, 1, 3, Objects.requireNonNull(ColonyContent.WAREHOUSE.get())); foreign.owner(Objects.requireNonNull(UUID.randomUUID())); foreign.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.DIAMOND), 7));
        helper.setBlock(new BlockPos(4, 1, 5), Objects.requireNonNull(Blocks.CHEST)); var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) Objects.requireNonNull(helper.getBlockEntity(new BlockPos(4, 1, 5)));
        chest.setItem(0, new ItemStack(Objects.requireNonNull(Items.IRON_INGOT), 7)); var locked = Objects.requireNonNull(chest.saveWithoutMetadata()); locked.putString("Lock", "secret"); chest.load(locked);
        var sealed = machine(helper, scene, 9, 1, 9, Objects.requireNonNull(ColonyContent.WAREHOUSE.get())); sealed.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.GOLD_INGOT), 7));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 1; y <= 3; y++) if (dx != 0 || dz != 0 || y != 1) helper.setBlock(new BlockPos(9 + dx, y, 9 + dz), Objects.requireNonNull(Blocks.STONE));
        var drop = new ItemEntity(Objects.requireNonNull(helper.getLevel()), scene.worker.getX(), scene.worker.getY(), scene.worker.getZ(), new ItemStack(Objects.requireNonNull(Items.EMERALD), 5)); drop.setNoPickUpDelay();
        var data = new CompoundTag(); drop.saveWithoutId(data); data.putUUID("Owner", Objects.requireNonNull(UUID.randomUUID())); drop.load(data); helper.getLevel().addFreshEntity(drop);
        helper.runAfterDelay(10, () -> {
            var scan = new BuildCatalogService.Scan(scene.player, Objects.requireNonNull(UUID.randomUUID())); for (int i = 0; !scan.done() && i < 10000; i++) scan.step();
            var entries = scan.entries(); helper.assertTrue(scan.done() && entries.stream().anyMatch(entry -> entry.item().equals("minecraft:stone") && entry.available() == 2), "Reachable warehouse was omitted");
            helper.assertTrue(entries.stream().noneMatch(entry -> Set.of("minecraft:diamond", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:emerald").contains(entry.item())), "Catalog exposed foreign, locked, unreachable or owned drops");
            drop.discard(); scene.worker.discard(); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "building_network")
    public static void catalogDeduplicatesWorkersAndNetworkPortsBeforeSubtractingReservations(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 3, 1, 4, Objects.requireNonNull(ColonyContent.CONTROLLER.get()));
        machine(helper, scene, 4, 1, 4, Objects.requireNonNull(ColonyContent.CABLE.get()));
        var box = machine(helper, scene, 5, 1, 4, Objects.requireNonNull(ColonyContent.WAREHOUSE.get())); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.STONE), 8));
        machine(helper, scene, 6, 1, 4, Objects.requireNonNull(ColonyContent.PORT.get())); machine(helper, scene, 5, 1, 3, Objects.requireNonNull(ColonyContent.PORT.get()));
        var second = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); second.owner(Objects.requireNonNull(scene.player.getUUID())); second.moveTo(scene.worker.getX(), scene.worker.getY(), scene.worker.getZ() + 1); helper.getLevel().addFreshEntity(second);
        helper.runAfterDelay(10, () -> {
            var source = Objects.requireNonNull(StorageResources.resolve(Objects.requireNonNull(helper.getLevel()), pos(helper, 5, 1, 3), Objects.requireNonNull(scene.player.getUUID()), Objects.requireNonNull(Items.STONE)));
            UUID claim = Objects.requireNonNull(UUID.randomUUID()); scene.construction.reservations().reserve(claim, Objects.requireNonNull(UUID.randomUUID()), source.identity(), 8, 3);
            var scan = new BuildCatalogService.Scan(scene.player, Objects.requireNonNull(UUID.randomUUID())); for (int i = 0; !scan.done() && i < 10000; i++) scan.step();
            helper.assertTrue(scan.done() && scan.entries().stream().filter(entry -> entry.item().equals("minecraft:stone")).findFirst().orElseThrow().available() == 5, "Network inventory or reservation was counted once per port/worker");
            scene.construction.reservations().release(claim); scene.worker.discard(); second.discard(); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 400, batch = "building_catalog")
    public static void catalogUsesReachableUnreservedStockAndExecutableRecipesWithoutMovingWorkers(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, Objects.requireNonNull(ColonyContent.COLONY_WORKBENCH.get()));
        var box = machine(helper, scene, 5, 1, 3, Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_LOG))); box.inventory.setStackInSlot(1, new ItemStack(Objects.requireNonNull(Items.STONE), 3));
        UUID reservationWorker = Objects.requireNonNull(UUID.randomUUID()); var source = Objects.requireNonNull(StorageResources.resolve(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(box.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()), Objects.requireNonNull(Items.STONE)));
        helper.runAfterDelay(10, () -> {
            var ledger = scene.construction.reservations(); helper.assertTrue(ledger.reserve(reservationWorker, Objects.requireNonNull(UUID.randomUUID()), source.identity(), 3, 2), "Fixture reservation failed");
            var scan = new BuildCatalogService.Scan(scene.player, Objects.requireNonNull(UUID.randomUUID()));
            int steps = 0; while (!scan.done() && steps++ < 10000) scan.step(); helper.assertTrue(scan.done(), "Catalog did not finish its bounded steps");
            var stone = scan.entries().stream().filter(entry -> entry.item().equals("minecraft:stone")).findFirst().orElseThrow();
            var planks = scan.entries().stream().filter(entry -> entry.item().equals("minecraft:oak_planks")).findFirst().orElseThrow();
            helper.assertTrue(stone.available() == 1 && planks.available() == 0 && planks.recipe().equals("minecraft:oak_planks") && planks.station() != null, "Catalog counted reserved stock or omitted executable shapeless production");
            helper.assertTrue(scene.worker.getNavigation().isDone() && box.inventory.getStackInSlot(0).getCount() == 1, "Preview changed navigation or real inventory");
            ledger.release(reservationWorker); scene.worker.discard(); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "building_recipe")
    public static void blueprintSimulationRejectsInsufficientIngredientsAndFullOutputStorage(GameTestHelper helper) {
        Scene scene = scene(helper); BlockPos station = pos(helper, 5, 1, 5), stand = pos(helper, 5, 1, 4);
        machine(helper, scene, 5, 1, 5, Objects.requireNonNull(ColonyContent.COLONY_WORKBENCH.get())); var box = machine(helper, scene, 5, 1, 3, Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.STONE), 3));
        helper.assertTrue(!CraftingService.canProduce(Objects.requireNonNull(helper.getLevel()), scene.worker, station, stand, "minecraft:stone_bricks", scene.construction.reservations()), "Three stone incorrectly satisfy a four-stone recipe");
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.STONE), 4));
        helper.assertTrue(CraftingService.canProduce(Objects.requireNonNull(helper.getLevel()), scene.worker, station, stand, "minecraft:stone_bricks", scene.construction.reservations()), "Shaped building recipe was rejected");
        for (int slot = 0; slot < box.inventory.getSlots(); slot++) box.inventory.setStackInSlot(slot, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 64));
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_LOG), 2));
        helper.assertTrue(!CraftingService.canProduce(Objects.requireNonNull(helper.getLevel()), scene.worker, station, stand, "minecraft:oak_planks", scene.construction.reservations()), "Blue status ignored output capacity");
        scene.worker.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 20, batch = "building_lifecycle")
    public static void linkedSupplyFollowsParentPriorityPauseCancelAndSavedDataMigration(GameTestHelper helper) {
        Scene scene = scene(helper); UUID id = Objects.requireNonNull(UUID.randomUUID());
        BuildCatalogService.place(scene.player, id, Objects.requireNonNull(Items.STONE), pos(helper, 8, 1, 7), 0, "", null);
        var parent = Objects.requireNonNull(scene.construction.project(id)); scene.work.supply(parent, pos(helper, 5, 1, 5), "minecraft:stone_bricks");
        var supply = scene.work.jobs(Objects.requireNonNull(scene.player.getUUID())).get(0);
        scene.construction.update(parent.owner, id, "priority", 9); scene.construction.update(parent.owner, id, "pause", 0); scene.work.synchronizeSupplies(scene.construction);
        helper.assertTrue(supply.priority == 9 && supply.paused && supply.sequence == parent.sequence, "Supply did not inherit project scheduling");
        var restored = ColonyWorkManager.load(scene.work.save(new CompoundTag())); var restoredBuilds = ConstructionManager.load(scene.construction.save(new CompoundTag()));
        helper.assertTrue(id.equals(restored.jobs(parent.owner).get(0).parentProject) && Objects.requireNonNull(restoredBuilds.project(id)).palette, "Reload lost supply link or server projection");
        CompoundTag legacy = scene.construction.save(new CompoundTag()); legacy.putInt("schemaVersion", 1); legacy.getList("projects", 10).getCompound(0).remove("palette");
        helper.assertTrue(!Objects.requireNonNull(ConstructionManager.load(legacy).project(id)).palette, "Legacy project was changed into a palette plan");
        scene.work.control(Objects.requireNonNull(helper.getLevel()), parent.owner, supply.id, "pause", 0); scene.work.control(Objects.requireNonNull(helper.getLevel()), parent.owner, supply.id, "priority", 7);
        helper.assertTrue(!parent.paused && !supply.paused && parent.priority == 7 && supply.priority == 7, "Supply UI controls did not update the parent project");
        scene.work.control(Objects.requireNonNull(helper.getLevel()), parent.owner, supply.id, "cancel", 0); helper.assertTrue(supply.cancelled && parent.cancelled, "Cancelled construction kept manufacturing");
        scene.worker.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 20, batch = "building_reject")
    public static void rejectedAutoBuildDoesNotLeaveOrphanProductionOrConstruction(GameTestHelper helper) {
        Scene scene = scene(helper); var station = pos(helper, 5, 1, 5); machine(helper, scene, 5, 1, 5, Objects.requireNonNull(ColonyContent.COLONY_WORKBENCH.get()));
        machine(helper, scene, 5, 1, 3, Objects.requireNonNull(ColonyContent.WAREHOUSE.get())); boolean rejected = false;
        try { BuildCatalogService.place(scene.player, Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(Items.STONE), pos(helper, 8, 1, 7), 0, "minecraft:oak_planks", station); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected && scene.construction.projects(Objects.requireNonNull(scene.player.getUUID())).isEmpty() && scene.work.jobs(Objects.requireNonNull(scene.player.getUUID())).isEmpty(), "Rejected intent left orphan jobs");
        scene.worker.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 20, batch = "building_pairs")
    public static void doorsAndBedsUsePairedDesignsAndOneRealItemAtEveryRotation(GameTestHelper helper) {
        for (Item item : new Item[]{Items.OAK_DOOR, Items.RED_BED}) for (int turn = 0; turn < 4; turn++) {
            var preset = BlockBuilding.design(Objects.requireNonNull(item));
            var plan = BuildPlan.compile(new dev.colony.camera.planning.ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, 0, 0, 0, turn, false, true));
            helper.assertTrue(plan.units().size() == 1 && plan.units().get(0).blocks().size() == 2 && plan.units().get(0).count() == 1, "Paired building consumed two items or became incomplete");
        }
        helper.succeed();
    }
    @Nonnull private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE)); for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        var level = Objects.requireNonNull(helper.getLevel()); var owner = Objects.requireNonNull(UUID.randomUUID());
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(owner, "BuildingTest"))); var start = pos(helper, 2, 1, 2); player.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5);
        var worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(level)); worker.owner(owner); worker.moveTo(player.getX(), player.getY(), player.getZ()); level.addFreshEntity(worker);
        return new Scene(player, worker, ConstructionManager.get(level), ColonyWorkManager.get(level));
    }
    @Nonnull private static BlockPos pos(GameTestHelper helper, int x, int y, int z) { return Objects.requireNonNull(helper.absolutePos(new BlockPos(x, y, z))); }
    @Nonnull private static MachineBlockEntity machine(GameTestHelper helper, Scene scene, int x, int y, int z, Block block) {
        helper.setBlock(new BlockPos(x, y, z), block); var machine = (MachineBlockEntity) Objects.requireNonNull(helper.getBlockEntity(new BlockPos(x, y, z))); machine.owner(Objects.requireNonNull(scene.player.getUUID())); return machine;
    }
}
