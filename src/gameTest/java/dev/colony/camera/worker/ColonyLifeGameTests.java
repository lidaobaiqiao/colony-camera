package dev.colony.camera.worker;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.crafting.CraftingService;
import dev.colony.camera.tech.TechProgress;
import dev.colony.camera.work.ColonyWorkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class ColonyLifeGameTests {
    private ColonyLifeGameTests() {}
    @GameTest(template = "construction", timeoutTicks = 300)
    public static void workerWalksToARealSourceAndKeepsItsCargo(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var worker = scene.worker();
        thirsty(worker); worker.carry(new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 3));
        var cargo = Objects.requireNonNull(UUID.randomUUID()); worker.cargoTask(cargo);
        helper.setBlock(new BlockPos(4, 0, 6), Objects.requireNonNull(Blocks.WATER));
        helper.succeedWhen(() -> {
            helper.assertTrue(worker.needs().drinks() == 1 && worker.needs().hydration() > 90, "Worker did not reach and drink from water");
            helper.assertTrue(worker.getX() < helper.absolutePos(new BlockPos(7, 1, 6)).getX(), "Drinking happened remotely");
            helper.assertTrue(WorkerSurvival.source(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 0, 6)))), "Renewable water source was removed");
            helper.assertTrue(worker.cargoCount() == 3 && cargo.equals(worker.cargoTask()), "Life interruption lost cargo or its ownership");
        });
    }
    @GameTest(template = "construction", timeoutTicks = 160)
    public static void absentOrSealedWaterDoesNotHoldTheWorker(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var worker = scene.worker(); thirsty(worker);
        helper.setBlock(new BlockPos(5, 1, 6), Objects.requireNonNull(Blocks.WATER));
        for (int x = 4; x <= 6; x++) for (int z = 5; z <= 7; z++) for (int y = 1; y <= 3; y++)
            if (x != 5 || z != 6 || y != 1) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.STONE));
        helper.runAtTickTime(120, () -> {
            helper.assertTrue(worker.needs().drinks() == 0 && !worker.lifeBusy() && worker.available(), "Blocked water locked the worker or was drunk through walls"); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30)
    public static void pumpRequiresResearchAndAnAdjacentSource(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        var pump = machine(helper, scene, new BlockPos(8, 1, 5), Objects.requireNonNull(ColonyContent.WATER_PUMP.get()));
        helper.assertTrue(WorkerSurvival.facilityStatus(level, pump).equals("technology"), "Unresearched pump was active");
        waterResearch(helper, scene);
        helper.assertTrue(WorkerSurvival.facilityStatus(level, pump).equals("no_source"), "Pump created water without a source");
        helper.setBlock(new BlockPos(8, 0, 5), Objects.requireNonNull(Blocks.WATER));
        helper.assertTrue(WorkerSurvival.facilityStatus(level, pump).equals("ready"), "Real adjacent water did not activate pump");
        thirsty(scene.worker()); helper.runAtTickTime(20, () -> {
            helper.assertTrue(scene.worker().needs().waterQuality() == 2 && scene.worker().needs().hydration() > 90, "Worker did not drink at pump"); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 1000)
    public static void branchResearchUsesRealMaterialsWithoutAdvancingTools(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var owner = Objects.requireNonNull(scene.player().getUUID());
        scene.worker().needs().drink(1);
        var desk = machine(helper, scene, new BlockPos(5, 1, 5), Objects.requireNonNull(ColonyContent.RESEARCH_DESK.get()));
        var warehouse = machine(helper, scene, new BlockPos(5, 1, 3), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        warehouse.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COPPER_INGOT), 4));
        warehouse.inventory.setStackInSlot(1, new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS), 8));
        var manager = ColonyWorkManager.get(level); manager.research(scene.player(), Objects.requireNonNull(desk.getBlockPos()), TechProgress.WATERWORKS);
        helper.succeedWhen(() -> {
            var technology = TechProgress.get(level);
            helper.assertTrue(manager.jobs(owner).stream().anyMatch(job -> job.finished()), "Worker did not finish branch research");
            helper.assertTrue(technology.unlocked(owner, TechProgress.WATERWORKS) && technology.tier(owner) == 0, "Branch advanced or required a tool tier");
            helper.assertTrue(warehouse.inventory.getStackInSlot(0).isEmpty() && warehouse.inventory.getStackInSlot(1).isEmpty(), "Research did not consume exactly its materials");
            var restored = TechProgress.load(technology.save(new CompoundTag()));
            helper.assertTrue(!restored.readOnly() && restored.unlocked(owner, 4) && restored.tier(owner) == 0, "Branch unlock did not survive save/load");
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30)
    public static void milestonesAndOwnersCannotBeBypassed(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var owner = Objects.requireNonNull(scene.player().getUUID());
        var technology = TechProgress.get(level); int[] charged = {0};
        helper.assertTrue(!technology.complete(level, owner, 4, () -> charged[0]++), "Water practice was skipped");
        helper.assertTrue(!technology.complete(level, owner, 7, () -> charged[0]++), "Prerequisites were skipped");
        waterResearch(helper, scene);
        helper.assertTrue(technology.researchReason(level, owner, 5).equals("housing") && charged[0] == 0, "Housing prerequisite or atomic rejection failed");
        WorkerStateGameTests.room(helper, scene.player()); scene.worker().needs().meal();
        helper.assertTrue(technology.complete(level, owner, 6, () -> {}), "Community prerequisites did not reflect real colony state");
        helper.assertTrue(technology.researchReason(level, owner, 7).equals("social_practice"), "Education ignored actual social life");
        helper.assertTrue(!technology.unlocked(Objects.requireNonNull(UUID.randomUUID()), 4), "Another owner's colony gained research");
        var nodes = TechProgress.describe(level, owner).getAsJsonArray("nodes");
        helper.assertTrue(nodes.size() == 8 && nodes.get(4).getAsJsonObject().get("done").getAsBoolean() && !nodes.get(1).getAsJsonObject().get("done").getAsBoolean(), "Client tree confused branches and tool levels"); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30)
    public static void legacyTechnologyMigratesAndUnknownBranchesStayRaw(GameTestHelper helper) {
        CompoundTag root = new CompoundTag(); root.putInt("schemaVersion", 1); ListTag entries = new ListTag();
        CompoundTag entry = new CompoundTag(); UUID owner = Objects.requireNonNull(UUID.randomUUID()); entry.putUUID("owner", owner); entry.putInt("tier", 2); entries.add(entry); root.put("colonies", entries);
        var legacy = TechProgress.load(root); helper.assertTrue(!legacy.readOnly() && legacy.tier(owner) == 2 && !legacy.unlocked(owner, 4), "Legacy progress was lost or granted unearned branches");
        CompoundTag next = legacy.save(new CompoundTag()); next.getList("colonies", 10).getCompound(0).putIntArray("unlocks", new int[]{1, 2, 99});
        var bad = TechProgress.load(next); helper.assertTrue(bad.readOnly() && next.equals(bad.save(new CompoundTag())), "Unknown research was overwritten");
        root.putInt("schemaVersion", 99); helper.assertTrue(root.equals(TechProgress.load(root).save(new CompoundTag())), "Future technology schema was overwritten"); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 300)
    public static void workersActuallyConverseAndForeignWorkersAreExcluded(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var owner = Objects.requireNonNull(scene.player().getUUID());
        community(helper, scene); machine(helper, scene, new BlockPos(8, 1, 5), Objects.requireNonNull(ColonyContent.MEETING_POINT.get()));
        var friend = WorkerStateGameTests.worker(helper, owner, new BlockPos(9, 1, 6));
        var foreign = WorkerStateGameTests.worker(helper, Objects.requireNonNull(UUID.randomUUID()), new BlockPos(9, 1, 7)); lonely(scene.worker()); lonely(friend); lonely(foreign);
        helper.succeedWhen(() -> {
            var first = scene.worker().needs().relations().get(friend.getUUID()); var second = friend.needs().relations().get(scene.worker().getUUID());
            helper.assertTrue(first != null && second != null && first.togetherTicks() >= 200 && first.togetherTicks() == second.togetherTicks(), "Actual social time did not accrue equally");
            helper.assertTrue(!scene.worker().needs().relations().containsKey(foreign.getUUID()) && foreign.needs().relations().isEmpty(), "Colony membership was ignored");
            helper.assertTrue(scene.worker().needs().social() > 20 && friend.needs().social() > 20, "Conversation did not satisfy social needs");
            machine(helper, scene, new BlockPos(10, 1, 4), Objects.requireNonNull(ColonyContent.ACADEMY.get()));
            helper.assertTrue(TechProgress.get(level).researchReason(level, owner, 7).equals("ready"), "Actual social progress did not unlock education research");
        });
    }
    @GameTest(template = "construction", timeoutTicks = 140)
    public static void fullRelationshipsCannotCreateOneSidedConversationOrStrandWorkers(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var worker = scene.worker(); community(helper, scene);
        machine(helper, scene, new BlockPos(8, 1, 5), Objects.requireNonNull(ColonyContent.MEETING_POINT.get()));
        var friend = WorkerStateGameTests.worker(helper, Objects.requireNonNull(scene.player().getUUID()), new BlockPos(9, 1, 6)); lonely(friend);
        var full = new java.util.LinkedHashMap<UUID, WorkerNeeds.Relation>();
        for (int i = 0; i < WorkerNeeds.MAX_RELATIONS; i++) full.put(Objects.requireNonNull(UUID.randomUUID()), new WorkerNeeds.Relation(20));
        worker.needs().restore(100, 0, 0, 20, 1, 1, -1, full);
        helper.runAtTickTime(100, () -> {
            helper.assertTrue(worker.needs().relations().equals(full) && friend.needs().relations().isEmpty(), "A full relationship list produced one-sided shared time");
            helper.assertTrue(!worker.lifeBusy() && !friend.lifeBusy() && worker.available() && friend.available(), "Unavailable conversation partners stranded workers"); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 300)
    public static void solitaryWorkerDoesNotSpendForeverAtAGatheringPoint(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); community(helper, scene); lonely(scene.worker());
        machine(helper, scene, new BlockPos(8, 1, 5), Objects.requireNonNull(ColonyContent.MEETING_POINT.get()));
        helper.runAtTickTime(260, () -> {
            helper.assertTrue(scene.worker().needs().relations().isEmpty() && !scene.worker().lifeBusy() && scene.worker().available(), "Missing partners stranded the worker"); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30)
    public static void heatNeedsARealLitSourceAndWallsBlockIt(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        double baseline = LocalClimate.sample(level, scene.worker());
        helper.setBlock(new BlockPos(10, 1, 6), Objects.requireNonNull(Blocks.FURNACE.defaultBlockState().setValue(Objects.requireNonNull(BlockStateProperties.LIT), true)));
        helper.assertTrue(LocalClimate.sample(level, scene.worker()) > baseline + 2, "Lit furnace did not warm actual surroundings");
        for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(9, y, 6), Objects.requireNonNull(Blocks.STONE));
        helper.assertTrue(Math.abs(LocalClimate.sample(level, scene.worker()) - baseline) < 0.01, "Heat passed through a solid wall"); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30)
    public static void insulationOnlyModeratesTheActualHomeInterior(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var owner = Objects.requireNonNull(scene.player().getUUID());
        waterResearch(helper, scene); WorkerStateGameTests.room(helper, scene.player()); helper.assertTrue(Residences.assign(level, scene.worker()), "Fixture did not provide housing");
        BlockPos inside = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 1, 4))); scene.worker().moveTo(inside.getX() + .5, inside.getY(), inside.getZ() + .5, 0, 0);
        double before = LocalClimate.sample(level, scene.worker()); helper.assertTrue(TechProgress.get(level).complete(level, owner, 5, () -> {}), "Housing did not allow insulation research");
        helper.assertTrue(Math.abs(LocalClimate.sample(level, scene.worker()) - 20) <= Math.abs(before - 20) * .35 + .001, "Valid interior was not insulated");
        var outside = Objects.requireNonNull(helper.absolutePos(new BlockPos(8, 1, 6))); scene.worker().moveTo(outside.getX() + .5, outside.getY(), outside.getZ() + .5, 0, 0);
        scene.worker().home(null); double unhoused = LocalClimate.sample(level, scene.worker()); scene.worker().home(Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 2))));
        helper.assertTrue(LocalClimate.sample(level, scene.worker()) == unhoused, "A home gave remote insulation"); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30)
    public static void needsSaveExactlyAndFutureDataMakesTheWorkerReadOnly(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var worker = scene.worker(); var friend = Objects.requireNonNull(UUID.randomUUID());
        worker.needs().drink(2); worker.needs().meal(); worker.needs().converse(friend, 20); worker.needs().claimCare(4);
        CompoundTag encoded = WorkerNeedsStorage.save(worker.needs()); helper.assertTrue(encoded.equals(WorkerNeedsStorage.save(WorkerNeedsStorage.load(encoded))), "Life/relations/care checkpoint did not round-trip");
        CompoundTag future = Objects.requireNonNull(encoded.copy()); future.putInt("schemaVersion", 99); future.putString("future", "preserve");
        CompoundTag entity = new CompoundTag(); worker.addAdditionalSaveData(entity); entity.put("ColonyNeeds", future); worker.readAdditionalSaveData(entity);
        CompoundTag saved = new CompoundTag(); worker.addAdditionalSaveData(saved);
        helper.assertTrue(worker.stateReadOnly() && future.equals(saved.get("ColonyNeeds")), "Future life data was overwritten or allowed mutation");
        entity.remove("ColonyNeeds"); worker.readAdditionalSaveData(entity); helper.assertTrue(!worker.stateReadOnly() && worker.needs().hydration() == 100 && worker.needs().social() == 80, "Old workers did not receive safe migration defaults"); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30)
    public static void newFacilityCraftingUsesActualResearchRegardlessOfRecipeId(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var owner = Objects.requireNonNull(scene.player().getUUID());
        helper.assertTrue(!CraftingService.allowed(level, owner, "colonycamera:water_pump"), "Water station was auto-crafted before research");
        waterResearch(helper, scene); helper.assertTrue(CraftingService.allowed(level, owner, "colonycamera:water_pump"), "Researched station was not craftable");
        helper.assertTrue(!CraftingService.allowed(level, owner, "colonycamera:meeting_point"), "Community research was bypassed");
        helper.assertTrue(CraftingService.allowed(level, owner, "colonycamera:residence"), "Basic housing was blocked by new research"); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 150)
    public static void realMealsRewardCareOnceAndReloadPreservesTheLimit(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var worker = scene.worker(); var level = Objects.requireNonNull(helper.getLevel());
        WorkerStateGameTests.room(helper, scene.player()); helper.assertTrue(Residences.assign(level, worker), "Missing home");
        var pos = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 1, 4))); worker.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        var box = machine(helper, scene, new BlockPos(5, 1, 4), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        box.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.BREAD), 4)); worker.needs().drink(1);
        worker.state().restoreLife(4, 1, 1, 2, 0, false, 0, Objects.requireNonNull(BigInteger.ZERO), 0, false);
        double[] earned = {0};
        helper.runAtTickTime(60, () -> {
            helper.assertTrue(worker.needs().meals() == 2 && box.inventory.getStackInSlot(0).getCount() == 2 && worker.needs().lastCareDay() == level.getGameTime() / 24000,
                    "Meals=" + worker.needs().meals() + " stock=" + box.inventory.getStackInSlot(0).getCount() + " careDay=" + worker.needs().lastCareDay()
                    + " home=" + (Residences.home(level, worker) != null) + " hunger=" + worker.state().hunger() + " reason=" + worker.lifeReason());
            earned[0] = worker.state().loyalty(); helper.assertTrue(earned[0] > 3, "Care and living improvement produced no loyalty");
            CompoundTag saved = new CompoundTag(); worker.addAdditionalSaveData(saved); worker.readAdditionalSaveData(saved);
            worker.state().restoreLife(4, 1, worker.state().foodQuality(), worker.state().lastQuality(), earned[0], false, 0, Objects.requireNonNull(BigInteger.ZERO), 0, false);
        });
        helper.runAtTickTime(120, () -> {
            helper.assertTrue(worker.needs().meals() == 4 && box.inventory.getStackInSlot(0).isEmpty(), "Second real meal batch was not consumed");
            helper.assertTrue(Math.abs(worker.state().loyalty() - earned[0]) < .001, "Need refill or reload minted another care reward on the same day"); helper.succeed();
        });
    }
    private static void thirsty(WorkerEntity worker) { worker.needs().restore(30, 80, 0, 20, 0, 0, -1, Objects.requireNonNull(Map.of())); }
    @GameTest(template = "construction", timeoutTicks = 140)
    public static void publicEducationAcceleratesActualStudy(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var worker = scene.worker(); var level = Objects.requireNonNull(helper.getLevel()); var owner = Objects.requireNonNull(scene.player().getUUID());
        community(helper, scene); helper.assertTrue(Residences.assign(level, worker), "Missing home");
        var school = machine(helper, scene, new BlockPos(8, 1, 5), Objects.requireNonNull(ColonyContent.ACADEMY.get()));
        var request = new com.google.gson.JsonObject(); request.addProperty("id", worker.getUUID().toString()); request.addProperty("operation", "study");
        request.addProperty("station", school.getBlockPos().asLong()); request.addProperty("course", "MINING"); request.addProperty("tier", 1); WorkerOrders.request(scene.player(), request);
        double[] progress = new double[3];
        helper.runAtTickTime(20, () -> progress[0] = Objects.requireNonNull(worker.directive()).progress);
        helper.runAtTickTime(40, () -> {
            progress[1] = Objects.requireNonNull(worker.directive()).progress;
            var person = Objects.requireNonNull(UUID.randomUUID()); for (int i = 0; i < 10; i++) worker.needs().converse(person, 20);
            helper.assertTrue(TechProgress.get(level).complete(level, owner, 7, () -> {}), "Education milestone failed");
        });
        helper.runAtTickTime(60, () -> progress[2] = Objects.requireNonNull(worker.directive()).progress);
        helper.runAtTickTime(80, () -> {
            double before = progress[1] - progress[0], after = Objects.requireNonNull(worker.directive()).progress - progress[2];
            helper.assertTrue(before > 0 && Math.abs(after / before - 1.5) < .02, "Public education did not affect real study: " + before + " -> " + after); helper.succeed();
        });
    }
    private static void lonely(WorkerEntity worker) { worker.needs().restore(100, 0, 0, 20, 0, 0, -1, Objects.requireNonNull(Map.of())); }
    private static void waterResearch(GameTestHelper helper, WorkerStateGameTests.Scene scene) {
        scene.worker().needs().drink(1); var level = Objects.requireNonNull(helper.getLevel());
        helper.assertTrue(TechProgress.get(level).complete(level, Objects.requireNonNull(scene.player().getUUID()), 4, () -> {}), "Fixture water prerequisite failed");
    }
    private static void community(GameTestHelper helper, WorkerStateGameTests.Scene scene) {
        waterResearch(helper, scene); WorkerStateGameTests.room(helper, scene.player()); scene.worker().needs().meal();
        helper.assertTrue(TechProgress.get(Objects.requireNonNull(helper.getLevel())).complete(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.player().getUUID()), 6, () -> {}), "Fixture community prerequisite failed");
    }
    @Nonnull private static MachineBlockEntity machine(GameTestHelper helper, WorkerStateGameTests.Scene scene, BlockPos relative, Block block) {
        helper.setBlock(relative, block); var machine = Objects.requireNonNull((MachineBlockEntity) helper.getBlockEntity(relative));
        machine.owner(Objects.requireNonNull(scene.player().getUUID())); return machine;
    }
}
