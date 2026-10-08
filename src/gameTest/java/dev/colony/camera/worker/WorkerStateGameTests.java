package dev.colony.camera.worker;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.construction.ConstructionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class WorkerStateGameTests {
    public record Scene(@Nonnull FakePlayer player, @Nonnull WorkerEntity worker) {}
    @GameTest(template = "construction", timeoutTicks = 600, batch = "worker_sleep")
    public static void tiredWorkerOpensAnActualClosedBedroomDoorAndSleeps(GameTestHelper helper) {
        Scene scene = scene(helper); room(helper, scene.player()); closeBedroom(helper);
        helper.assertTrue(Residences.assign(Objects.requireNonNull(helper.getLevel()), scene.worker()), "Closed doorway invalidated a physical home");
        scene.worker().state().restoreLife(20, .1, 1, 2, 0, false, 0, Objects.requireNonNull(BigInteger.ZERO), 0, false);
        scene.worker().resting(true);
        helper.succeedWhen(() -> {
            helper.assertTrue(scene.worker().isSleeping() && scene.worker().state().rest() > .1, "Worker failed to traverse the doorway and rest: " + scene.worker().blockPosition());
            scene.worker().discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 260, batch = "worker_door_permission")
    public static void protectedBedroomDoorStaysClosedWithoutLosingItsResident(GameTestHelper helper) {
        Scene scene = scene(helper); room(helper, scene.player()); closeBedroom(helper);
        helper.assertTrue(Residences.assign(Objects.requireNonNull(helper.getLevel()), scene.worker()), "Home assignment failed");
        scene.worker().state().restoreLife(20, .1, 1, 2, 0, false, 0, Objects.requireNonNull(BigInteger.ZERO), 0, false); scene.worker().resting(true);
        BlockPos door = Objects.requireNonNull(helper.absolutePos(new BlockPos(3, 1, 5))); int[] attempts = {0};
        java.util.function.Consumer<net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock> reject = event -> {
            if (Objects.requireNonNull(event).getEntity().getUUID().equals(scene.player().getUUID()) && event.getPos().getX() == door.getX() && event.getPos().getZ() == door.getZ()) {
                attempts[0]++; event.setCanceled(true);
            }
        };
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGH, false,
                net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class, reject);
        helper.runAfterDelay(220, () -> {
            try {
                helper.assertTrue(attempts[0] > 0 && !helper.getLevel().getBlockState(door).getValue(Objects.requireNonNull(DoorBlock.OPEN))
                        && !scene.worker().isSleeping() && scene.worker().home() != null, "Worker bypassed the door interaction protection or discarded its home");
                helper.succeed();
            } finally { net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(reject); scene.worker().discard(); }
        });
    }
    private static void closeBedroom(GameTestHelper helper) {
        for (int y = 1; y <= 2; y++) {
            BlockPos pos = new BlockPos(3, y, 5);
            helper.setBlock(pos, Objects.requireNonNull(helper.getBlockState(pos).setValue(Objects.requireNonNull(DoorBlock.OPEN), false)));
        }
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "worker_attributes")
    public static void eighteenEffectsFeedPhysicalAttributesDamageAndBilateralSight(GameTestHelper helper) {
        Scene scene = scene(helper); WorkerEntity worker = scene.worker(); WorkerState state = worker.state();
        for (var entry : new WorkerAttribute[]{WorkerAttribute.PERCEPTION, WorkerAttribute.MOBILITY, WorkerAttribute.ENERGY,
                WorkerAttribute.LEARNING, WorkerAttribute.ENVIRONMENT_RESISTANCE, WorkerAttribute.PRECISION, WorkerAttribute.OPERATION})
            state.restoreAttribute(Objects.requireNonNull(entry), Objects.requireNonNull(BigInteger.valueOf(401)), Objects.requireNonNull(BigInteger.ZERO));
        worker.applyStateEffects();
        helper.assertTrue(worker.maxUpStep() > 0.6 && worker.searchRange() > 24 && worker.navigationRange() > 64, "Perception or mobility was cosmetic");
        helper.assertTrue(worker.getAttributeValue(Objects.requireNonNull(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE)) > 64
                && WorkerEffects.learning(state) > 1 && WorkerEffects.operation(state) > 1 && WorkerEffects.energy(state) > 1, "Effects did not reach entity or action consumers");
        WorkerEntity observer = worker(helper, Objects.requireNonNull(scene.player().getUUID()), new BlockPos(9, 1, 6));
        double conspicuous = WorkerEffects.witnessRange(observer.state(), worker);
        observer.state().restoreAttribute(WorkerAttribute.PERCEPTION, Objects.requireNonNull(BigInteger.valueOf(401)), Objects.requireNonNull(BigInteger.ZERO));
        helper.assertTrue(conspicuous > 32 && WorkerEffects.witnessRange(observer.state(), worker) > conspicuous, "Perception only affected one side of witnessing");
        helper.assertTrue(worker.hurt(Objects.requireNonNull(worker.damageSources().fall()), 4) && worker.getHealth() > 16 && worker.getHealth() < 20
                && state.credit(WorkerAttribute.ENVIRONMENT_RESISTANCE).signum() > 0, "Environmental resistance bypassed real damage or did not train");
        double before = WorkerEffects.buildTicks(new WorkerState(), helper.getLevel().getGameTime());
        WorkerState builder = new WorkerState(); builder.profession(WorkerProfile.Profession.BUILDER, false);
        helper.assertTrue(WorkerEffects.buildTicks(builder, helper.getLevel().getGameTime()) < before && builder.points(WorkerAttribute.BUILDING).equals(BigInteger.ONE), "Profession had no immediate bonus before practice");
        CompoundTag saved = WorkerStateStorage.save(state);
        helper.assertTrue(saved.getCompound("attributes").getAllKeys().size() == 18 && WorkerStateStorage.load(saved).points(WorkerAttribute.OPERATION).equals(BigInteger.valueOf(401)), "Expanded state failed persistence");
        worker.discard(); observer.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 1700, batch = "worker_capacity")
    public static void capacityDrivesOneRealEightyItemTripAndConservesEveryBlock(GameTestHelper helper) {
        Scene scene = scene(helper); WorkerEntity worker = scene.worker();
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 8))); worker.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5, 0, 0);
        worker.state().restoreAttribute(WorkerAttribute.CAPACITY, Objects.requireNonNull(BigInteger.valueOf(11)), Objects.requireNonNull(BigInteger.ZERO));
        MachineBlockEntity warehouse = machine(helper, scene.player(), new BlockPos(1, 1, 8), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        warehouse.inventory.upgrade(true, 1); warehouse.inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE), 128));
        var cells = new java.util.ArrayList<dev.colony.camera.preset.BlockPreset.Cell>();
        for (int x = 0; x < 8; x++) for (int z = 0; z < 10; z++) cells.add(new dev.colony.camera.preset.BlockPreset.Cell(x, 0, z,
                new dev.colony.camera.preset.BlockPreset.State("minecraft:cobblestone", Objects.requireNonNull(java.util.Map.of()))));
        var preset = new dev.colony.camera.preset.BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "Capacity", 8, 1, 10, cells);
        BlockPos origin = Objects.requireNonNull(helper.absolutePos(new BlockPos(3, 1, 2)));
        var placement = new dev.colony.camera.planning.ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, origin.getX(), origin.getY(), origin.getZ(), 0, false, true);
        ConstructionManager manager = ConstructionManager.get(Objects.requireNonNull(helper.getLevel())); manager.create(scene.player(), placement, 5);
        var project = Objects.requireNonNull(manager.project(placement.id())); int[] largestCargo = {0};
        helper.onEachTick(() -> largestCargo[0] = Math.max(largestCargo[0], worker.cargoCount()));
        helper.succeedWhen(() -> {
            var assignment = worker.assignment;
            helper.assertTrue(project.finished(), "Capacity construction stalled: " + project.reason + " done=" + project.completed.cardinality()
                    + " alive=" + worker.isAlive() + " cargo=" + worker.cargoCount() + " position=" + worker.blockPosition()
                    + " assignment=" + (assignment == null ? "none" : assignment.unit + "/" + assignment.stand + "/" + assignment.actionProgress));
            helper.assertTrue(largestCargo[0] == 80 && worker.cargoCount() == 0 && warehouse.inventory.getStackInSlot(0).getCount() == 48,
                    "Capacity was only cosmetic, caused extra trips, or lost stock: peak=" + largestCargo[0]);
            helper.assertTrue(worker.state().credit(WorkerAttribute.BUILDING).signum() > 0 && worker.state().credit(WorkerAttribute.CAPACITY).signum() > 0,
                    "Real construction/transport did not grow their matching attributes"); worker.discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 160, batch = "worker_teaching")
    public static void teachingHelpsOnlyActualColonyClassmatesAtTheSchool(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        room(helper, scene.player()); room(helper, scene.player(), 6);
        WorkerEntity teacher = worker(helper, Objects.requireNonNull(scene.player().getUUID()), new BlockPos(7, 1, 7));
        helper.assertTrue(Residences.assign(level, scene.worker()) && Residences.assign(level, teacher), "Two actual classmates failed to obtain separate homes");
        MachineBlockEntity school = machine(helper, scene.player(), new BlockPos(6, 1, 7), Objects.requireNonNull(ColonyContent.ACADEMY.get()));
        BlockPos near = Objects.requireNonNull(helper.absolutePos(new BlockPos(5, 1, 7))); scene.worker().moveTo(near.getX() + .5, near.getY(), near.getZ() + .5, 0, 0);
        teacher.state().profession(WorkerProfile.Profession.TEACHER, false);
        WorkerDirective lesson = new WorkerDirective(WorkerDirective.Kind.STUDY_ATTRIBUTE, Objects.requireNonNull(school.getBlockPos()), null, "MINING", false, 1);
        scene.worker().directive(lesson); teacher.directive(new WorkerDirective(WorkerDirective.Kind.STUDY_ATTRIBUTE, Objects.requireNonNull(school.getBlockPos()), null, "MINING", false, 1));
        double[] measured = {0, 0, 0};
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(teacher.lifeReason().equals("studying") && scene.worker().lifeReason().equals("studying"), "Classmates did not physically study at school");
            measured[0] = lesson.progress;
        });
        helper.runAfterDelay(100, () -> { measured[1] = (lesson.progress - measured[0]) / 20; teacher.directive(null); measured[2] = lesson.progress; });
        helper.runAfterDelay(122, () -> {
            double alone = (lesson.progress - measured[2]) / 22;
            helper.assertTrue(measured[1] > alone + .02 && alone > 0 && teacher.state().credit(WorkerAttribute.LEARNING).signum() > 0,
                    "Teacher bonus was cosmetic or persisted after teaching stopped: paired=" + measured[1] + " alone=" + alone);
            scene.worker().discard(); teacher.discard(); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "worker_states")
    public static void stateAndLargeCargoRoundTripAndUnknownSchemaRemainUntouched(GameTestHelper helper) {
        Scene scene = scene(helper); WorkerEntity worker = scene.worker();
        worker.state().birth(WorkerProfile.Interest.SCIENTIST, WorkerProfile.Temperament.STEADY);
        worker.state().practice(WorkerAttribute.MINING, WorkerState.TICKS_PER_POINT, 1);
        worker.state().addPressure(WorkerProfile.StressCause.FATIGUE, 20);
        worker.carry(new ItemStack(Objects.requireNonNull(Items.DIRT), 193)); UUID cargo = Objects.requireNonNull(UUID.randomUUID()); worker.cargoTask(cargo);
        worker.directive(new WorkerDirective(WorkerDirective.Kind.STUDY_ATTRIBUTE, new BlockPos(5, 1, 5), null, "BUILDING", false, 1));
        Objects.requireNonNull(worker.directive()).progress = 125.5;
        CompoundTag saved = new CompoundTag(); worker.addAdditionalSaveData(saved); worker.readAdditionalSaveData(saved);
        helper.assertTrue(worker.cargoCount() == 193 && worker.getMainHandItem().getCount() == 64 && cargo.equals(worker.cargoTask()), "Large cargo or ownership was truncated by vanilla Count NBT");
        helper.assertTrue(worker.state().points(WorkerAttribute.MINING).equals(BigInteger.TWO) && worker.state().interest() == WorkerProfile.Interest.SCIENTIST
                && Objects.requireNonNull(worker.directive()).progress == 125.5 && worker.state().rawPressure() == 20, "State or study progress was lost");
        CompoundTag unknown = Objects.requireNonNull(saved.getCompound("ColonyState")); unknown.putInt("schemaVersion", 99); unknown.putString("future", "retain");
        worker.readAdditionalSaveData(saved); helper.assertTrue(worker.stateReadOnly() && !worker.available(), "Unknown state kept working");
        CompoundTag again = new CompoundTag(); worker.addAdditionalSaveData(again);
        helper.assertTrue(unknown.equals(again.get("ColonyState")), "Unsupported worker state was rewritten");
        unknown.putInt("schemaVersion", 1); worker.readAdditionalSaveData(saved);
        again = new CompoundTag(); worker.addAdditionalSaveData(again);
        helper.assertTrue(worker.stateReadOnly() && unknown.equals(again.get("ColonyState")), "Same-schema future fields were erased");
        unknown.remove("future");
        unknown.put("directive", new WorkerDirective(WorkerDirective.Kind.STUDY_PROFESSION, new BlockPos(5, 1, 5), null, "MINER", true, 3).save());
        worker.readAdditionalSaveData(saved);
        again = new CompoundTag(); worker.addAdditionalSaveData(again);
        helper.assertTrue(worker.stateReadOnly() && unknown.equals(again.get("ColonyState")), "Invalid additional study was accepted or rewritten");
        worker.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 40, batch = "worker_states")
    public static void printingWithoutHousingKeepsBirthProfileAndOwnership(GameTestHelper helper) {
        Scene scene = scene(helper); scene.worker().discard();
        var level = Objects.requireNonNull(helper.getLevel());
        MachineBlockEntity core = machine(helper, scene.player(), new BlockPos(8, 1, 8), Objects.requireNonNull(ColonyContent.PRINTING_CORE.get()));
        ConstructionManager manager = ConstructionManager.get(level);
        helper.assertTrue(Residences.available(level, Objects.requireNonNull(scene.player().getUUID())) == null, "Scene unexpectedly contained housing");
        helper.assertTrue(manager.printWorker(scene.player(), Objects.requireNonNull(core.getBlockPos()), WorkerProfile.Interest.ARCHITECT, WorkerProfile.Temperament.CAREFUL).equals("success"), "No housing prevented printing");
        var printed = manager.workers(level).stream().filter(worker -> scene.player().getUUID().equals(worker.owner())).toList();
        helper.assertTrue(printed.size() == 1, "Printing created an incorrect worker count");
        WorkerEntity worker = Objects.requireNonNull(printed.get(0));
        helper.assertTrue(worker.isAlive() && worker.home() == null && worker.available()
                && worker.state().points(WorkerAttribute.BUILDING).equals(BigInteger.TWO)
                && worker.state().interest() == WorkerProfile.Interest.ARCHITECT
                && worker.state().temperament() == WorkerProfile.Temperament.CAREFUL, "Unhoused birth lost its profile or could not work");
        helper.assertTrue(manager.printWorker(scene.player(), Objects.requireNonNull(core.getBlockPos())).equals("cooldown"), "Unhoused printing bypassed cooldown");
        worker.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 40, batch = "worker_states")
    public static void occupiedHousingDoesNotBlockPrintingOrStealTheRoom(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        MachineBlockEntity residence = room(helper, scene.player());
        helper.assertTrue(Residences.assign(level, scene.worker()), "Resident could not claim the only room");
        machine(helper, scene.player(), new BlockPos(4, 1, 4), Objects.requireNonNull(ColonyContent.RESIDENCE.get()));
        helper.assertTrue(Residences.available(level, Objects.requireNonNull(scene.player().getUUID())) == null, "Duplicate marker offered the occupied room");
        MachineBlockEntity core = machine(helper, scene.player(), new BlockPos(8, 1, 8), Objects.requireNonNull(ColonyContent.PRINTING_CORE.get()));
        ConstructionManager manager = ConstructionManager.get(level);
        helper.assertTrue(manager.printWorker(scene.player(), Objects.requireNonNull(core.getBlockPos())).equals("success"), "Occupied housing blocked printing");
        WorkerEntity printed = Objects.requireNonNull(manager.workers(level).stream()
                .filter(worker -> scene.player().getUUID().equals(worker.owner()) && !worker.getUUID().equals(scene.worker().getUUID())).findFirst().orElseThrow());
        helper.assertTrue(printed.home() == null && printed.available() && printed.state().points(WorkerAttribute.BUILDING).equals(BigInteger.ONE), "Unhoused neutral worker was not ready to work");
        helper.assertTrue(Residences.home(level, scene.worker()) != null && scene.worker().getUUID().equals(residence.resident()), "Printing stole or invalidated the existing room claim");
        scene.worker().discard(); printed.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 40, batch = "worker_states")
    public static void housingIsPhysicalUniqueAndAssignedWhenAvailable(GameTestHelper helper) {
        Scene scene = scene(helper); scene.worker().discard();
        MachineBlockEntity core = machine(helper, scene.player(), new BlockPos(8, 1, 8), Objects.requireNonNull(ColonyContent.PRINTING_CORE.get()));
        ConstructionManager manager = ConstructionManager.get(Objects.requireNonNull(helper.getLevel()));
        MachineBlockEntity residence = room(helper, scene.player());
        helper.setBlock(new BlockPos(3, 1, 2), Objects.requireNonNull(Blocks.AIR));
        helper.assertTrue(Residences.inspect(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(residence.getBlockPos())) == null, "An incomplete bed granted a home");
        residence = room(helper, scene.player());
        helper.assertTrue(Residences.inspect(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(residence.getBlockPos())) != null, "Player-built enclosed bedroom was rejected");
        helper.assertTrue(manager.printWorker(scene.player(), Objects.requireNonNull(core.getBlockPos()), WorkerProfile.Interest.ARCHITECT, WorkerProfile.Temperament.CAREFUL).equals("success"), "Printing with a vacant home failed");
        var printed = manager.workers(Objects.requireNonNull(helper.getLevel())).stream().filter(worker -> scene.player().getUUID().equals(worker.owner())).findFirst().orElseThrow();
        helper.assertTrue(printed.state().points(WorkerAttribute.BUILDING).equals(BigInteger.TWO) && printed.home() != null, "Birth profile or housing assignment was missing");
        machine(helper, scene.player(), new BlockPos(4, 1, 4), Objects.requireNonNull(ColonyContent.RESIDENCE.get()));
        helper.assertTrue(Residences.available(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.player().getUUID())) == null, "Two markers created two homes in one room");
        helper.setBlock(new BlockPos(3, 3, 3), Objects.requireNonNull(Blocks.AIR));
        helper.assertTrue(Residences.home(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(printed)) == null && printed.isAlive(), "Damaged home stayed valid or deleted its resident");
        CompoundTag facility = Objects.requireNonNull(residence.saveWithoutMetadata());
        var futureLiving = Objects.requireNonNull(facility.getCompound("Living")); futureLiving.putString("future", "keep"); residence.load(facility);
        helper.assertTrue(residence.facilityReadOnly() && futureLiving.equals(residence.saveWithoutMetadata().get("Living")), "Future housing fields were erased");
        printed.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 600, batch = "worker_states")
    public static void dualStudyHasHardFacilitiesSoftAttributesAndPersistentGrowth(GameTestHelper helper) {
        Scene scene = scene(helper); room(helper, scene.player());
        helper.assertTrue(Residences.assign(Objects.requireNonNull(helper.getLevel()), scene.worker()), "Valid home could not be assigned");
        MachineBlockEntity school = machine(helper, scene.player(), new BlockPos(7, 1, 5), Objects.requireNonNull(ColonyContent.ACADEMY.get()));
        scene.worker().state().birth(WorkerProfile.Interest.SCIENTIST, WorkerProfile.Temperament.STEADY);
        scene.worker().state().profession(WorkerProfile.Profession.MINER, false);
        JsonObject request = intent(scene.worker(), "profession"); request.addProperty("station", school.getBlockPos().asLong()); request.addProperty("course", "BUILDER"); request.addProperty("additional", true);
        boolean rejected = false;
        try { WorkerOrders.request(scene.player(), request); } catch (IllegalArgumentException expected) { rejected = "school".equals(expected.getMessage()); }
        helper.assertTrue(rejected && scene.worker().directive() == null, "Low-tier academy bypassed dual-degree gate");
        school.schoolLevel(3); WorkerOrders.request(scene.player(), request);
        helper.assertTrue(scene.worker().directive() != null && scene.worker().state().points(WorkerAttribute.BUILDING).equals(BigInteger.ONE), "Soft mismatch was treated as an impossible course");
        helper.succeedWhen(() -> {
            helper.assertTrue(scene.worker().state().secondary() == WorkerProfile.Profession.BUILDER && scene.worker().directive() == null, "Physical dual-degree study failed: " + scene.worker().lifeReason());
            helper.assertTrue(scene.worker().state().primary() == WorkerProfile.Profession.MINER && scene.worker().state().points(WorkerAttribute.BUILDING).compareTo(BigInteger.TWO) >= 0
                    && scene.worker().state().pressureSources().containsKey(WorkerProfile.StressCause.DOUBLE_DEGREE)
                    && scene.worker().state().pressureSources().containsKey(WorkerProfile.StressCause.FORCED_STUDY), "Learning lost an old profession/growth or concealed pressure");
        });
    }
    @GameTest(template = "construction", timeoutTicks = 160, batch = "worker_states")
    public static void fullStressStrikesRetainsCargoAndRecoversBeforeReturningToWork(GameTestHelper helper) {
        Scene scene = scene(helper); WorkerEntity worker = scene.worker(); worker.carry(new ItemStack(Objects.requireNonNull(Items.DIRT), 7));
        worker.state().lifeImproved(27, 2); worker.state().addPressure(WorkerProfile.StressCause.OVERWORK, 105);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(worker.state().striking() && !worker.available() && worker.cargoCount() == 7 && worker.state().loyalty() < 50, "Crisis failed to stop work or lost cargo");
            worker.state().recover(100);
        });
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(!worker.state().striking() && worker.available() && worker.cargoCount() == 7, "Recovered worker stayed stuck or consumed cargo"); worker.discard(); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 160, batch = "worker_states")
    public static void hungryWorkerEatsActualOwnedDropWithoutReplacingConstructionCargo(GameTestHelper helper) {
        Scene scene = scene(helper); WorkerEntity worker = scene.worker(); worker.carry(new ItemStack(Objects.requireNonNull(Items.DIRT), 7));
        worker.state().restoreLife(5, 1, 1, 2, 0, false, 0, Objects.requireNonNull(BigInteger.ZERO), 0, false);
        ItemEntity bread = new ItemEntity(Objects.requireNonNull(helper.getLevel()), worker.getX(), worker.getY() + 0.1, worker.getZ(), new ItemStack(Objects.requireNonNull(Items.BREAD), 2));
        bread.setTarget(scene.player().getUUID()); bread.setNoPickUpDelay(); bread.setNoGravity(true); bread.setDeltaMovement(Objects.requireNonNull(net.minecraft.world.phys.Vec3.ZERO)); helper.getLevel().addFreshEntity(bread);
        helper.succeedWhen(() -> {
            helper.assertTrue(worker.state().hunger() > 6, "Hungry worker did not consume reachable food");
            helper.assertTrue(bread.isAlive() && bread.getItem().getCount() == 1 && worker.cargoCount() == 7
                    && worker.state().credit(WorkerAttribute.HUNGER).signum() > 0, "Eating duplicated food or replaced transport cargo");
            worker.discard(); bread.discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 140, batch = "worker_orders")
    public static void executionRequiresPlayerLoyaltyAndRealWitnessSight(GameTestHelper helper) {
        Scene scene = scene(helper); WorkerEntity executor = scene.worker();
        BlockPos at = Objects.requireNonNull(helper.absolutePos(new BlockPos(6, 1, 4))); executor.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        var target = Objects.requireNonNull(EntityType.CHICKEN.create(Objects.requireNonNull(helper.getLevel()))); BlockPos victim = Objects.requireNonNull(helper.absolutePos(new BlockPos(7, 1, 4)));
        target.moveTo(victim.getX() + 0.5, victim.getY(), victim.getZ() + 0.5, 0, 0); target.setNoAi(true); helper.getLevel().addFreshEntity(target);
        WorkerEntity visible = worker(helper, Objects.requireNonNull(UUID.randomUUID()), new BlockPos(5, 1, 3));
        WorkerEntity hidden = worker(helper, Objects.requireNonNull(UUID.randomUUID()), new BlockPos(9, 1, 4));
        for (int z = 1; z <= 9; z++) for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(8, y, z), Objects.requireNonNull(Blocks.STONE));
        JsonObject request = intent(executor, "execute"); request.addProperty("target", target.getUUID().toString());
        boolean refused = false; try { WorkerOrders.request(scene.player(), request); } catch (IllegalArgumentException expected) { refused = "loyalty".equals(expected.getMessage()); }
        helper.assertTrue(refused && executor.directive() == null && target.isAlive() && executor.state().pressureSources().containsKey(WorkerProfile.StressCause.REFUSED_ORDER), "Low loyalty issued an autonomous execution");
        executor.state().lifeImproved(32, 2); WorkerOrders.request(scene.player(), request);
        helper.succeedWhen(() -> {
            helper.assertTrue(!target.isAlive(), "Loyal worker did not execute the physical player order");
            helper.assertTrue(visible.state().pressureSources().containsKey(WorkerProfile.StressCause.WITNESSED_EXECUTION)
                    && !hidden.state().pressureSources().containsKey(WorkerProfile.StressCause.WITNESSED_EXECUTION)
                    && !hidden.state().pressureSources().containsKey(WorkerProfile.StressCause.HEARD_EXECUTION), "Witness logic ignored real walls or colony communication ownership");
            executor.discard(); visible.discard(); hidden.discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "worker_orders")
    public static void moraleUsesWitnessLoyaltyAndDistinctSuccessFailureMappings(GameTestHelper helper) {
        Scene scene = scene(helper); WorkerEntity loyal = worker(helper, Objects.requireNonNull(scene.player().getUUID()), new BlockPos(8, 1, 5));
        loyal.state().lifeImproved(32, 2); WorkerEntity neutral = worker(helper, Objects.requireNonNull(scene.player().getUUID()), new BlockPos(7, 1, 6));
        scene.worker().directive(new WorkerDirective(WorkerDirective.Kind.SACRIFICE, null, Objects.requireNonNull(UUID.randomUUID()), "", false, 1));
        long time = helper.getLevel().getGameTime(); float health = scene.worker().getHealth();
        WorkerOrders.sacrificeEnded(Objects.requireNonNull(helper.getLevel()), scene.worker(), true); double success = WorkerEffects.mining(loyal.state(), time);
        helper.assertTrue(success > 1 && WorkerEffects.mining(neutral.state(), time) == 1 && scene.worker().getHealth() == health, "Sacrifice invented a health cost or buffed neutral observers");
        WorkerOrders.sacrificeEnded(Objects.requireNonNull(helper.getLevel()), scene.worker(), false);
        helper.assertTrue(WorkerEffects.mining(loyal.state(), time) > 1 && WorkerEffects.mining(loyal.state(), time) < success
                && WorkerEffects.mining(loyal.state(), time + 24000) == 1, "Failure and success were identical or temporary morale became permanent");
        scene.worker().discard(); loyal.discard(); neutral.discard(); helper.succeed();
    }
    @Nonnull public static MachineBlockEntity room(GameTestHelper helper, FakePlayer player) {
        return room(helper, player, 0);
    }
    @Nonnull private static MachineBlockEntity room(GameTestHelper helper, FakePlayer player, int xOffset) {
        for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) {
            helper.setBlock(new BlockPos(x + xOffset, 0, z), Objects.requireNonNull(Blocks.STONE));
            helper.setBlock(new BlockPos(x + xOffset, 3, z), Objects.requireNonNull(Blocks.OAK_PLANKS));
            for (int y = 1; y <= 2; y++) helper.setBlock(new BlockPos(x + xOffset, y, z), Objects.requireNonNull(x == 1 || x == 5 || z == 1 || z == 5 ? Blocks.OAK_PLANKS : Blocks.AIR));
        }
        for (int y = 1; y <= 2; y++) helper.setBlock(new BlockPos(3 + xOffset, y, 5), Objects.requireNonNull(Blocks.OAK_DOOR.defaultBlockState()
                .setValue(Objects.requireNonNull(DoorBlock.FACING), Direction.SOUTH).setValue(Objects.requireNonNull(DoorBlock.OPEN), true)
                .setValue(Objects.requireNonNull(DoorBlock.HALF), y == 1 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER)));
        helper.setBlock(new BlockPos(3 + xOffset, 1, 2), Objects.requireNonNull(Blocks.WHITE_BED.defaultBlockState().setValue(Objects.requireNonNull(BedBlock.FACING), Direction.SOUTH).setValue(Objects.requireNonNull(BedBlock.PART), BedPart.FOOT)));
        helper.setBlock(new BlockPos(3 + xOffset, 1, 3), Objects.requireNonNull(Blocks.WHITE_BED.defaultBlockState().setValue(Objects.requireNonNull(BedBlock.FACING), Direction.SOUTH).setValue(Objects.requireNonNull(BedBlock.PART), BedPart.HEAD)));
        return machine(helper, player, new BlockPos(2 + xOffset, 1, 2), Objects.requireNonNull(ColonyContent.RESIDENCE.get()));
    }
    @Nonnull static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y <= 5; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        UUID owner = Objects.requireNonNull(UUID.randomUUID()); FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(helper.getLevel(), new GameProfile(owner, "WorkerStateTest")));
        BlockPos pos = Objects.requireNonNull(helper.absolutePos(new BlockPos(8, 1, 6))); player.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        player.getAbilities().mayBuild = true; return new Scene(player, worker(helper, owner, new BlockPos(8, 1, 6)));
    }
    @Nonnull static WorkerEntity worker(GameTestHelper helper, UUID owner, BlockPos relative) {
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); worker.owner(owner);
        BlockPos pos = Objects.requireNonNull(helper.absolutePos(relative)); worker.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0); helper.getLevel().addFreshEntity(worker); return worker;
    }
    @Nonnull private static MachineBlockEntity machine(GameTestHelper helper, FakePlayer player, BlockPos relative, net.minecraft.world.level.block.Block block) {
        helper.setBlock(relative, block); MachineBlockEntity machine = (MachineBlockEntity) Objects.requireNonNull(helper.getBlockEntity(relative)); machine.owner(Objects.requireNonNull(player.getUUID())); return machine;
    }
    @Nonnull private static JsonObject intent(WorkerEntity worker, String operation) { JsonObject data = new JsonObject(); data.addProperty("id", worker.getUUID().toString()); data.addProperty("operation", operation); return data; }
}
