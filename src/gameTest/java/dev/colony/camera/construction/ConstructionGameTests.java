package dev.colony.camera.construction;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.planning.ProjectionCodec;
import dev.colony.camera.network.ConstructionNetwork;
import dev.colony.camera.network.ConstructionPayload;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.storage.WarehouseInventory;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.storage.StorageMenu;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.preset.BlockPreset;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.network.NetworkDirection;
import io.netty.buffer.Unpooled;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
public final class ConstructionGameTests {
    @GameTest(template = "construction", timeoutTicks = 450, batch = "construction_sweep_regression")
    public static void blockedEarlierSweepDoesNotRecycleConstructionMaterials(GameTestHelper helper) {
        Scene scene = scene(helper);
        MachineBlockEntity warehouse = machine(helper, scene, 2, 1, 4, ColonyContent.WAREHOUSE.get());
        warehouse.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE)));
        for (int x = 8; x <= 10; x++) for (int z = 8; z <= 10; z++) for (int y = 1; y <= 3; y++) {
            if (x != 9 || z != 9 || y == 3) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.STONE));
        }
        BlockPos sealed = Objects.requireNonNull(helper.absolutePos(new BlockPos(9, 1, 9)));
        ItemEntity unreachable = new ItemEntity(Objects.requireNonNull(helper.getLevel()), sealed.getX() + .5, sealed.getY() + .1,
                sealed.getZ() + .5, new ItemStack(Objects.requireNonNull(Items.DIAMOND)));
        unreachable.setTarget(scene.player.getUUID()); unreachable.setNoPickUpDelay(); unreachable.setNoGravity(true);
        unreachable.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); helper.getLevel().addFreshEntity(unreachable);
        dev.colony.camera.work.ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel())).sweep(Objects.requireNonNull(scene.player),
                Objects.requireNonNull(helper.absolutePos(new BlockPos(8, 1, 8))), Objects.requireNonNull(helper.absolutePos(new BlockPos(10, 3, 10))));
        ConstructionProject building = project(helper, scene, 10, 3, 5);
        helper.succeedWhen(() -> {
            helper.assertTrue(building.finished(), "Earlier blocked sweep kept returning construction material: " + building.reason);
            helper.assertTrue(unreachable.isAlive() && unreachable.getItem().getCount() == 1 && warehouse.inventory.getStackInSlot(0).isEmpty()
                    && helper.getBlockState(new BlockPos(10, 1, 3)).is(Objects.requireNonNull(Blocks.COBBLESTONE)), "Blocked sweep or construction changed material quantities");
        });
    }

    private record Scene(FakePlayer player, ConstructionManager manager, WorkerEntity worker) {}

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void creativeTabPublishesAllFacilitiesAndUpgrades(GameTestHelper helper) {
        var tab = ColonyContent.COLONY_TAB.get();
        tab.buildContents(new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(Objects.requireNonNull(helper.getLevel().enabledFeatures()), true, Objects.requireNonNull(helper.getLevel().registryAccess())));
        helper.assertTrue(tab.getDisplayItems().size() == 12, "Creative colony tab is incomplete");
        for (var item : List.of(ColonyContent.PRINTING_CORE.get().asItem(), ColonyContent.WAREHOUSE.get().asItem(), ColonyContent.CONTROLLER.get().asItem(),
                ColonyContent.PORT.get().asItem(), ColonyContent.CABLE.get().asItem(), ColonyContent.CAPACITY_UPGRADE.get(), ColonyContent.STACK_UPGRADE.get(),
                ColonyContent.COLONY_WORKBENCH.get().asItem(), ColonyContent.RESEARCH_DESK.get().asItem(), ColonyContent.RESIDENCE.get().asItem(), ColonyContent.ACADEMY.get().asItem(), ColonyContent.ACADEMY_UPGRADE.get())) {
            helper.assertTrue(tab.contains(new ItemStack(Objects.requireNonNull(item))), "Missing creative/search entry: " + item);
        }
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 400)
    public static void groundPickupAndRealConstruction(GameTestHelper helper) {
        Scene scene = scene(helper);
        ConstructionProject project = project(helper, scene, 7, 7, 5);
        ItemEntity material = material(helper, scene, 3, 3, 1);
        helper.succeedWhen(() -> {
            helper.assertTrue(project.finished(), "Worker has not finished");
            helper.assertTrue(helper.getBlockState(new BlockPos(7, 1, 7)).is(Objects.requireNonNull(Blocks.COBBLESTONE)), "Actual block was not placed");
            helper.assertTrue(!material.isAlive() && scene.worker.getMainHandItem().isEmpty(), "Material conservation failed");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 450)
    public static void highPriorityWinsOneItemThenResumesAfterSupply(GameTestHelper helper) {
        Scene scene = scene(helper);
        worker(helper, scene.player, 3, 2);
        ConstructionProject low = project(helper, scene, 7, 6, 1);
        ConstructionProject high = project(helper, scene, 8, 6, 9);
        material(helper, scene, 3, 3, 1);
        helper.runAtTickTime(180, () -> {
            helper.assertTrue(high.finished() && !low.finished(), "Low priority consumed the only material or higher job stalled");
            material(helper, scene, 4, 3, 1);
        });
        helper.succeedWhen(() -> helper.assertTrue(high.finished() && low.finished(), "Supply did not resume the second project: " + low.reason
                + ", worker=" + scene.worker.position() + ", held=" + scene.worker.getMainHandItem() + ", assigned=" + (scene.worker.assignment != null)));
    }

    @GameTest(template = "construction", timeoutTicks = 200)
    public static void samePriorityUsesCreationOrder(GameTestHelper helper) {
        Scene scene = scene(helper); worker(helper, scene.player, 3, 2);
        ConstructionProject first = project(helper, scene, 8, 7, 5);
        ConstructionProject second = project(helper, scene, 5, 6, 5);
        material(helper, scene, 3, 3, 1);
        helper.runAtTickTime(180, () -> {
            helper.assertTrue(first.finished() && !second.finished(), "Same-priority FIFO violated: first=" + first.reason + "/" + first.completed.cardinality()
                    + " second=" + second.reason + "/" + second.completed.cardinality()); helper.succeed();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 140)
    public static void sealedGroundItemIsNotAvailable(GameTestHelper helper) {
        Scene scene = scene(helper); ConstructionProject project = project(helper, scene, 6, 3, 5);
        for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) for (int y = 1; y <= 4; y++) {
            if (x != 8 || z != 8 || y == 4) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.STONE));
        }
        ItemEntity material = material(helper, scene, 8, 8, 1);
        helper.runAtTickTime(100, () -> {
            helper.assertTrue(!project.finished() && material.isAlive() && material.getItem().getCount() == 1, "Unreachable material was consumed");
            helper.assertTrue(scene.worker.getMainHandItem().isEmpty(), "Worker picked up through a wall"); helper.succeed();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 400)
    public static void vanishedSourceReleasesAndFindsReplacement(GameTestHelper helper) {
        Scene scene = scene(helper); ConstructionProject project = project(helper, scene, 8, 7, 5);
        ItemEntity original = material(helper, scene, 9, 2, 1);
        helper.runAtTickTime(10, original::discard);
        helper.runAtTickTime(70, () -> {
            helper.assertTrue(!project.finished(), "Project fabricated material after source vanished"); material(helper, scene, 3, 3, 1);
        });
        helper.succeedWhen(() -> helper.assertTrue(project.finished(), "Replacement material was never used"));
    }

    @GameTest(template = "construction", timeoutTicks = 160)
    public static void pausedCarriedMaterialAndSaveRoundTrip(GameTestHelper helper) {
        Scene scene = scene(helper); ConstructionProject project = project(helper, scene, 9, 9, 5);
        material(helper, scene, 2, 3, 1);
        boolean[] paused = {false}; long[] pauseTick = {0};
        helper.onEachTick(() -> {
            if (!paused[0] && !scene.worker.getMainHandItem().isEmpty()) {
                paused[0] = true; pauseTick[0] = helper.getLevel().getGameTime();
                scene.manager.update(Objects.requireNonNull(scene.player.getUUID()), project.id(), "pause", 0);
                CompoundTag tag = new CompoundTag(); scene.worker.saveWithoutId(tag);
                WorkerEntity restored = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); restored.load(tag);
                helper.assertTrue(scene.player.getUUID().equals(restored.owner()) && restored.getMainHandItem().getCount() == 1, "Transport inventory/owner did not survive save");
                ConstructionManager saved = ConstructionManager.load(scene.manager.save(new CompoundTag()));
                helper.assertTrue(saved.projects(Objects.requireNonNull(scene.player.getUUID())).get(0).paused, "Pause did not survive save");
            }
            if (paused[0] && helper.getLevel().getGameTime() - pauseTick[0] > 30) {
                helper.assertTrue(!project.finished() && scene.worker.getMainHandItem().getCount() == 1, "Pause lost or consumed carried material");
                helper.assertTrue(scene.worker.assignment == null, "Pause left an active assignment"); helper.succeed();
            }
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void doorBedAndDoubleSlabBillUsesItems(GameTestHelper helper) {
        var cells = Objects.requireNonNull(List.of(
                new BlockPreset.Cell(0, 0, 0, new BlockPreset.State("minecraft:red_bed", Objects.requireNonNull(Map.of("part", "foot", "facing", "south")))),
                new BlockPreset.Cell(0, 0, 1, new BlockPreset.State("minecraft:red_bed", Objects.requireNonNull(Map.of("part", "head", "facing", "south")))),
                new BlockPreset.Cell(1, 0, 0, new BlockPreset.State("minecraft:oak_door", Objects.requireNonNull(Map.of("half", "lower")))),
                new BlockPreset.Cell(1, 1, 0, new BlockPreset.State("minecraft:oak_door", Objects.requireNonNull(Map.of("half", "upper")))),
                new BlockPreset.Cell(2, 0, 0, new BlockPreset.State("minecraft:stone_slab", Objects.requireNonNull(Map.of("type", "double"))))));
        var preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "Materials", 3, 2, 2, cells);
        for (int turn = 0; turn < 4; turn++) for (boolean mirror : List.of(false, true)) {
            BuildPlan plan = BuildPlan.compile(new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, 0, 0, 0, turn, mirror, true));
            helper.assertTrue(plan.units().size() == 3 && plan.units().stream().mapToInt((BuildPlan.Unit checkedElement) -> Objects.requireNonNull(checkedElement).count()).sum() == 4, "Block count was mistaken for item bill");
        }
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void unsupportedSaveIsPreservedAndReadOnly(GameTestHelper helper) {
        CompoundTag original = new CompoundTag(); original.putInt("schemaVersion", 999);
        original.putString("futurePayload", "Must survive this version");
        ConstructionManager loaded = ConstructionManager.load(original);
        helper.assertTrue(loaded.readOnly() && loaded.save(new CompoundTag()).equals(original), "Unsupported save was replaced or made writable");
        CompoundTag malformed = new CompoundTag(); malformed.putInt("schemaVersion", 1); malformed.putLong("nextSequence", 0);
        ListTag wrongList = new ListTag(); wrongList.add(StringTag.valueOf("Not a project")); malformed.put("projects", wrongList);
        ConstructionManager damaged = ConstructionManager.load(malformed);
        helper.assertTrue(damaged.readOnly() && damaged.save(new CompoundTag()).equals(malformed), "Wrong list type was silently replaced");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void largePresetFitsActualServerboundPacket(GameTestHelper helper) {
        var cells = new ArrayList<BlockPreset.Cell>();
        for (int index = 0; index < 8192; index++) cells.add(new BlockPreset.Cell(index % 32, index / 512, index / 32 % 16,
                new BlockPreset.State("minecraft:cobblestone", Objects.requireNonNull(Map.of()))));
        var preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "大型中文预设施工", 32, 16, 16, cells);
        var placement = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, 1, 2, 3, 0, false, true);
        String json = ProjectionCodec.encode(placement).toString();
        var packet = (ServerboundCustomPayloadPacket) ConstructionNetwork.CHANNEL.toVanillaPacket(new ConstructionNetwork.Request("create", placement.id(), 5, Objects.requireNonNull(json)), NetworkDirection.PLAY_TO_SERVER);
        FriendlyByteBuf wire = new FriendlyByteBuf(Objects.requireNonNull(Unpooled.buffer()));
        try {
            packet.write(wire);
            var received = new ServerboundCustomPayloadPacket(wire);
            FriendlyByteBuf payload = received.getData();
            try {
                helper.assertTrue(payload.readableBytes() <= 32767, "Packet exceeds vanilla serverbound limit");
                helper.assertTrue(payload.readVarInt() == 0 && payload.readUtf(16).equals("create")
                        && payload.readUUID().equals(placement.id()) && payload.readInt() == 5, "Request header changed in transit");
                byte[] compressed = new byte[payload.readVarInt()]; payload.readBytes(compressed);
                helper.assertTrue(ConstructionPayload.decode(compressed).equals(json), "Large preset changed in transit");
            } finally { payload.release(); }
        } finally { wire.release(); packet.getData().release(); }
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 220)
    public static void protectedPlacementRollsBackWithoutConsuming(GameTestHelper helper) {
        Scene scene = scene(helper); ConstructionProject project = project(helper, scene, 7, 7, 5);
        material(helper, scene, 3, 3, 1);
        Consumer<BlockEvent.EntityPlaceEvent> protection = event -> {
            var entity = event.getEntity();
            if (entity != null && entity.getUUID().equals(scene.player.getUUID())) event.setCanceled(true);
        };
        MinecraftForge.EVENT_BUS.addListener(protection);
        helper.runAtTickTime(180, () -> {
            MinecraftForge.EVENT_BUS.unregister(protection);
            helper.assertTrue(!project.finished() && helper.getBlockState(new BlockPos(7, 1, 7)).isAir(), "Protection left a placed block");
            helper.assertTrue(scene.worker.getMainHandItem().getCount() == 1, "Denied placement consumed its material"); helper.succeed();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 400)
    public static void missingHighPriorityMaterialDoesNotDeadlockLowerJob(GameTestHelper helper) {
        Scene scene = scene(helper);
        ConstructionProject high = project(helper, scene, 8, 7, 9, "minecraft:bricks");
        ConstructionProject low = project(helper, scene, 7, 6, 1);
        material(helper, scene, 3, 3, 1);
        helper.succeedWhen(() -> helper.assertTrue(low.finished() && !high.finished(), "Missing brick material stalled an independent cobblestone job"));
    }

    @GameTest(template = "construction", timeoutTicks = 400)
    public static void printingCoreSpawnsAtGroundDespiteFlyingPlayerAndBuilds(GameTestHelper helper) {
        Scene scene = scene(helper); scene.worker.discard();
        MachineBlockEntity core = machine(helper, scene, 4, 1, 4, ColonyContent.PRINTING_CORE.get());
        scene.player.setPos(scene.player.getX(), scene.player.getY() + 8, scene.player.getZ());
        helper.assertTrue(scene.manager.printWorker(Objects.requireNonNull(scene.player), Objects.requireNonNull(core.getBlockPos())).equals("success"), "Flying player prevented core print");
        helper.assertTrue(scene.manager.printWorker(Objects.requireNonNull(scene.player), Objects.requireNonNull(core.getBlockPos())).equals("cooldown"), "Core allowed duplicate print before ready");
        ConstructionProject project = project(helper, scene, 7, 7, 5); material(helper, scene, 3, 6, 1);
        helper.succeedWhen(() -> helper.assertTrue(project.finished(), "Printed worker did not pick up and build"));
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void coreRejectsBlockedSpawnAndForeignOwner(GameTestHelper helper) {
        Scene scene = scene(helper); MachineBlockEntity core = machine(helper, scene, 5, 1, 5, ColonyContent.PRINTING_CORE.get());
        FakePlayer stranger = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "Stranger"));
        helper.assertTrue(scene.manager.printWorker(Objects.requireNonNull(stranger), Objects.requireNonNull(core.getBlockPos())).equals("no_core"), "Foreign player printed from owned core");
        for (int x = 2; x <= 8; x++) for (int z = 2; z <= 8; z++) for (int y = 1; y <= 5; y++) {
            if (x != 5 || z != 5 || y != 1) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.STONE));
        }
        helper.assertTrue(scene.manager.printWorker(Objects.requireNonNull(scene.player), Objects.requireNonNull(core.getBlockPos())).equals("blocked"), "Worker printed into blocked ground"); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void upgradedWarehousePreservesLargeCountsAndRefusesOverflow(GameTestHelper helper) {
        WarehouseInventory inventory = new WarehouseInventory(() -> {});
        helper.assertTrue(inventory.upgrade(true, 1) && inventory.upgrade(true, 1) && inventory.upgrade(true, 1), "Stack upgrades failed");
        helper.assertTrue(inventory.insertItem(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 512), false).isEmpty(), "Warehouse stack capacity remained vanilla");
        helper.assertTrue(!inventory.upgrade(true, -1), "Overflowing stack downgrade accepted");
        WarehouseInventory restored = new WarehouseInventory(() -> {}); restored.load(inventory.save());
        helper.assertTrue(!restored.readOnly() && restored.getStackInSlot(0).getCount() == 512, "Large count truncated during NBT save");
        helper.assertTrue(restored.extractItem(0, 512, false).getCount() == 64 && restored.getStackInSlot(0).getCount() == 448, "Output did not respect normal item stack limit");
        helper.assertTrue(restored.upgrade(false, 1), "Capacity upgrade failed"); restored.setStackInSlot(30, new ItemStack(Objects.requireNonNull(Items.STONE)));
        helper.assertTrue(!restored.upgrade(false, -1) && restored.getSlots() == 54, "Occupied capacity downgrade lost an item");
        ItemStack remaining = restored.insertItem(1, new ItemStack(Objects.requireNonNull(Items.IRON_PICKAXE), 2), false);
        helper.assertTrue(remaining.getCount() == 1 && restored.getStackInSlot(1).getCount() == 1, "Unstackable item multiplied");
        CompoundTag damaged = inventory.save(); damaged.putString("capacityLevel", "not an integer");
        WarehouseInventory protectedInventory = new WarehouseInventory(() -> {}); protectedInventory.load(damaged);
        helper.assertTrue(protectedInventory.readOnly() && protectedInventory.save().equals(damaged)
                && protectedInventory.extractItem(0, 1, false).isEmpty() && !protectedInventory.upgrade(false, 1), "Malformed inventory was overwritten or modified");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 400)
    public static void workerUsesWarehouseMaterial(GameTestHelper helper) {
        Scene scene = scene(helper); ConstructionProject project = project(helper, scene, 7, 7, 5);
        MachineBlockEntity box = machine(helper, scene, 3, 1, 4, ColonyContent.WAREHOUSE.get()); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE)));
        helper.succeedWhen(() -> {
            helper.assertTrue(project.finished(), "Worker did not build with warehouse material: " + project.reason);
            helper.assertTrue(box.inventory.getStackInSlot(0).isEmpty() && scene.worker.getMainHandItem().isEmpty(), "Warehouse pickup duplicated material");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void networkPortsShareInventoryAndDisconnectSafely(GameTestHelper helper) {
        Scene scene = scene(helper);
        machine(helper, scene, 6, 1, 5, ColonyContent.CONTROLLER.get());
        MachineBlockEntity box = machine(helper, scene, 7, 1, 5, ColonyContent.WAREHOUSE.get());
        MachineBlockEntity first = machine(helper, scene, 5, 1, 5, ColonyContent.PORT.get());
        MachineBlockEntity second = machine(helper, scene, 6, 1, 6, ColonyContent.PORT.get());
        box.inventory.upgrade(true, 1); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 128));
        var a = StorageResources.resolve(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(first.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()), Objects.requireNonNull(Items.COBBLESTONE));
        var b = StorageResources.resolve(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(second.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()), Objects.requireNonNull(Items.COBBLESTONE));
        helper.assertTrue(a != null && b != null && a.identity().equals(b.identity()) && a.count() == 128 && b.count() == 128, "Ports duplicated or separated reservation identity");
        helper.assertTrue(StorageResources.resolve(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(box.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()), Objects.requireNonNull(Items.COBBLESTONE)) == null, "Connected warehouse published duplicate source");
        StorageAccess access = StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(first.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()));
        helper.assertTrue(access.extract(new ItemStack(Objects.requireNonNull(Items.COBBLESTONE)), 64, false).getCount() == 64 && box.inventory.getStackInSlot(0).getCount() == 64, "Port extraction was not real");
        helper.setBlock(new BlockPos(6, 1, 5), Objects.requireNonNull(Blocks.AIR));
        helper.assertTrue(StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(first.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).inventories.isEmpty() && box.inventory.getStackInSlot(0).getCount() == 64, "Disconnect lost or exposed inventory");
        machine(helper, scene, 6, 1, 5, ColonyContent.CONTROLLER.get());
        helper.assertTrue(StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(second.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(new ItemStack(Objects.requireNonNull(Items.COBBLESTONE))) == 64, "Reconnect did not restore inventory"); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 400)
    public static void workerUsesPortWithoutWalkingToSealedWarehouse(GameTestHelper helper) {
        Scene scene = scene(helper); ConstructionProject project = project(helper, scene, 6, 7, 5);
        for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) for (int y = 1; y <= 4; y++) {
            if (x != 8 || z != 8 || y == 4) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.STONE));
        }
        MachineBlockEntity box = machine(helper, scene, 8, 1, 8, ColonyContent.WAREHOUSE.get()); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE)));
        machine(helper, scene, 4, 1, 4, ColonyContent.PORT.get()); machine(helper, scene, 5, 1, 4, ColonyContent.CONTROLLER.get());
        for (int x = 5; x <= 8; x++) machine(helper, scene, x, 0, 4, ColonyContent.CABLE.get());
        for (int z = 5; z <= 8; z++) machine(helper, scene, 8, 0, z, ColonyContent.CABLE.get());
        helper.succeedWhen(() -> {
            helper.assertTrue(project.finished(), "Worker could not use accessible port: " + project.reason);
            helper.assertTrue(box.inventory.getStackInSlot(0).isEmpty(), "Port fabricated material");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void storageMenuPickupAndFullShiftTransferConserveCounts(GameTestHelper helper) {
        Scene scene = scene(helper); MachineBlockEntity box = machine(helper, scene, 3, 1, 3, ColonyContent.WAREHOUSE.get());
        box.inventory.upgrade(true, 1); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 128));
        StorageMenu menu = new StorageMenu(42, Objects.requireNonNull(scene.player.getInventory()), Objects.requireNonNull(box.getBlockPos()));
        menu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, Objects.requireNonNull(scene.player));
        helper.assertTrue(menu.getCarried().getCount() == 64 && box.inventory.getStackInSlot(0).getCount() == 64, "Menu pickup lost hidden count");
        menu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, Objects.requireNonNull(scene.player));
        helper.assertTrue(menu.getCarried().isEmpty() && box.inventory.getStackInSlot(0).getCount() == 128, "Menu deposit lost count");
        for (int slot = 0; slot < box.inventory.getSlots(); slot++) box.inventory.setStackInSlot(slot, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 128));
        scene.player.getInventory().setItem(9, new ItemStack(Objects.requireNonNull(Items.STONE)));
        helper.assertTrue(menu.quickMoveStack(Objects.requireNonNull(scene.player), StorageMenu.PAGE_SIZE).isEmpty() && scene.player.getInventory().getItem(9).getCount() == 1, "Full shift transfer did not terminate safely"); helper.succeed();
    }

    private static MachineBlockEntity machine(GameTestHelper helper, Scene scene, int x, int y, int z, net.minecraft.world.level.block.Block block) {
        helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(block));
        MachineBlockEntity machine = (MachineBlockEntity) helper.getBlockEntity(new BlockPos(x, y, z)); Objects.requireNonNull(machine).owner(scene.player.getUUID()); return machine;
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void chestUpgradeThroughPlayerInteractionPreservesContents(GameTestHelper helper) {
        Scene scene = scene(helper);
        BlockPos relative = new BlockPos(3, 1, 3); BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, Objects.requireNonNull(Blocks.CHEST.defaultBlockState().setValue(Objects.requireNonNull(net.minecraft.world.level.block.ChestBlock.FACING), net.minecraft.core.Direction.EAST)));
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(relative);
        Objects.requireNonNull(chest).setItem(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 64)); chest.setItem(26, new ItemStack(Objects.requireNonNull(Items.DIAMOND), 7));
        chest.setCustomName(Objects.requireNonNull(net.minecraft.network.chat.Component.literal("Named warehouse")));
        scene.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Objects.requireNonNull(ColonyContent.CAPACITY_UPGRADE.get()), 2));
        scene.player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var hit = new net.minecraft.world.phys.BlockHitResult(Objects.requireNonNull(Vec3.atCenterOf(Objects.requireNonNull(pos))), net.minecraft.core.Direction.UP, Objects.requireNonNull(pos), false);
        scene.player.gameMode.useItemOn(Objects.requireNonNull(scene.player), Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.player.getMainHandItem()), net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        helper.assertTrue(helper.getBlockEntity(relative) instanceof MachineBlockEntity, "Chest UI intercepted the upgrade");
        var box = (MachineBlockEntity) helper.getBlockEntity(relative);
        helper.assertTrue(Objects.requireNonNull(box).inventory.getSlots() == 54 && box.permits(scene.player.getUUID()) && scene.player.getMainHandItem().getCount() == 1, "Upgrade capacity, owner or cost incorrect");
        helper.assertTrue(box.inventory.getStackInSlot(0).getCount() == 64 && box.inventory.getStackInSlot(26).getCount() == 7
                && box.getDisplayName().getString().equals("Named warehouse") && box.getBlockState().getValue(Objects.requireNonNull(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) == net.minecraft.core.Direction.EAST, "Conversion lost inventory, name or facing");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, Objects.requireNonNull(new AABB(Objects.requireNonNull(pos)).inflate(2))).isEmpty(), "Conversion duplicated dropped inventory");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void protectedChestUpgradeRollsBackWithoutLoss(GameTestHelper helper) {
        Scene scene = scene(helper); BlockPos relative = new BlockPos(3, 1, 3); BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, Objects.requireNonNull(Blocks.CHEST));
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(relative);
        Objects.requireNonNull(chest).setItem(0, new ItemStack(Objects.requireNonNull(Items.DIAMOND), 7));
        scene.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Objects.requireNonNull(ColonyContent.CAPACITY_UPGRADE.get())));
        Consumer<BlockEvent.EntityPlaceEvent> protection = event -> { if (event.getPos().equals(pos)) event.setCanceled(true); };
        MinecraftForge.EVENT_BUS.addListener(protection);
        try {
            var hit = new net.minecraft.world.phys.BlockHitResult(Objects.requireNonNull(Vec3.atCenterOf(Objects.requireNonNull(pos))), net.minecraft.core.Direction.UP, Objects.requireNonNull(pos), false);
            scene.player.gameMode.useItemOn(Objects.requireNonNull(scene.player), Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.player.getMainHandItem()), net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        } finally { MinecraftForge.EVENT_BUS.unregister(protection); }
        helper.assertTrue(helper.getBlockEntity(relative) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity restored
                && restored.getItem(0).getCount() == 7 && scene.player.getMainHandItem().getCount() == 1, "Protected conversion lost chest material or upgrade");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, Objects.requireNonNull(new AABB(Objects.requireNonNull(pos)).inflate(2))).isEmpty(), "Rollback duplicated inventory");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 400)
    public static void workerUsesOrdinaryChestMaterial(GameTestHelper helper) {
        Scene scene = scene(helper); ConstructionProject project = project(helper, scene, 7, 7, 5);
        helper.setBlock(new BlockPos(3, 1, 4), Objects.requireNonNull(Blocks.CHEST));
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(new BlockPos(3, 1, 4));
        Objects.requireNonNull(chest).setItem(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE)));
        CompoundTag locked = chest.saveWithoutMetadata(); locked.putString("Lock", "secret"); chest.load(locked);
        helper.assertTrue(StorageResources.resolve(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(chest.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()), Objects.requireNonNull(Items.COBBLESTONE)) == null, "Locked container exposed material");
        locked.remove("Lock"); chest.load(locked);
        helper.succeedWhen(() -> {
            helper.assertTrue(project.finished(), "Worker did not use normal Forge chest inventory: " + project.reason);
            helper.assertTrue(chest.getItem(0).isEmpty(), "Chest material was not consumed");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 450)
    public static void twoPortsAndTwoWorkersRespectProjectPriority(GameTestHelper helper) {
        Scene scene = scene(helper); worker(helper, scene.player, 3, 2);
        ConstructionProject low = project(helper, scene, 7, 6, 1); ConstructionProject high = project(helper, scene, 8, 6, 9);
        machine(helper, scene, 4, 1, 4, ColonyContent.CONTROLLER.get());
        machine(helper, scene, 3, 1, 4, ColonyContent.PORT.get()); machine(helper, scene, 4, 1, 3, ColonyContent.PORT.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 4, ColonyContent.WAREHOUSE.get());
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE)));
        helper.runAtTickTime(180, () -> {
            helper.assertTrue(high.finished() && !low.finished() && box.inventory.getStackInSlot(0).isEmpty(), "Shared inventory or strict priority failed");
            box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE)));
        });
        helper.succeedWhen(() -> helper.assertTrue(high.finished() && low.finished() && box.inventory.getStackInSlot(0).isEmpty(), "Network supply did not resume lower job: " + low.reason));
    }
    @GameTest(template = "construction", timeoutTicks = 20, batch = "navigation_regression")
    public static void feasibilityProbesPreserveTheActiveWalkingTarget(GameTestHelper helper) {
        Scene scene = scene(helper);
        WorkerEntity worker = Objects.requireNonNull(scene.worker);
        worker.setOnGround(true);
        var level = Objects.requireNonNull(helper.getLevel());
        BlockPos walking = Objects.requireNonNull(helper.absolutePos(new BlockPos(9, 1, 2)));
        BlockPos probe = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 9)));
        var navigation = worker.getNavigation();
        navigation.setCanFloat(true);
        helper.assertTrue(worker.planningNavigation().canFloat(), "Planner lost the execution navigator's water setting");
        var active = Objects.requireNonNull(navigation.createPath(walking, 0));
        helper.assertTrue(navigation.moveTo(active, 1.0), "Initial walk did not start");
        var first = Objects.requireNonNull(dev.colony.camera.resources.GroundResources.path(level, worker, probe));
        var second = Objects.requireNonNull(dev.colony.camera.resources.GroundResources.path(level, worker, probe));
        helper.assertTrue(first.getTarget().equals(probe) && second.getTarget().equals(probe), "A feasibility query reused the route to a different target");
        helper.assertTrue(navigation.getPath() == active && walking.equals(navigation.getTargetPos()), "Feasibility probes changed the active navigation target");
        helper.assertTrue(dev.colony.camera.resources.GroundResources.follow(level, worker, first)
                && probe.equals(navigation.getTargetPos()), "Starting a probed route did not update the actual movement target");
        worker.discard();
        helper.succeed();
    }

    private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        AABB bounds = new AABB(Objects.requireNonNull(helper.absolutePos(new BlockPos(0, 0, 0))), Objects.requireNonNull(helper.absolutePos(new BlockPos(12, 6, 12))));
        helper.getLevel().getEntitiesOfClass(WorkerEntity.class, bounds).forEach((WorkerEntity checkedElement) -> Objects.requireNonNull(checkedElement).discard());
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds).forEach((ItemEntity checkedElement) -> Objects.requireNonNull(checkedElement).discard());
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "BuilderTest"));
        BlockPos position = helper.absolutePos(new BlockPos(2, 1, 2)); player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        return new Scene(player, ConstructionManager.get(Objects.requireNonNull(helper.getLevel())), worker(helper, player, 2, 2));
    }
    private static WorkerEntity worker(GameTestHelper helper, FakePlayer player, int x, int z) {
        var worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel())));
        BlockPos pos = helper.absolutePos(new BlockPos(x, 1, z)); worker.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        worker.owner(Objects.requireNonNull(player.getUUID())); helper.getLevel().addFreshEntity(worker); return worker;
    }
    private static ConstructionProject project(GameTestHelper helper, Scene scene, int x, int z, int priority) {
        return project(helper, scene, x, z, priority, "minecraft:cobblestone");
    }
    private static ConstructionProject project(GameTestHelper helper, Scene scene, int x, int z, int priority, String block) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, 1, z));
        var state = new BlockPreset.State(Objects.requireNonNull(block), Objects.requireNonNull(Map.of()));
        var cells = Objects.requireNonNull(List.of(new BlockPreset.Cell(0, 0, 0, state)));
        var preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "Build", 1, 1, 1, cells);
        var placement = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, pos.getX(), pos.getY(), pos.getZ(), 0, false, true);
        scene.manager.create(Objects.requireNonNull(scene.player), placement, priority);
        return scene.manager.projects(Objects.requireNonNull(scene.player.getUUID())).stream().filter(p -> p.id().equals(placement.id())).findFirst().orElseThrow();
    }
    private static ItemEntity material(GameTestHelper helper, Scene scene, int x, int z, int count) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, 1, z));
        var item = new ItemEntity(Objects.requireNonNull(helper.getLevel()), pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), count));
        item.setTarget(scene.player.getUUID()); item.setNoPickUpDelay(); item.setNoGravity(true);
        item.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); helper.getLevel().addFreshEntity(item); return item;
    }
}
