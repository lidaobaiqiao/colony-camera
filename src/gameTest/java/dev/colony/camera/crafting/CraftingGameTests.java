package dev.colony.camera.crafting;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.preset.BlockPreset;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.tech.TechProgress;
import dev.colony.camera.work.ColonyJob;
import dev.colony.camera.work.ColonyWorkManager;
import dev.colony.camera.work.WorkTask;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
public final class CraftingGameTests {
    private record Scene(FakePlayer player, WorkerEntity worker, ReservationLedger ledger) {}
    private record Facility(String id, Item output, int count, Item unlock, Item[] grid) {}

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void survivalWorkbenchUnlocksAndConsumesEveryFacilityRecipe(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos station = helper.absolutePos(new BlockPos(5, 1, 5));
        helper.setBlock(new BlockPos(5, 1, 5), Objects.requireNonNull(Blocks.CRAFTING_TABLE));
        var limited = level.getGameRules().getRule(Objects.requireNonNull(GameRules.RULE_LIMITED_CRAFTING));
        boolean previous = limited.get(); limited.set(true, level.getServer());
        try {
            for (Facility facility : facilities()) {
                // Forge intentionally refuses advancements for FakePlayer. Use a normal
                // server player with only network transport stubbed for this recipe test.
                ServerPlayer player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "FacilityTest"));
                player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player) {
                    @Override public void send(@javax.annotation.Nonnull Packet<?> packet) {}
                };
                player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
                player.moveTo(station.getX() + 0.5, station.getY(), station.getZ() - 0.5);
                ResourceLocation id = Objects.requireNonNull(ResourceLocation.tryParse(ColonyCamera.ID + ":" + facility.id));
                Recipe<?> recipe = level.getRecipeManager().byKey(id).orElseThrow();
                helper.assertTrue(!player.isCreative() && !player.getAbilities().instabuild && !player.getRecipeBook().contains(recipe),
                        "Facility started in creative or was already learned: " + id);
                CraftingMenu menu = new CraftingMenu(1, Objects.requireNonNull(player.getInventory()), Objects.requireNonNull(ContainerLevelAccess.create(level, station)));
                for (int slot = 0; slot < 9; slot++) menu.getSlot(slot + 1).set(new ItemStack(Objects.requireNonNull(facility.grid[slot])));
                helper.assertTrue(menu.getSlot(0).getItem().isEmpty(), "Limited crafting accepted an unknown recipe: " + id);
                var advancement = level.getServer().getAdvancements().getAdvancement(Objects.requireNonNull(ResourceLocation.tryParse(ColonyCamera.ID + ":recipes/misc/" + facility.id)));
                helper.assertTrue(advancement != null, "Recipe unlock advancement was not loaded: " + id);
                ItemStack acquired = new ItemStack(Objects.requireNonNull(facility.unlock)); player.getInventory().setItem(0, acquired);
                CriteriaTriggers.INVENTORY_CHANGED.trigger(player, Objects.requireNonNull(player.getInventory()), acquired);
                helper.assertTrue(player.getRecipeBook().contains(recipe), "Obtaining the ingredient did not unlock recipe: " + id);
                menu.slotsChanged(Objects.requireNonNull(menu.getSlot(1).container));
                ItemStack result = menu.getSlot(0).getItem();
                helper.assertTrue(menu.stillValid(player) && result.is(Objects.requireNonNull(facility.output)) && result.getCount() == facility.count,
                        "Survival crafting table produced the wrong facility: " + id);
                ItemStack taken = menu.getSlot(0).remove(facility.count); menu.getSlot(0).onTake(player, Objects.requireNonNull(taken));
                helper.assertTrue(taken.is(Objects.requireNonNull(facility.output)) && taken.getCount() == facility.count, "Wrong taken output: " + id);
                for (int slot = 1; slot <= 9; slot++) helper.assertTrue(menu.getSlot(slot).getItem().isEmpty(),
                        "Taking a facility did not consume its exact ingredients: " + id);
                helper.assertTrue(menu.getSlot(0).getItem().isEmpty(), "Facility output remained after inputs were consumed: " + id);
            }
        } finally {
            limited.set(previous, level.getServer());
        }
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void colonyCatalogIncludesAllFacilitiesWithoutResearchCycle(GameTestHelper helper) {
        Scene scene = scene(helper); var catalog = CraftingService.catalog(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.player.getUUID()));
        helper.assertTrue(catalog.size() == 22 && TechProgress.get(Objects.requireNonNull(helper.getLevel())).tier(Objects.requireNonNull(scene.player.getUUID())) == 0,
                "Facility and tool catalog has the wrong size or requires prior research");
        for (Facility facility : facilities()) {
            String id = ColonyCamera.ID + ":" + facility.id; int found = 0;
            for (var value : catalog) {
                var entry = value.getAsJsonObject(); if (!entry.get("id").getAsString().equals(id)) continue;
                found++; helper.assertTrue(entry.get("unlocked").getAsBoolean() && entry.get("requiredTier").getAsInt() == 0
                                && entry.get("count").getAsInt() == facility.count && CraftingService.allowed(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.player.getUUID()), id),
                        "Basic facility is locked behind its own workstation or technology: " + id);
            }
            helper.assertTrue(found == 1, "Facility missing or duplicated in colony UI catalog: " + id);
        }
        helper.assertTrue(!CraftingService.allowed(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.player.getUUID()), "minecraft:iron_pickaxe"),
                "Adding facilities bypassed existing tool technology"); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 850)
    public static void workerProducesEveryFacilityUsingExactRealStock(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, ColonyContent.COLONY_WORKBENCH.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 3, ColonyContent.WAREHOUSE.get());
        List<Facility> recipes = facilities(); int[] next = {0}; ColonyJob[] jobs = {null}; WorkTask[] tasks = {null};
        helper.onEachTick(() -> {
            if (next[0] == recipes.size()) return;
            Facility facility = recipes.get(next[0]);
            if (tasks[0] == null) {
                for (int slot = 0; slot < box.inventory.getSlots(); slot++) box.inventory.setStackInSlot(slot, ItemStack.EMPTY);
                Map<Item, Integer> ingredients = new LinkedHashMap<>();
                for (Item item : facility.grid) if (item != Items.AIR) ingredients.merge(item, 1,
                        (left, right) -> Objects.requireNonNull(left) + Objects.requireNonNull(right));
                int slot = 0; for (var ingredient : ingredients.entrySet()) {
                    box.inventory.setStackInSlot(slot++, new ItemStack(Objects.requireNonNull(ingredient.getKey()), ingredient.getValue()));
                }
                CompoundTag before = box.inventory.save();
                jobs[0] = job(helper, scene, ColonyJob.Kind.CRAFT, ColonyCamera.ID + ":" + facility.id);
                tasks[0] = Objects.requireNonNull(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(jobs[0]), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()));
                helper.assertTrue(before.equals(box.inventory.save()), "Facility assignment consumed real stock early");
                BlockPos stand = tasks[0].stand; scene.worker.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            }
            CraftingService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(jobs[0]), Objects.requireNonNull(tasks[0]), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime());
            if (jobs[0].finished()) {
                int produced = 0;
                for (int slot = 0; slot < box.inventory.getSlots(); slot++) {
                    ItemStack stack = box.inventory.getStackInSlot(slot); if (stack.isEmpty()) continue;
                    helper.assertTrue(stack.is(Objects.requireNonNull(facility.output)), "Facility production left ingredients or wrong outputs: " + facility.id);
                    produced += stack.getCount();
                }
                helper.assertTrue(produced == facility.count, "Facility production lost or duplicated output: " + facility.id);
                scene.ledger.release(scene.worker.getUUID()); tasks[0] = null; jobs[0] = null; next[0]++;
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(next[0] == recipes.size(), "Worker did not complete all nine facility recipes"));
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void fullWarehouseRejectsWholeCableBatchWithoutCost(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, ColonyContent.COLONY_WORKBENCH.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 3, ColonyContent.WAREHOUSE.get());
        for (int slot = 0; slot < box.inventory.getSlots(); slot++) box.inventory.setStackInSlot(slot, new ItemStack(Objects.requireNonNull(Items.DIRT), 64));
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COPPER_INGOT), 64)); box.inventory.setStackInSlot(1, new ItemStack(Objects.requireNonNull(Items.REDSTONE), 64));
        box.inventory.setStackInSlot(2, new ItemStack(Objects.requireNonNull(ColonyContent.CABLE.get().asItem()), 63));
        CompoundTag before = box.inventory.save(); ColonyJob cable = job(helper, scene, ColonyJob.Kind.CRAFT, ColonyCamera.ID + ":network_cable");
        helper.assertTrue(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(cable), Objects.requireNonNull(scene.ledger), 0) == null && cable.reason.equals("full")
                        && before.equals(box.inventory.save()) && scene.ledger.claim(scene.worker.getUUID()) == null && cable.done == 0,
                "Partial space consumed inputs, produced fewer than eight cables, or kept a material claim"); helper.succeed();
    }

    private static List<Facility> facilities() {
        Item iron = Items.IRON_INGOT, redstone = Items.REDSTONE, table = Items.CRAFTING_TABLE, air = Items.AIR;
        Item oak = Items.OAK_PLANKS, birch = Items.BIRCH_PLANKS, crimson = Items.CRIMSON_PLANKS;
        return List.of(
                new Facility("printing_core", ColonyContent.PRINTING_CORE.get().asItem(), 1, table,
                        new Item[]{iron, Items.GLASS, iron, redstone, table, redstone, iron, iron, iron}),
                new Facility("warehouse", ColonyContent.WAREHOUSE.get().asItem(), 1, Items.CHEST,
                        new Item[]{iron, iron, iron, iron, Items.CHEST, iron, iron, iron, iron}),
                new Facility("colony_workbench", ColonyContent.COLONY_WORKBENCH.get().asItem(), 1, table,
                        new Item[]{iron, iron, iron, air, table, air, oak, birch, crimson}),
                new Facility("research_desk", ColonyContent.RESEARCH_DESK.get().asItem(), 1, table,
                        new Item[]{air, Items.BOOK, air, iron, table, iron, oak, birch, crimson}),
                new Facility("capacity_upgrade", ColonyContent.CAPACITY_UPGRADE.get(), 1, iron,
                        new Item[]{iron, iron, iron, iron, Items.PAPER, iron, iron, iron, iron}),
                new Facility("stack_upgrade", ColonyContent.STACK_UPGRADE.get(), 1, redstone,
                        new Item[]{air, Items.EMERALD, air, redstone, Items.PAPER, redstone, air, Items.EMERALD, air}),
                new Facility("network_controller", ColonyContent.CONTROLLER.get().asItem(), 1, redstone,
                        new Item[]{iron, redstone, iron, redstone, Items.QUARTZ, redstone, iron, redstone, iron}),
                new Facility("network_port", ColonyContent.PORT.get().asItem(), 1, redstone,
                        new Item[]{iron, iron, iron, redstone, Items.HOPPER, redstone, iron, iron, iron}),
                new Facility("network_cable", ColonyContent.CABLE.get().asItem(), 8, Items.COPPER_INGOT,
                        new Item[]{Items.COPPER_INGOT, redstone, Items.COPPER_INGOT, air, air, air, air, air, air}));
    }

    @GameTest(template = "construction", timeoutTicks = 350)
    public static void workerWalksToWorkbenchAndCraftsNativePickaxe(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, ColonyContent.COLONY_WORKBENCH.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 3, ColonyContent.WAREHOUSE.get());
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS), 3)); box.inventory.setStackInSlot(1, new ItemStack(Objects.requireNonNull(Items.STICK), 2));
        ColonyJob job = job(helper, scene, ColonyJob.Kind.CRAFT, "minecraft:wooden_pickaxe");
        WorkTask task = Objects.requireNonNull(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()));
        helper.assertTrue(box.inventory.getStackInSlot(0).getCount() == 3 && job.done == 0, "Assignment copied or consumed materials");
        helper.onEachTick(() -> {
            if (!job.finished()) CraftingService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), job, task, Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime());
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Worker did not finish native crafting: " + job.reason);
            var access = StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(box.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()));
            helper.assertTrue(access.count(new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE))) == 1 && access.count(new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS))) == 0 && access.count(new ItemStack(Objects.requireNonNull(Items.STICK))) == 0,
                    "Native recipe output or material conservation failed");
            helper.assertTrue(scene.worker.distanceToSqr(Objects.requireNonNull(net.minecraft.world.phys.Vec3.atBottomCenterOf(task.stand))) <= 1.1, "Crafting occurred away from station");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void fullWarehouseAndLockedTechnologyRejectWithoutCost(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, ColonyContent.COLONY_WORKBENCH.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 3, ColonyContent.WAREHOUSE.get());
        for (int slot = 0; slot < 27; slot++) box.inventory.setStackInSlot(slot, new ItemStack(Objects.requireNonNull(Items.DIRT), 64));
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS), 64)); box.inventory.setStackInSlot(1, new ItemStack(Objects.requireNonNull(Items.STICK), 64));
        CompoundTag before = box.inventory.save(); ColonyJob craft = job(helper, scene, ColonyJob.Kind.CRAFT, "minecraft:wooden_pickaxe");
        helper.assertTrue(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(craft), Objects.requireNonNull(scene.ledger), 0) == null && craft.reason.equals("full"), "Full warehouse accepted output");
        helper.assertTrue(before.equals(box.inventory.save()) && scene.ledger.claim(scene.worker.getUUID()) == null, "Rejected craft altered real inventory or left claims");
        ColonyJob locked = job(helper, scene, ColonyJob.Kind.CRAFT, "minecraft:iron_pickaxe");
        helper.assertTrue(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(locked), Objects.requireNonNull(scene.ledger), 0) == null && locked.reason.equals("technology"), "Locked recipe was usable");
        helper.assertTrue(before.equals(box.inventory.save()), "Locked craft consumed materials"); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void sealedWarehouseCannotSupplyCraftingAndBlockingRollsBack(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, ColonyContent.COLONY_WORKBENCH.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 3, ColonyContent.WAREHOUSE.get());
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS), 3)); box.inventory.setStackInSlot(1, new ItemStack(Objects.requireNonNull(Items.STICK), 2));
        ColonyJob craft = job(helper, scene, ColonyJob.Kind.CRAFT, "minecraft:wooden_pickaxe");
        CompoundTag before = box.inventory.save();
        sealWarehouse(helper, true);
        helper.assertTrue(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(craft), Objects.requireNonNull(scene.ledger), 0) == null
                && craft.reason.equals("storage"), "Sealed local warehouse supplied crafting through a wall");
        helper.assertTrue(before.equals(box.inventory.save()) && scene.ledger.claim(scene.worker.getUUID()) == null,
                "Blocked assignment changed inventory or left a reservation");
        sealWarehouse(helper, false);
        WorkTask task = Objects.requireNonNull(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(craft), Objects.requireNonNull(scene.ledger), 0));
        scene.worker.moveTo(task.stand.getX() + 0.5, task.stand.getY(), task.stand.getZ() + 0.5);
        sealWarehouse(helper, true);
        helper.assertTrue(CraftingService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(craft), task, Objects.requireNonNull(scene.ledger), 1)
                && task.progress == 0 && craft.done == 0 && before.equals(box.inventory.save()),
                "Storage blocked after assignment consumed materials or produced an item");
        scene.ledger.release(scene.worker.getUUID()); helper.succeed();
    }

    private static void sealWarehouse(GameTestHelper helper, boolean sealed) {
        Block block = sealed ? Blocks.STONE : Blocks.AIR;
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(4, y, 3), Objects.requireNonNull(block)); helper.setBlock(new BlockPos(6, y, 3), Objects.requireNonNull(block));
            helper.setBlock(new BlockPos(5, y, 2), Objects.requireNonNull(block)); helper.setBlock(new BlockPos(5, y, 4), Objects.requireNonNull(block));
        }
        helper.setBlock(new BlockPos(5, 2, 3), Objects.requireNonNull(block)); helper.setBlock(new BlockPos(5, 3, 3), Objects.requireNonNull(block));
    }

    @GameTest(template = "construction", timeoutTicks = 480)
    public static void workerResearchConsumesStockOnceAndUnlockSurvivesSave(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, ColonyContent.RESEARCH_DESK.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 3, ColonyContent.WAREHOUSE.get()); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 16));
        ColonyJob job = job(helper, scene, ColonyJob.Kind.RESEARCH, "1");
        WorkTask task = Objects.requireNonNull(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()));
        helper.onEachTick(() -> { if (!job.finished()) CraftingService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), job, task, Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()); });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Research failed: " + job.reason);
            TechProgress technology = TechProgress.get(Objects.requireNonNull(helper.getLevel()));
            helper.assertTrue(technology.tier(Objects.requireNonNull(scene.player.getUUID())) == 1 && box.inventory.getStackInSlot(0).isEmpty(), "Research did not consume exact cost");
            helper.assertTrue(TechProgress.load(technology.save(new CompoundTag())).tier(Objects.requireNonNull(scene.player.getUUID())) == 1, "Unlock did not survive persistence");
            ColonyJob duplicate = job(helper, scene, ColonyJob.Kind.RESEARCH, "1");
            scene.ledger.release(scene.worker.getUUID());
            helper.assertTrue(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(duplicate), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()) == null && duplicate.finished(), "Completed target researched again");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void researchHonorsExistingMaterialReservationAndShrinkingStock(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, ColonyContent.RESEARCH_DESK.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 3, ColonyContent.WAREHOUSE.get()); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 20));
        var source = Objects.requireNonNull(StorageResources.resolve(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(box.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()), Objects.requireNonNull(Items.COBBLESTONE)));
        UUID builder = UUID.randomUUID(); scene.ledger.reserve(builder, UUID.randomUUID(), source.identity(), 20, 8);
        ColonyJob research = job(helper, scene, ColonyJob.Kind.RESEARCH, "1");
        helper.assertTrue(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(research), Objects.requireNonNull(scene.ledger), 0) == null, "Research stole high-priority construction material");
        scene.ledger.release(builder); WorkTask task = Objects.requireNonNull(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(research), Objects.requireNonNull(scene.ledger), 0));
        scene.ledger.reserve(builder, UUID.randomUUID(), source.identity(), 20, 4);
        // A player removes material while both jobs retain their reservations.
        box.inventory.extractItem(0, 1, false); scene.worker.moveTo(task.stand.getX() + 0.5, task.stand.getY(), task.stand.getZ() + 0.5);
        helper.assertTrue(CraftingService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(research), task, Objects.requireNonNull(scene.ledger), 1) && task.progress == 0,
                "Research progressed while collective claims exceeded real stock");
        helper.assertTrue(box.inventory.getStackInSlot(0).getCount() == 19 && TechProgress.get(Objects.requireNonNull(helper.getLevel())).tier(Objects.requireNonNull(scene.player.getUUID())) == 0,
                "Shrinking stock was consumed or fabricated"); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 250)
    public static void twoNetworkPortsProduceOneNativeStickBatch(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, ColonyContent.COLONY_WORKBENCH.get());
        machine(helper, scene, 6, 1, 3, ColonyContent.CONTROLLER.get()); machine(helper, scene, 5, 1, 3, ColonyContent.PORT.get());
        machine(helper, scene, 6, 1, 4, ColonyContent.PORT.get()); MachineBlockEntity box = machine(helper, scene, 7, 1, 3, ColonyContent.WAREHOUSE.get());
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.BIRCH_PLANKS), 2)); ColonyJob job = job(helper, scene, ColonyJob.Kind.CRAFT, "minecraft:stick");
        WorkTask task = Objects.requireNonNull(CraftingService.assign(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), Objects.requireNonNull(job), Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()));
        helper.assertTrue(task.materialClaims.size() == 1 && task.materialClaims.values().iterator().next() == 2, "Ports duplicated shared stock or reservation");
        helper.onEachTick(() -> { if (!job.finished()) CraftingService.execute(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.worker), job, task, Objects.requireNonNull(scene.ledger), helper.getLevel().getGameTime()); });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Network crafting stalled: " + job.reason);
            var access = StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(helper.absolutePos(new BlockPos(5, 1, 3))), Objects.requireNonNull(scene.player.getUUID()));
            helper.assertTrue(access.count(new ItemStack(Objects.requireNonNull(Items.STICK))) == 4 && access.count(new ItemStack(Objects.requireNonNull(Items.BIRCH_PLANKS))) == 0, "Network crafting multiplied output or lost conservation");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20)
    public static void technologyRejectsAndPreservesUnsupportedOrDuplicateData(GameTestHelper helper) {
        CompoundTag unsupported = new CompoundTag(); unsupported.putInt("schemaVersion", 99); unsupported.putString("future", "keep");
        TechProgress rejected = TechProgress.load(unsupported);
        helper.assertTrue(rejected.readOnly() && rejected.save(new CompoundTag()).equals(unsupported)
                && !rejected.advance(Objects.requireNonNull(UUID.randomUUID()), 0, () -> helper.fail("Rejected technology executed material transaction")), "Unknown schema was overwritten");
        CompoundTag root = new CompoundTag(); root.putInt("schemaVersion", 1); ListTag entries = new ListTag(); UUID owner = UUID.randomUUID();
        for (int tier : new int[]{1, 2}) { CompoundTag entry = new CompoundTag(); entry.putUUID("owner", Objects.requireNonNull(owner)); entry.putInt("tier", tier); entries.add(entry); }
        root.put("colonies", entries); TechProgress duplicate = TechProgress.load(root);
        helper.assertTrue(duplicate.readOnly() && duplicate.save(new CompoundTag()).equals(root), "Duplicate owner entries were silently rewritten");
        helper.assertTrue(root.getList("colonies", Tag.TAG_COMPOUND).size() == 2, "Source data changed");
        TechProgress transaction = new TechProgress();
        try {
            transaction.advance(Objects.requireNonNull(owner), 0, () -> { throw new IllegalStateException("Synthetic failed material transaction"); });
            helper.fail("Failed material transaction published technology");
        } catch (IllegalStateException expected) {
            helper.assertTrue(transaction.tier(Objects.requireNonNull(owner)) == 0, "Technology changed before material commit");
        }
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 600)
    public static void unifiedSchedulerWaitsForRealWorkerThenResearches(GameTestHelper helper) {
        Scene scene = scene(helper); scene.worker.discard(); machine(helper, scene, 5, 1, 5, ColonyContent.RESEARCH_DESK.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 3, ColonyContent.WAREHOUSE.get()); box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 20));
        ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel())); manager.research(Objects.requireNonNull(scene.player), Objects.requireNonNull(helper.absolutePos(new BlockPos(5, 1, 5))));
        ColonyJob job = manager.jobs(Objects.requireNonNull(scene.player.getUUID())).get(0);
        helper.runAtTickTime(50, () -> {
            helper.assertTrue(job.done == 0 && TechProgress.get(Objects.requireNonNull(helper.getLevel())).tier(Objects.requireNonNull(scene.player.getUUID())) == 0 && box.inventory.getStackInSlot(0).getCount() == 20,
                    "No-worker research fabricated progress or consumed stock");
            WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); worker.owner(Objects.requireNonNull(scene.player.getUUID()));
            BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2)); worker.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5); helper.getLevel().addFreshEntity(worker);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Unified scheduler did not resume research after worker arrived: " + job.reason);
            helper.assertTrue(TechProgress.get(Objects.requireNonNull(helper.getLevel())).tier(Objects.requireNonNull(scene.player.getUUID())) == 1 && box.inventory.getStackInSlot(0).getCount() == 4,
                    "Unified scheduler research cost or unlock incorrect");
        });
    }

    @GameTest(template = "construction", timeoutTicks = 500)
    public static void constructionAndCraftingSharePriorityAndWaitForSupply(GameTestHelper helper) {
        Scene scene = scene(helper); machine(helper, scene, 5, 1, 5, ColonyContent.COLONY_WORKBENCH.get());
        MachineBlockEntity box = machine(helper, scene, 5, 1, 3, ColonyContent.WAREHOUSE.get());
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS), 3)); box.inventory.setStackInSlot(1, new ItemStack(Objects.requireNonNull(Items.STICK), 2));
        ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel())); manager.craft(Objects.requireNonNull(scene.player), Objects.requireNonNull(helper.absolutePos(new BlockPos(5, 1, 5))), "minecraft:wooden_pickaxe", 1);
        ColonyJob craft = manager.jobs(Objects.requireNonNull(scene.player.getUUID())).get(0); manager.update(Objects.requireNonNull(scene.player.getUUID()), craft.id, "priority", 1);
        BlockPos target = helper.absolutePos(new BlockPos(8, 1, 7));
        var state = new BlockPreset.State("minecraft:oak_planks", Objects.requireNonNull(Map.of()));
        var cells = Objects.requireNonNull(List.of(new BlockPreset.Cell(0, 0, 0, state)));
        BlockPreset preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "Shared material priority", 1, 1, 1, cells);
        ProjectionPlacement placement = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, target.getX(), target.getY(), target.getZ(), 0, false, true);
        ConstructionManager construction = ConstructionManager.get(Objects.requireNonNull(helper.getLevel())); construction.create(Objects.requireNonNull(scene.player), placement, 9);
        var build = construction.projects(Objects.requireNonNull(scene.player.getUUID())).get(0);
        helper.runAtTickTime(180, () -> {
            helper.assertTrue(build.finished() && !craft.finished(), "Low-priority craft stole construction material");
            helper.assertTrue(box.inventory.getStackInSlot(0).getCount() == 2, "Shared stock was not decremented exactly once");
            box.inventory.insertItem(0, new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS)), false);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(build.finished() && craft.finished(), "Supply did not resume queued crafting: " + craft.reason);
            var access = StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(box.getBlockPos()), Objects.requireNonNull(scene.player.getUUID()));
            helper.assertTrue(access.count(new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE))) == 1 && access.count(new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS))) == 0, "Final shared-stock conservation failed");
        });
    }

    private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE)); for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "CraftingTest"));
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2)); player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); worker.owner(Objects.requireNonNull(player.getUUID()));
        worker.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5); helper.getLevel().addFreshEntity(worker);
        return new Scene(player, worker, new ReservationLedger());
    }
    private static MachineBlockEntity machine(GameTestHelper helper, Scene scene, int x, int y, int z, Block block) {
        BlockPos pos = new BlockPos(x, y, z); helper.setBlock(pos, Objects.requireNonNull(block)); MachineBlockEntity machine = (MachineBlockEntity) helper.getBlockEntity(pos);
        Objects.requireNonNull(machine).owner(scene.player.getUUID()); return machine;
    }
    private static ColonyJob job(GameTestHelper helper, Scene scene, ColonyJob.Kind kind, String recipe) {
        ColonyJob job = new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(scene.player.getUUID()), "CraftingTest", Objects.requireNonNull(kind), 5, 0); job.recipe = Objects.requireNonNull(recipe);
        job.station = helper.absolutePos(new BlockPos(5, 1, 5)); return job;
    }
}
