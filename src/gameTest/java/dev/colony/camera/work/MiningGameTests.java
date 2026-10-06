package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.construction.ConstructionProject;
import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.preset.BlockPreset;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.tech.TechProgress;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
public final class MiningGameTests {
    private record Scene(FakePlayer player, WorkerEntity worker, ReservationLedger ledger) {}

    @GameTest(template = "construction", timeoutTicks = 180)
    public static void stoneUsesRealToolDurabilityDropsAndSavedSkill(GameTestHelper helper) {
        Scene scene = scene(helper); helper.setBlock(new BlockPos(3, 1, 3), Objects.requireNonNull(Blocks.STONE));
        ItemStack pick = new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)); pick.setDamageValue(11);
        scene.worker.setItemSlot(EquipmentSlot.OFFHAND, pick);
        scene.worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Objects.requireNonNull(Items.DIRT), 3));
        UUID delivery = Objects.requireNonNull(UUID.randomUUID()); scene.worker.cargoTask(delivery);
        ColonyJob job = job(helper, scene, new BlockPos(3, 1, 3)); WorkTask task = task(helper, scene, job);
        helper.onEachTick(() -> MiningService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(task), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished() && helper.getBlockState(new BlockPos(3, 1, 3)).isAir(), "Stone was not mined");
            helper.assertTrue(scene.worker.getOffhandItem().is(Objects.requireNonNull(Items.WOODEN_PICKAXE)) && scene.worker.getOffhandItem().getDamageValue() == 12,
                    "Real tool was not worn exactly once");
            helper.assertTrue(scene.worker.getMainHandItem().getCount() == 3 && scene.worker.miningXp() == 1, "Mining consumed transport inventory or repeated XP");
            helper.assertTrue(drops(helper, Items.COBBLESTONE) == 1, "Normal survival cobblestone drop was lost or duplicated");
            CompoundTag saved = new CompoundTag(); scene.worker.saveWithoutId(saved);
            var restored = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); restored.load(saved);
            helper.assertTrue(restored.miningXp() == 1 && restored.getOffhandItem().getDamageValue() == 12
                    && restored.getMainHandItem().getCount() == 3 && delivery.equals(restored.cargoTask()), "Saved skill/tool/material/cargo owner did not survive loading");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void unavailableToolsAndForeignGroundOwnerRemainBlocked(GameTestHelper helper) {
        Scene scene = scene(helper); helper.setBlock(new BlockPos(3, 1, 3), Objects.requireNonNull(Blocks.STONE));
        ColonyJob job = job(helper, scene, new BlockPos(3, 1, 3));
        helper.assertTrue(MiningService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()) == null
                && job.reason.equals("missing_tool"), "Worker fabricated a pickaxe");
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 3));
        ItemEntity foreign = new ItemEntity(Objects.requireNonNull(helper.getLevel()), pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        foreign.setTarget(UUID.randomUUID()); foreign.setNoPickUpDelay(); foreign.setNoGravity(true); foreign.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); helper.getLevel().addFreshEntity(foreign);
        helper.assertTrue(MiningService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()) == null
                && foreign.isAlive() && scene.worker.getOffhandItem().isEmpty(), "Foreign tool was claimed");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 180)
    public static void warehouseDamagedNamedToolIsPhysicallyTakenAndWorn(GameTestHelper helper) {
        Scene scene = scene(helper); helper.setBlock(new BlockPos(3, 1, 3), Objects.requireNonNull(Blocks.STONE));
        helper.setBlock(new BlockPos(2, 1, 4), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        var warehouse = (MachineBlockEntity) helper.getBlockEntity(new BlockPos(2, 1, 4)); Objects.requireNonNull(warehouse).owner(scene.player.getUUID());
        ItemStack pick = new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)); pick.setDamageValue(8); pick.setHoverName(Component.literal("Old colony pick"));
        warehouse.inventory.setStackInSlot(0, pick);
        ColonyJob job = job(helper, scene, new BlockPos(3, 1, 3)); WorkTask task = task(helper, scene, job);
        helper.assertTrue(task.toolPickup, "Warehouse tool was not scheduled for real pickup");
        helper.onEachTick(() -> MiningService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), task, Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished() && warehouse.inventory.getStackInSlot(0).isEmpty(), "Warehouse tool was copied instead of extracted");
            helper.assertTrue(scene.worker.getOffhandItem().getDamageValue() == 9
                    && scene.worker.getOffhandItem().getHoverName().getString().equals("Old colony pick"), "Pickup discarded damage or custom name");
            helper.assertTrue(drops(helper, Items.COBBLESTONE) == 1 && drops(helper, Items.WOODEN_PICKAXE) == 0, "Material/tool conservation failed");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 180)
    public static void skillAndTechnologyGateMiningWithoutLosingSupply(GameTestHelper helper) {
        Scene scene = scene(helper); helper.setBlock(new BlockPos(3, 1, 3), Objects.requireNonNull(Blocks.IRON_ORE));
        scene.worker.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.STONE_PICKAXE)));
        ColonyJob job = job(helper, scene, new BlockPos(3, 1, 3));
        helper.assertTrue(MiningService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()) == null
                && job.reason.equals("mining_level"), "Level one miner bypassed skill gate");
        scene.worker.gainMiningExperience(16);
        helper.assertTrue(MiningService.minimumLevel(Objects.requireNonNull(Blocks.IRON_ORE.defaultBlockState())) == 2
                && MiningService.minimumLevel(Objects.requireNonNull(Blocks.DIAMOND_ORE.defaultBlockState())) == 3
                && MiningService.minimumLevel(Objects.requireNonNull(Blocks.OBSIDIAN.defaultBlockState())) == 4, "Ore mining grade mapping drifted");
        helper.assertTrue(!MiningService.usableTool(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(Blocks.IRON_ORE.defaultBlockState()), new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE))),
                "Correct skill allowed a wrong-tier pickaxe to destroy valuable ore without drops");
        helper.assertTrue(MiningService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()) == null
                && job.reason.equals("missing_tool"), "Locked stone technology was bypassed with an externally obtained tool");
        helper.assertTrue(TechProgress.get(Objects.requireNonNull(helper.getLevel())).advance(Objects.requireNonNull(scene.player.getUUID()), 0, () -> {}), "Stone research fixture failed");
        WorkTask task = task(helper, scene, job);
        helper.onEachTick(() -> MiningService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(task), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished() && drops(helper, Items.RAW_IRON) == 1 && scene.worker.miningXp() == 17, "Unlocked trained worker failed to mine correct ore drop");
            helper.assertTrue(scene.worker.getOffhandItem().getDamageValue() == 1, "Stone pick durability was not charged");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void protectedMiningEventPreservesBlockToolAndExperience(GameTestHelper helper) {
        Scene scene = scene(helper); BlockPos relative = new BlockPos(3, 1, 3); helper.setBlock(relative, Objects.requireNonNull(Blocks.STONE));
        scene.worker.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        ColonyJob job = job(helper, scene, relative); WorkTask task = task(helper, scene, job); task.progress = 10_000;
        Consumer<BlockEvent.BreakEvent> protection = event -> { if (event.getPos().equals(helper.absolutePos(relative))) event.setCanceled(true); };
        MinecraftForge.EVENT_BUS.addListener(protection);
        try { helper.assertTrue(MiningService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), task, Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()), "Protected task did not release"); }
        finally { MinecraftForge.EVENT_BUS.unregister(protection); }
        helper.assertTrue(helper.getBlockState(relative).is(Objects.requireNonNull(Blocks.STONE)) && !job.finished() && job.reason.equals("denied"), "Protection was ignored");
        helper.assertTrue(scene.worker.miningXp() == 0 && scene.worker.getOffhandItem().getDamageValue() == 0 && drops(helper, Items.COBBLESTONE) == 0,
                "Cancelled break consumed tool or invented XP/material");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void changedSelectedBlockAndFacilitiesAreNeverDestroyed(GameTestHelper helper) {
        Scene scene = scene(helper); BlockPos relative = new BlockPos(3, 1, 3); helper.setBlock(relative, Objects.requireNonNull(Blocks.DIRT));
        ColonyJob job = job(helper, scene, relative); WorkTask task = task(helper, scene, job);
        helper.setBlock(relative, Objects.requireNonNull(Blocks.DIAMOND_BLOCK));
        helper.assertTrue(MiningService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(task), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()) && job.finished()
                && helper.getBlockState(relative).is(Objects.requireNonNull(Blocks.DIAMOND_BLOCK)) && scene.worker.miningXp() == 0, "Changed player block was destroyed");
        helper.setBlock(relative, Objects.requireNonNull(Blocks.AIR)); ColonyJob empty = job(helper, scene, relative);
        helper.assertTrue(MiningService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(empty), Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()) == null
                && empty.finished(), "A removed selection member remained blocked forever");
        helper.setBlock(relative, Objects.requireNonNull(ColonyContent.PRINTING_CORE.get()));
        ColonyJob core = job(helper, scene, relative);
        helper.assertTrue(MiningService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(core), Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()) == null
                && core.reason.equals("protected_block"), "Printing core was included in bulk mining");
        helper.setBlock(relative, Objects.requireNonNull(Blocks.CHEST)); var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(relative);
        Objects.requireNonNull(chest).setItem(0, new ItemStack(Objects.requireNonNull(Items.DIAMOND), 7)); ColonyJob storage = job(helper, scene, relative);
        helper.assertTrue(MiningService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(storage), Objects.requireNonNull(Set.of()), new ReservationLedger(), helper.getLevel().getGameTime()) == null
                && storage.reason.equals("protected_block") && chest.getItem(0).getCount() == 7, "Container contents were exposed to bulk mining");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 450)
    public static void dispatchWalksWithWarehouseToolAndMinesStackedSelection(GameTestHelper helper) {
        Scene scene = scene(helper);
        BlockPos lower = new BlockPos(8, 1, 8), upper = new BlockPos(8, 2, 8);
        helper.setBlock(lower, Objects.requireNonNull(Blocks.STONE)); helper.setBlock(upper, Objects.requireNonNull(Blocks.STONE));
        helper.setBlock(new BlockPos(2, 1, 4), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        var warehouse = (MachineBlockEntity) helper.getBlockEntity(new BlockPos(2, 1, 4)); Objects.requireNonNull(warehouse).owner(scene.player.getUUID());
        ItemStack pick = new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)); pick.setDamageValue(6); warehouse.inventory.setStackInSlot(0, pick);
        ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        manager.mine(Objects.requireNonNull(scene.player), Objects.requireNonNull(List.of(helper.absolutePos(lower), helper.absolutePos(upper))));
        ColonyJob job = manager.jobs(Objects.requireNonNull(scene.player.getUUID())).get(0);
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished() && helper.getBlockState(lower).isAir() && helper.getBlockState(upper).isAir(),
                    "Dispatched volume stalled: " + job.reason + ", worker=" + scene.worker.position());
            helper.assertTrue(warehouse.inventory.getStackInSlot(0).isEmpty() && scene.worker.getOffhandItem().getDamageValue() == 8,
                    "Tool delivery/usage did not preserve physical inventory");
            helper.assertTrue(scene.worker.miningXp() == 2 && drops(helper, Items.COBBLESTONE) == 2, "Stacked mining lost or duplicated output");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 650)
    public static void highPriorityMiningThenSweepDeliversBeforeLowerConstruction(GameTestHelper helper) {
        Scene scene = scene(helper); BlockPos relative = new BlockPos(8, 1, 8); helper.setBlock(relative, Objects.requireNonNull(Blocks.STONE));
        helper.setBlock(new BlockPos(2, 1, 4), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        var warehouse = (MachineBlockEntity) helper.getBlockEntity(new BlockPos(2, 1, 4)); Objects.requireNonNull(warehouse).owner(scene.player.getUUID());
        warehouse.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        BlockPos constructionPos = helper.absolutePos(new BlockPos(5, 1, 7));
        var state = new BlockPreset.State("minecraft:cobblestone", Objects.requireNonNull(Map.of()));
        var cells = Objects.requireNonNull(List.of(new BlockPreset.Cell(0, 0, 0, state)));
        var preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "Low build", 1, 1, 1, cells);
        var placement = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, constructionPos.getX(), constructionPos.getY(), constructionPos.getZ(), 0, false, true);
        ConstructionManager construction = ConstructionManager.get(Objects.requireNonNull(helper.getLevel())); construction.create(Objects.requireNonNull(scene.player), placement, 1);
        ConstructionProject build = construction.projects(Objects.requireNonNull(scene.player.getUUID())).stream().filter(project -> project.id().equals(placement.id())).findFirst().orElseThrow();
        ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel())); manager.mine(Objects.requireNonNull(scene.player), Objects.requireNonNull(List.of(helper.absolutePos(relative))));
        ColonyJob mining = manager.jobs(Objects.requireNonNull(scene.player.getUUID())).get(0); manager.update(Objects.requireNonNull(scene.player.getUUID()), mining.id, "priority", 9);
        manager.sweep(Objects.requireNonNull(scene.player), Objects.requireNonNull(helper.absolutePos(new BlockPos(5, 1, 5))), Objects.requireNonNull(helper.absolutePos(new BlockPos(10, 3, 10))));
        ColonyJob sweep = manager.jobs(Objects.requireNonNull(scene.player.getUUID())).stream().filter(job -> job.kind == ColonyJob.Kind.SWEEP).findFirst().orElseThrow();
        boolean[] stored = {false};
        helper.onEachTick(() -> {
            stored[0] |= StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(warehouse.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(new ItemStack(Objects.requireNonNull(Items.COBBLESTONE))) == 1;
            if (build.finished()) helper.assertTrue(mining.finished() && sweep.finished(), "Lower construction stole the miner or sweep material");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(mining.finished() && sweep.finished() && build.finished(), "Unified jobs did not complete: mine=" + mining.reason + ", sweep=" + sweep.reason + ", build=" + build.reason);
            helper.assertTrue(stored[0] && sweep.done == 1 && helper.getBlockState(new BlockPos(5, 1, 7)).is(Objects.requireNonNull(Blocks.COBBLESTONE)),
                    "Native mined drop never entered colony warehouse before construction");
            helper.assertTrue(scene.worker.getMainHandItem().isEmpty() && scene.worker.getOffhandItem().getDamageValue() == 1
                    && drops(helper, Items.COBBLESTONE) == 0 && StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(warehouse.getBlockPos()), Objects.requireNonNull(scene.player.getUUID())).count(new ItemStack(Objects.requireNonNull(Items.COBBLESTONE))) == 0,
                    "Mine to warehouse to real construction duplicated or lost material");
        });
    }

    private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        AABB bounds = new AABB(Objects.requireNonNull(helper.absolutePos(new BlockPos(0, 0, 0))), Objects.requireNonNull(helper.absolutePos(new BlockPos(12, 6, 12))));
        helper.getLevel().getEntitiesOfClass(WorkerEntity.class, bounds).forEach((WorkerEntity checkedElement) -> Objects.requireNonNull(checkedElement).discard());
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds).forEach((ItemEntity checkedElement) -> Objects.requireNonNull(checkedElement).discard());
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "MiningTest"));
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 3)); player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); worker.owner(Objects.requireNonNull(player.getUUID()));
        worker.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0); helper.getLevel().addFreshEntity(worker);
        return new Scene(player, worker, new ReservationLedger());
    }

    private static ColonyJob job(GameTestHelper helper, Scene scene, BlockPos relative) {
        ColonyJob job = new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(scene.player.getUUID()), "MiningTest", ColonyJob.Kind.MINE, 5, 0);
        BlockPos pos = helper.absolutePos(Objects.requireNonNull(relative)); job.targets = Objects.requireNonNull(List.of(pos));
        BlockState state = helper.getLevel().getBlockState(Objects.requireNonNull(pos)); job.expectedStates = Objects.requireNonNull(List.of(BlockStateParser.serialize(Objects.requireNonNull(state)))); return job;
    }

    private static WorkTask task(GameTestHelper helper, Scene scene, ColonyJob job) {
        WorkTask task = MiningService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(Set.of()), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime());
        helper.assertTrue(task != null, "Mining task unavailable: " + job.reason); scene.worker.workTask = Objects.requireNonNull(task); return task;
    }

    private static int drops(GameTestHelper helper, net.minecraft.world.item.Item item) {
        AABB bounds = new AABB(Objects.requireNonNull(helper.absolutePos(new BlockPos(0, 0, 0))), Objects.requireNonNull(helper.absolutePos(new BlockPos(12, 6, 12))));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds).stream().filter(entity -> entity.getItem().is(Objects.requireNonNull(item))).mapToInt(entity -> entity.getItem().getCount()).sum();
    }
}
