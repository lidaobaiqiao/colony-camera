package dev.colony.camera.work;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.worker.WorkerPreferences;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class ItemSweepGameTests {
    @GameTest(template = "construction", timeoutTicks = 500, batch = "item_sweep_real")
    public static void matchingTypesAreSweptPhysicallyWhileUnreachableForeignAndNewDropsStay(GameTestHelper helper) {
        var scene = SweepRecoveryGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        ItemEntity iron = drop(helper, new BlockPos(4, 1, 3), named(Objects.requireNonNull(Items.IRON_INGOT), 5, "Original iron"));
        ItemEntity diamond = drop(helper, new BlockPos(6, 1, 3), named(Objects.requireNonNull(Items.DIAMOND), 7, "Original diamonds"));
        ItemEntity dirt = drop(helper, new BlockPos(4, 1, 6), new ItemStack(Objects.requireNonNull(Items.DIRT), 11));
        for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) for (int y = 1; y <= 3; y++)
            if (x != 8 || z != 8 || y == 3) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.STONE));
        ItemEntity unreachable = drop(helper, new BlockPos(8, 1, 8), named(Objects.requireNonNull(Items.IRON_INGOT), 2, "Inside wall"));
        ItemEntity foreign = drop(helper, new BlockPos(4, 1, 8), named(Objects.requireNonNull(Items.DIAMOND), 3, "Foreign")); foreign.setTarget(Objects.requireNonNull(UUID.randomUUID()));
        ItemStack expectedIron = Objects.requireNonNull(iron.getItem().copy()), expectedDiamond = Objects.requireNonNull(diamond.getItem().copy());
        var manager = ColonyWorkManager.get(level); manager.sweepItems(scene.player(), Objects.requireNonNull(List.of("minecraft:iron_ingot", "minecraft:diamond")));
        var job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        ItemEntity newer = drop(helper, new BlockPos(3, 1, 7), named(Objects.requireNonNull(Items.IRON_INGOT), 9, "Later batch"));
        helper.assertTrue(job.itemSweep() && !job.surfaceSweep && !job.sweepDrops.contains(foreign.getUUID()) && !job.sweepDrops.contains(newer.getUUID()), "Type sweep captured foreign or future items");
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Finite type sweep did not finish reachable drops: " + job.reason + " at " + scene.worker().position());
            var store = StorageAccess.at(level, Objects.requireNonNull(scene.warehouse().getBlockPos()), job.owner);
            helper.assertTrue(!iron.isAlive() && !diamond.isAlive() && store.count(expectedIron) == 5 && store.count(expectedDiamond) == 7
                    && scene.worker().cargoCount() == 0 && unreachable.isAlive() && unreachable.getItem().getCount() == 2 && dirt.isAlive() && foreign.isAlive() && newer.isAlive(), "Sweeping ignored types, reachability, ownership, NBT or conservation");
            scene.worker().discard(); dirt.discard(); unreachable.discard(); foreign.discard(); newer.discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 350, batch = "item_sweep_disable")
    public static void disablingSweepAfterPickupStillReturnsTaskCargoAndCompletes(GameTestHelper helper) {
        var scene = SweepRecoveryGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        ItemEntity iron = drop(helper, new BlockPos(7, 1, 3), named(Objects.requireNonNull(Items.IRON_INGOT), 9, "Finish the delivery")); ItemStack expected = Objects.requireNonNull(iron.getItem().copy());
        var manager = ColonyWorkManager.get(level); manager.sweepItems(scene.player(), Objects.requireNonNull(List.of("minecraft:iron_ingot")));
        var job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0)); boolean[] disabled = {false};
        helper.onEachTick(() -> {
            if (!disabled[0] && scene.worker().cargoCount() > 0) { disabled[0] = true; scene.worker().preferences().allows(WorkerPreferences.Task.SWEEP, false); manager.redispatch(); }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(disabled[0] && job.finished() && scene.worker().cargoCount() == 0 && scene.worker().cargoTask() == null, "Disabling sweep stranded active cargo: " + job.reason);
            helper.assertTrue(StorageAccess.at(level, Objects.requireNonNull(scene.warehouse().getBlockPos()), job.owner).count(expected) == 9, "Disabled delivery lost actual items"); scene.worker().discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "item_sweep_schema")
    public static void typeSweepReloadKeepsItsFiniteBatchAndRejectsIncompleteSchema(GameTestHelper helper) {
        var scene = SweepRecoveryGameTests.scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        ItemEntity item = drop(helper, new BlockPos(4, 1, 3), new ItemStack(Objects.requireNonNull(Items.IRON_INGOT), 5));
        var manager = ColonyWorkManager.get(level); manager.sweepItems(scene.player(), Objects.requireNonNull(List.of("minecraft:iron_ingot")));
        var job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0)); CompoundTag saved = manager.save(new CompoundTag());
        var restored = ColonyWorkManager.load(saved); var reloaded = Objects.requireNonNull(restored.jobs(job.owner).get(0));
        helper.assertTrue(!restored.readOnly() && reloaded.itemSweep() && reloaded.itemTypes.equals(job.itemTypes) && reloaded.sweepDrops.contains(item.getUUID()) && reloaded.targets.equals(job.targets), "Type sweep reload expanded or erased intent");
        CompoundTag invalid = Objects.requireNonNull(saved.copy()); var entries = invalid.getList("jobs", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) { var entry = entries.getCompound(index); if (entry.hasUUID("id") && job.id.equals(entry.getUUID("id"))) entry.remove("sweepDrops"); }
        var rejected = ColonyWorkManager.load(invalid);
        helper.assertTrue(rejected.readOnly() && invalid.equals(rejected.save(new CompoundTag())), "Incomplete type-sweep data was guessed or rewritten");
        manager.update(job.owner, job.id, "cancel", 0); item.discard(); scene.worker().discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "item_sweep_schema")
    public static void invalidTypeIntentDoesNotCreateAJobOrMoveResources(GameTestHelper helper) {
        var scene = SweepRecoveryGameTests.scene(helper); var manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        int count = manager.jobs(Objects.requireNonNull(scene.player().getUUID())).size(); boolean denied = false;
        try { manager.sweepItems(scene.player(), Objects.requireNonNull(List.of("minecraft:missing_item"))); } catch (IllegalArgumentException expected) { denied = "invalid".equals(expected.getMessage()); }
        helper.assertTrue(denied && manager.jobs(Objects.requireNonNull(scene.player().getUUID())).size() == count, "Unknown type created a ghost order");
        scene.worker().discard(); helper.succeed();
    }
    @Nonnull private static ItemStack named(net.minecraft.world.item.Item item, int count, String name) { ItemStack value = new ItemStack(item, count); value.setHoverName(Objects.requireNonNull(net.minecraft.network.chat.Component.literal(name))); return value; }
    @Nonnull private static ItemEntity drop(GameTestHelper helper, BlockPos relative, ItemStack stack) {
        var level = Objects.requireNonNull(helper.getLevel()); BlockPos pos = Objects.requireNonNull(helper.absolutePos(relative)); ItemEntity item = new ItemEntity(level, pos.getX() + .5, pos.getY() + .02, pos.getZ() + .5, stack);
        item.setNoGravity(true); item.setNoPickUpDelay(); item.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); level.addFreshEntity(item); return item;
    }
}
