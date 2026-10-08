package dev.colony.camera.worker;

import com.google.gson.JsonObject;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.work.ColonyWorkManager;
import dev.colony.camera.work.MiningService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class WorkerConfigurationGameTests {
    @GameTest(template = "construction", timeoutTicks = 30, batch = "residence_unloaded_claim")
    public static void changingHomesNeverLoadsThePreviousUnloadedClaim(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var home = WorkerStateGameTests.room(helper, scene.player());
        BlockPos old = new BlockPos(1_000_000, 10, 1_000_000); scene.worker().home(old);
        helper.assertTrue(!level.getChunkSource().hasChunk(old.getX() >> 4, old.getZ() >> 4), "The unloaded claim fixture was already loaded");
        boolean assigned = Residences.assign(level, scene.worker(), Objects.requireNonNull(home.getBlockPos())); Residences.release(level, scene.worker());
        helper.assertTrue(!assigned && old.equals(scene.worker().home()) && home.resident() == null && !level.getChunkSource().hasChunk(old.getX() >> 4, old.getZ() >> 4), "Home reconfiguration stole or force-loaded an unavailable prior claim");
        scene.worker().discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 450, batch = "worker_build_settings")
    public static void disablingAndReenablingBuildControlsActualConstruction(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var worker = scene.worker();
        var target = Objects.requireNonNull(helper.absolutePos(new BlockPos(7, 1, 6)));
        var preset = new dev.colony.camera.preset.BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "Settings build", 1, 1, 1,
                Objects.requireNonNull(List.of(new dev.colony.camera.preset.BlockPreset.Cell(0, 0, 0, new dev.colony.camera.preset.BlockPreset.State("minecraft:cobblestone", Objects.requireNonNull(java.util.Map.of()))))));
        var placement = new dev.colony.camera.planning.ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, target.getX(), target.getY(), target.getZ(), 0, false, true);
        helper.setBlock(new BlockPos(9, 1, 6), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        var store = Objects.requireNonNull((MachineBlockEntity) helper.getBlockEntity(new BlockPos(9, 1, 6))); store.owner(Objects.requireNonNull(worker.owner())); store.inventory.insertItem(0, new ItemStack(Objects.requireNonNull(Items.COBBLESTONE)), false);
        worker.preferences().allows(WorkerPreferences.Task.BUILD, false); var manager = dev.colony.camera.construction.ConstructionManager.get(level); manager.create(scene.player(), placement, 5);
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(worker.assignment == null && level.getBlockState(target).isAir() && store.inventory.getStackInSlot(0).getCount() == 1, "Disabled builder consumed stock or created a block");
            worker.preferences().allows(WorkerPreferences.Task.BUILD, true); ColonyWorkManager.get(level).redispatch();
        });
        helper.succeedWhen(() -> {
            var project = manager.project(placement.id()); helper.assertTrue(project != null && project.finished() && level.getBlockState(target).is(Objects.requireNonNull(Blocks.COBBLESTONE)), "Re-enabled builder never completed its actual project");
            helper.assertTrue(store.inventory.getStackInSlot(0).isEmpty() && worker.cargoCount() == 0, "Construction material was duplicated or lost"); worker.discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 60, batch = "worker_equipment_attributes")
    public static void equippedArmorAffectsNativeDefenseAndPersistsAsAnActualStack(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var worker = scene.worker(); int before = worker.getArmorValue();
        ItemStack incoming = named(Objects.requireNonNull(Items.IRON_HELMET), "Persistent armor", 12); scene.player().getInventory().setItem(0, incoming);
        WorkerOrders.request(scene.player(), equipment(worker, EquipmentSlot.HEAD, 0, incoming));
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(worker.getArmorValue() > before, "Equipped armor did not feed native defense attributes");
            WorkerEntity restored = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); restored.readAdditionalSaveData(Objects.requireNonNull(worker.saveWithoutId(new CompoundTag())));
            helper.assertTrue(ItemStack.matches(incoming, Objects.requireNonNull(restored.getItemBySlot(EquipmentSlot.HEAD))), "Equipment NBT was not preserved by native entity saving"); worker.discard(); restored.discard(); helper.succeed();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "worker_configuration_permission")
    public static void otherColoniesCannotConfigureWorkersOrHomes(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var home = WorkerStateGameTests.room(helper, scene.player());
        scene.worker().owner(Objects.requireNonNull(UUID.randomUUID())); home.owner(Objects.requireNonNull(UUID.randomUUID()));
        JsonObject task = intent(scene.worker(), "task"); task.addProperty("task", "MINE"); task.addProperty("enabled", false);
        JsonObject housing = residence(Objects.requireNonNull(home.getBlockPos()), "automatic"); housing.addProperty("enabled", false);
        helper.assertTrue(rejects(() -> WorkerOrders.request(scene.player(), task), "denied") && rejects(() -> ResidenceOrders.request(scene.player(), housing), "denied")
                && scene.worker().preferences().allows(WorkerPreferences.Task.MINE) && home.automaticHousing(), "Cross-owner configuration bypassed validation"); scene.worker().discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "worker_equipment")
    public static void namedDamagedEquipmentExchangesAtomicallyWithoutTouchingCargo(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var player = scene.player(); var worker = scene.worker();
        ItemStack incoming = named(Objects.requireNonNull(Items.LEATHER_HELMET), "New helmet", 12), old = named(Objects.requireNonNull(Items.IRON_HELMET), "Old helmet", 9);
        player.getInventory().setItem(0, incoming); worker.setItemSlot(EquipmentSlot.HEAD, old); worker.carry(new ItemStack(Objects.requireNonNull(Items.DIRT), 7));
        UUID cargo = Objects.requireNonNull(UUID.randomUUID()); worker.cargoTask(cargo); JsonObject request = equipment(worker, EquipmentSlot.HEAD, 0, incoming);
        WorkerOrders.request(player, request);
        helper.assertTrue(ItemStack.matches(Objects.requireNonNull(worker.getItemBySlot(EquipmentSlot.HEAD)), incoming) && ItemStack.matches(Objects.requireNonNull(player.getInventory().getItem(0)), old)
                && worker.cargoCount() == 7 && cargo.equals(worker.cargoTask()), "Equipment exchange lost NBT, items or cargo ownership");
        JsonObject remove = equipment(worker, EquipmentSlot.HEAD, -1, Objects.requireNonNull(ItemStack.EMPTY)); WorkerOrders.request(player, remove);
        helper.assertTrue(worker.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && player.getInventory().getItem(1).getDamageValue() == 12, "Removal did not return the actual worn item");
        worker.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "worker_equipment")
    public static void changedInventoryUnsuitableSlotAndFullReturnInventoryPreserveBothSides(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var player = scene.player(); var worker = scene.worker();
        ItemStack advertised = named(Objects.requireNonNull(Items.LEATHER_HELMET), "Advertised", 0), old = named(Objects.requireNonNull(Items.IRON_HELMET), "Old", 1);
        worker.setItemSlot(EquipmentSlot.HEAD, old); player.getInventory().setItem(0, new ItemStack(Objects.requireNonNull(Items.DIRT), 64));
        boolean stale = rejects(() -> WorkerOrders.request(player, equipment(worker, EquipmentSlot.HEAD, 0, advertised)), "equipment");
        boolean wrongSlot = rejects(() -> WorkerOrders.request(player, equipment(worker, EquipmentSlot.HEAD, 0, Objects.requireNonNull(player.getInventory().getItem(0)))), "equipment");
        for (int index = 0; index < 36; index++) player.getInventory().setItem(index, new ItemStack(Objects.requireNonNull(Items.DIRT), 64));
        boolean full = rejects(() -> WorkerOrders.request(player, equipment(worker, EquipmentSlot.HEAD, -1, Objects.requireNonNull(ItemStack.EMPTY))), "inventory_full");
        boolean cargoSlot = rejects(() -> WorkerOrders.request(player, equipment(worker, EquipmentSlot.MAINHAND, -1, Objects.requireNonNull(ItemStack.EMPTY))), "invalid");
        helper.assertTrue(stale && wrongSlot && full && cargoSlot && ItemStack.matches(old, Objects.requireNonNull(worker.getItemBySlot(EquipmentSlot.HEAD))) && player.getInventory().getItem(0).getCount() == 64, "Rejected equipment operation partially moved items");
        worker.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "worker_preferences")
    public static void preferencesMigratePersistAndPreserveUnknownDataReadOnly(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var worker = scene.worker();
        worker.preferences().allows(WorkerPreferences.Task.MINE, false); worker.preferences().automaticTools(false);
        CompoundTag saved = Objects.requireNonNull(worker.saveWithoutId(new CompoundTag())); WorkerEntity loaded = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel()))); loaded.readAdditionalSaveData(saved);
        helper.assertTrue(!loaded.stateReadOnly() && !loaded.preferences().allows(WorkerPreferences.Task.MINE) && !loaded.preferences().automaticTools() && loaded.preferences().allows(WorkerPreferences.Task.SWEEP), "Preferences did not persist independently");
        CompoundTag legacy = Objects.requireNonNull(saved.copy()); legacy.remove("ColonyPreferences"); loaded.readAdditionalSaveData(legacy);
        helper.assertTrue(!loaded.stateReadOnly() && loaded.preferences().allows(WorkerPreferences.Task.MINE) && loaded.preferences().automaticTools(), "Legacy worker did not inherit working defaults");
        var future = Objects.requireNonNull(saved.getCompound("ColonyPreferences")); future.putInt("schemaVersion", 99); loaded.readAdditionalSaveData(saved);
        helper.assertTrue(loaded.stateReadOnly() && future.equals(loaded.saveWithoutId(new CompoundTag()).get("ColonyPreferences")), "Unknown preferences were erased or executed");
        worker.discard(); loaded.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "worker_task_filters")
    public static void disabledTasksAreExcludedByTheActualSharedScheduler(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var worker = scene.worker(); worker.setOnGround(true);
        helper.setBlock(new BlockPos(7, 1, 6), Objects.requireNonNull(Blocks.DIRT)); var manager = ColonyWorkManager.get(level);
        manager.mine(scene.player(), Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(7, 1, 6)))));
        var job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        JsonObject request = intent(worker, "task"); request.addProperty("task", "MINE"); request.addProperty("enabled", false); WorkerOrders.request(scene.player(), request);
        manager.dispatchJob(level, job, Objects.requireNonNull(List.of(worker)), new HashSet<>(), new HashSet<>(), new ReservationLedger(), level.getGameTime());
        helper.assertTrue(worker.workTask == null && job.done == 0, "Disabled miner still claimed work");
        request.addProperty("enabled", true); WorkerOrders.request(scene.player(), request);
        manager.dispatchJob(level, job, Objects.requireNonNull(List.of(worker)), new HashSet<>(), new HashSet<>(), new ReservationLedger(), level.getGameTime());
        helper.assertTrue(worker.workTask != null, "Re-enabled task did not resume through the scheduler");
        manager.update(Objects.requireNonNull(worker.owner()), job.id, "cancel", 0); worker.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "worker_manual_tools")
    public static void manualToolSettingPreventsAutomaticStockCollection(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var worker = scene.worker(); worker.setOnGround(true);
        helper.setBlock(new BlockPos(7, 1, 6), Objects.requireNonNull(Blocks.STONE)); helper.setBlock(new BlockPos(9, 1, 6), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        var store = Objects.requireNonNull((MachineBlockEntity) helper.getBlockEntity(new BlockPos(9, 1, 6))); store.owner(Objects.requireNonNull(worker.owner())); store.inventory.insertItem(0, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)), false);
        var manager = ColonyWorkManager.get(level); manager.mine(scene.player(), Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(7, 1, 6))))); var job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(worker.owner())).get(0));
        worker.preferences().automaticTools(false); var task = MiningService.assign(level, worker, job, new HashSet<>(), new ReservationLedger(), level.getGameTime());
        helper.assertTrue(task == null && worker.getOffhandItem().isEmpty() && !store.inventory.getStackInSlot(0).isEmpty(), "Manual equipment mode still collected a tool");
        worker.preferences().automaticTools(true); task = MiningService.assign(level, worker, job, new HashSet<>(), new ReservationLedger(), level.getGameTime());
        helper.assertTrue(task != null && task.toolPickup, "Automatic tools could not be enabled again");
        manager.update(Objects.requireNonNull(worker.owner()), job.id, "cancel", 0); worker.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "residence_configuration")
    public static void explicitHomeAssignmentHonoursAutoSettingAndPhysicalSingleOccupancy(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel()); var worker = scene.worker();
        var home = WorkerStateGameTests.room(helper, scene.player()); var marker = Objects.requireNonNull(home.getBlockPos()); home.automaticHousing(false);
        helper.assertTrue(!Residences.assign(level, worker), "Automatic placement entered an opt-out home");
        JsonObject request = residence(marker, "assign"); request.addProperty("worker", worker.getUUID().toString()); ResidenceOrders.request(scene.player(), request);
        WorkerEntity other = WorkerStateGameTests.worker(helper, Objects.requireNonNull(worker.owner()), new BlockPos(9, 1, 7));
        helper.assertTrue(Residences.home(level, worker) != null && !Residences.assign(level, other, marker), "Explicit home placement stole an occupied room");
        var detail = Residences.details(level, marker); helper.assertTrue(detail.reason().equals("valid") && detail.area() >= 4 && detail.room() != null, "Physical inspection did not expose details");
        JsonObject rename = residence(marker, "name"); rename.addProperty("name", "工匠之家"); ResidenceOrders.request(scene.player(), rename);
        helper.assertTrue(home.getDisplayName().getString().equals("工匠之家"), "Residence name was only cosmetic client state");
        ResidenceOrders.request(scene.player(), residence(marker, "release")); helper.assertTrue(worker.home() == null && home.resident() == null && !home.automaticHousing(), "Unassignment immediately allowed automatic re-entry");
        helper.setBlock(new BlockPos(3, 3, 3), Objects.requireNonNull(Blocks.AIR)); helper.assertTrue(Residences.details(level, marker).reason().equals("roof"), "Missing roof remained compliant");
        worker.discard(); other.discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "residence_configuration")
    public static void residenceSettingsMigrateAndPreserveMalformedFutureLiving(GameTestHelper helper) {
        var scene = WorkerStateGameTests.scene(helper); var home = WorkerStateGameTests.room(helper, scene.player()); home.automaticHousing(false);
        CompoundTag saved = Objects.requireNonNull(home.saveWithoutMetadata()); home.load(saved); helper.assertTrue(!home.facilityReadOnly() && !home.automaticHousing(), "Housing opt-out was lost on reload");
        CompoundTag legacy = Objects.requireNonNull(saved.copy()); var living = Objects.requireNonNull(legacy.getCompound("Living")); living.putInt("schemaVersion", 1); living.remove("automaticHousing"); home.load(legacy);
        helper.assertTrue(!home.facilityReadOnly() && home.automaticHousing(), "Version one housing did not migrate to automatic defaults");
        var future = Objects.requireNonNull(saved.getCompound("Living")); future.putInt("schemaVersion", 99); home.load(saved);
        helper.assertTrue(home.facilityReadOnly() && future.equals(home.saveWithoutMetadata().get("Living")), "Future housing data was rewritten");
        scene.worker().discard(); helper.succeed();
    }
    private static boolean rejects(Runnable action, String reason) { try { action.run(); return false; } catch (IllegalArgumentException expected) { return reason.equals(expected.getMessage()); } }
    @Nonnull private static ItemStack named(net.minecraft.world.item.Item item, String name, int damage) { ItemStack stack = new ItemStack(item); stack.setHoverName(Objects.requireNonNull(net.minecraft.network.chat.Component.literal(name))); stack.setDamageValue(damage); return stack; }
    @Nonnull private static JsonObject intent(WorkerEntity worker, String operation) { JsonObject value = new JsonObject(); value.addProperty("id", worker.getUUID().toString()); value.addProperty("operation", operation); return value; }
    @Nonnull private static JsonObject equipment(WorkerEntity worker, EquipmentSlot slot, int source, ItemStack expected) { var value = intent(worker, "equipment"); value.addProperty("slot", slot.name()); value.addProperty("source", source); value.addProperty("expected", expected.save(new CompoundTag()).toString()); return value; }
    @Nonnull private static JsonObject residence(BlockPos pos, String operation) { JsonObject value = new JsonObject(); value.addProperty("pos", pos.asLong()); value.addProperty("operation", operation); return value; }
}
