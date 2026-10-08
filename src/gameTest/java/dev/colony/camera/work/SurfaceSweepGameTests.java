package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.storage.StorageAccess;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
public final class SurfaceSweepGameTests {
    private record Scene(@Nonnull FakePlayer player, @Nonnull WorkerEntity worker, @Nonnull MachineBlockEntity warehouse) {}

    @GameTest(template = "construction", timeoutTicks = 600, batch = "surface_sweep")
    public static void disconnectedFloorSelectionDepositsOnlyItsOwnDrops(GameTestHelper helper) {
        Scene scene = scene(helper);
        ItemEntity first = drop(helper, new BlockPos(3, 0, 3), 1.02, new ItemStack(Objects.requireNonNull(Items.DIRT), 17));
        ItemEntity second = drop(helper, new BlockPos(8, 0, 3), 1.02, new ItemStack(Objects.requireNonNull(Items.DIAMOND), 9));
        ItemEntity gap = drop(helper, new BlockPos(5, 0, 3), 1.02, new ItemStack(Objects.requireNonNull(Items.IRON_INGOT), 11));
        ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        manager.sweep(scene.player(), Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(3, 0, 3)), helper.absolutePos(new BlockPos(8, 0, 3)))));
        ColonyJob job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Exact surface sweep stalled: " + job.reason + " at " + scene.worker().position());
            StorageAccess stock = StorageAccess.at(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.warehouse().getBlockPos()), Objects.requireNonNull(scene.player().getUUID()));
            helper.assertTrue(!first.isAlive() && !second.isAlive() && gap.isAlive() && gap.getItem().getCount() == 11
                    && stock.count(new ItemStack(Objects.requireNonNull(Items.DIRT))) == 17 && stock.count(new ItemStack(Objects.requireNonNull(Items.DIAMOND))) == 9
                    && stock.count(new ItemStack(Objects.requireNonNull(Items.IRON_INGOT))) == 0 && scene.worker().getMainHandItem().isEmpty(),
                    "Surface selection expanded across gaps or lost deposited cargo");
            scene.worker().discard(); gap.discard();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 500, batch = "sweep_roof")
    public static void aRoofedWarehouseUsesALowerVisibleStandInsteadOfRepeatingAHiddenOne(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        helper.setBlock(new BlockPos(2, 1, 4), Objects.requireNonNull(Blocks.AIR));
        helper.setBlock(new BlockPos(5, 1, 5), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        MachineBlockEntity warehouse = Objects.requireNonNull((MachineBlockEntity) helper.getBlockEntity(new BlockPos(5, 1, 5))); warehouse.owner(Objects.requireNonNull(scene.player().getUUID()));
        helper.setBlock(new BlockPos(5, 2, 5), Objects.requireNonNull(Blocks.STONE));
        helper.setBlock(new BlockPos(4, 0, 5), Objects.requireNonNull(Blocks.AIR)); helper.setBlock(new BlockPos(4, -1, 5), Objects.requireNonNull(Blocks.STONE));
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(3, 1, 4))); scene.worker().moveTo(start.getX() + .5, start.getY(), start.getZ() + .5);
        BlockPos hidden = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 1, 4))), visible = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 0, 5)));
        var hiddenFeet = dev.colony.camera.resources.GroundResources.bottom(hidden); var visibleFeet = dev.colony.camera.resources.GroundResources.bottom(visible);
        helper.assertTrue(dev.colony.camera.construction.ConstructionManager.standable(level, hidden)
                && !dev.colony.camera.resources.StorageResources.canTakeFrom(level, scene.worker(), hiddenFeet, Objects.requireNonNull(hiddenFeet.add(0, scene.worker().getEyeHeight(), 0)), Objects.requireNonNull(warehouse.getBlockPos()))
                && dev.colony.camera.resources.StorageResources.canTakeFrom(level, scene.worker(), visibleFeet, Objects.requireNonNull(visibleFeet.add(0, scene.worker().getEyeHeight(), 0)), Objects.requireNonNull(warehouse.getBlockPos())),
                "Roof fixture did not distinguish nearby walkable and actually operable stands");
        ItemEntity item = drop(helper, new BlockPos(3, 0, 4), 1.02, new ItemStack(Objects.requireNonNull(Items.DIRT), 8));
        ColonyWorkManager manager = ColonyWorkManager.get(level); manager.sweep(scene.player(), Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(3, 0, 4)))));
        ColonyJob job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Roofed warehouse repeatedly chose a hidden stand: " + job.reason + " at " + scene.worker().position());
            helper.assertTrue(!item.isAlive() && scene.worker().getMainHandItem().isEmpty()
                    && StorageAccess.at(level, Objects.requireNonNull(warehouse.getBlockPos()), Objects.requireNonNull(scene.player().getUUID())).count(new ItemStack(Objects.requireNonNull(Items.DIRT))) == 8,
                    "Roof detour lost or duplicated carried material");
            scene.worker().discard();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "surface_membership")
    public static void topMembershipFollowsSlabsAndRejectsOtherFloorsAndGaps(GameTestHelper helper) {
        Scene scene = scene(helper);
        helper.setBlock(new BlockPos(3, 1, 3), Objects.requireNonNull(Blocks.OAK_SLAB));
        helper.setBlock(new BlockPos(5, 1, 3), Objects.requireNonNull(Blocks.OAK_SLAB));
        helper.setBlock(new BlockPos(9, 1, 3), Objects.requireNonNull(Blocks.OAK_STAIRS.defaultBlockState().setValue(
                Objects.requireNonNull(net.minecraft.world.level.block.StairBlock.FACING), Objects.requireNonNull(net.minecraft.core.Direction.NORTH))));
        ColonyJob job = new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(scene.player().getUUID()), "SurfaceTest", ColonyJob.Kind.SWEEP, 5, 0);
        job.surfaceSweep = true; job.targets = Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(3, 0, 3)), helper.absolutePos(new BlockPos(5, 1, 3)), helper.absolutePos(new BlockPos(8, 0, 3)), helper.absolutePos(new BlockPos(9, 1, 3))));
        ItemEntity upperFloor = drop(helper, new BlockPos(3, 0, 3), 1.52, new ItemStack(Objects.requireNonNull(Items.STICK)));
        ItemEntity slabTop = drop(helper, new BlockPos(5, 1, 3), .52, new ItemStack(Objects.requireNonNull(Items.DIRT)));
        ItemEntity underneath = drop(helper, new BlockPos(5, 0, 3), .99, new ItemStack(Objects.requireNonNull(Items.DIAMOND)));
        ItemEntity gap = drop(helper, new BlockPos(6, 0, 3), 1.02, new ItemStack(Objects.requireNonNull(Items.IRON_INGOT)));
        ItemEntity tooHigh = drop(helper, new BlockPos(8, 0, 3), 2.1, new ItemStack(Objects.requireNonNull(Items.COAL)));
        ItemEntity lowerStep = drop(helper, new BlockPos(9, 1, 3), .52, new ItemStack(Objects.requireNonNull(Items.BRICK)));
        BlockPos stair = Objects.requireNonNull(helper.absolutePos(new BlockPos(9, 1, 3))); lowerStep.setPos(stair.getX() + .5, stair.getY() + .52, stair.getZ() + .8);
        var level = Objects.requireNonNull(helper.getLevel());
        helper.assertTrue(SweepService.contains(level, job, slabTop) && SweepService.contains(level, job, lowerStep) && !SweepService.contains(level, job, upperFloor)
                && !SweepService.contains(level, job, underneath) && !SweepService.contains(level, job, gap) && !SweepService.contains(level, job, tooHigh),
                "Top membership lost the lower stair surface or included an upper slab, a lower floor, a gap or a distant height");
        for (ItemEntity item : List.of(upperFloor, slabTop, underneath, gap, tooHigh, lowerStep)) item.discard(); scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "surface_membership")
    public static void aReservedDropLeavingTheSelectedTopCannotBePickedUp(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        ItemEntity item = drop(helper, new BlockPos(3, 0, 3), 1.02, new ItemStack(Objects.requireNonNull(Items.DIRT), 7));
        BlockPos beside = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 3))); scene.worker().moveTo(beside.getX() + .5, beside.getY(), beside.getZ() + .5);
        ColonyJob job = new ColonyJob(Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(scene.player().getUUID()), "SurfaceTest", ColonyJob.Kind.SWEEP, 5, 0);
        job.surfaceSweep = true; job.targets = Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(3, 0, 3))));
        ReservationLedger ledger = new ReservationLedger(); WorkTask task = Objects.requireNonNull(SweepService.assign(level, scene.worker(), job, ledger, level.getGameTime()));
        BlockPos outside = Objects.requireNonNull(helper.absolutePos(new BlockPos(5, 1, 3))); item.setPos(outside.getX() + .5, outside.getY() + .02, outside.getZ() + .5);
        task.actionProgress = dev.colony.camera.worker.WorkerEffects.haulTicks(scene.worker().state()) - 1;
        helper.assertTrue(SweepService.execute(level, scene.worker(), job, task, ledger, level.getGameTime()) && job.reason.equals("source_changed")
                && item.isAlive() && item.getItem().getCount() == 7 && scene.worker().getMainHandItem().isEmpty(), "Pickup did not recheck exact surface membership");
        ledger.release(Objects.requireNonNull(scene.worker().getUUID())); item.discard(); scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "surface_data")
    public static void exactFloorsRoundTripWhileOldCuboidsKeepTheirMeaning(GameTestHelper helper) {
        Scene scene = scene(helper); ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        List<BlockPos> selected = Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(3, 0, 3)), helper.absolutePos(new BlockPos(8, 0, 3))));
        manager.sweep(scene.player(), selected);
        manager.sweep(scene.player(), Objects.requireNonNull(helper.absolutePos(new BlockPos(0, 1, 0))), Objects.requireNonNull(helper.absolutePos(new BlockPos(11, 3, 11))));
        CompoundTag saved = manager.save(new CompoundTag()); ListTag own = new ListTag();
        for (Tag entry : saved.getList("jobs", Tag.TAG_COMPOUND)) {
            CompoundTag job = (CompoundTag) entry;
            if (job.getUUID("owner").equals(scene.player().getUUID())) own.add(Objects.requireNonNull(job.copy()));
        }
        saved.put("jobs", own); ColonyWorkManager restored = ColonyWorkManager.load(saved);
        var jobs = restored.jobs(Objects.requireNonNull(scene.player().getUUID()));
        helper.assertTrue(saved.getInt("schemaVersion") == 6 && !restored.readOnly() && jobs.get(0).surfaceSweep && jobs.get(0).targets.equals(selected)
                && !jobs.get(1).surfaceSweep, "Exact floor list or legacy sweep semantics changed on reload");
        CompoundTag legacy = Objects.requireNonNull(saved.copy()); legacy.putInt("schemaVersion", 4);
        ListTag onlyLegacy = new ListTag(); CompoundTag older = Objects.requireNonNull(saved.getList("jobs", Tag.TAG_COMPOUND).getCompound(1).copy()); older.remove("surfaceSweep"); onlyLegacy.add(older); legacy.put("jobs", onlyLegacy);
        ColonyWorkManager migrated = ColonyWorkManager.load(legacy);
        helper.assertTrue(!migrated.readOnly() && !migrated.all().get(0).surfaceSweep && migrated.save(new CompoundTag()).getInt("schemaVersion") == 6, "Schema four cuboid did not migrate safely");
        CompoundTag malformed = Objects.requireNonNull(saved.copy()); malformed.getList("jobs", Tag.TAG_COMPOUND).getCompound(0).remove("surfaceSweep");
        ColonyWorkManager rejected = ColonyWorkManager.load(malformed);
        helper.assertTrue(rejected.readOnly() && rejected.save(new CompoundTag()).equals(malformed), "An ambiguous schema five sweep was overwritten");
        for (ColonyJob job : manager.jobs(Objects.requireNonNull(scene.player().getUUID()))) manager.update(Objects.requireNonNull(scene.player().getUUID()), job.id, "cancel", 0);
        scene.worker().discard(); helper.succeed();
    }

    @Nonnull private static ItemEntity drop(GameTestHelper helper, BlockPos relative, double height, ItemStack stack) {
        BlockPos pos = Objects.requireNonNull(helper.absolutePos(relative));
        ItemEntity item = new ItemEntity(Objects.requireNonNull(helper.getLevel()), pos.getX() + .5, pos.getY() + height, pos.getZ() + .5, stack);
        item.setNoPickUpDelay(); item.setNoGravity(true); item.setDeltaMovement(Objects.requireNonNull(Vec3.ZERO)); helper.getLevel().addFreshEntity(item); return item;
    }
    @Nonnull private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE)); for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        var level = Objects.requireNonNull(helper.getLevel());
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "SurfaceSweepTest")));
        BlockPos pos = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 2))); player.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(level)); worker.owner(Objects.requireNonNull(player.getUUID())); worker.moveTo(player.getX(), player.getY(), player.getZ()); level.addFreshEntity(worker);
        helper.setBlock(new BlockPos(2, 1, 4), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        MachineBlockEntity warehouse = Objects.requireNonNull((MachineBlockEntity) helper.getBlockEntity(new BlockPos(2, 1, 4))); warehouse.owner(Objects.requireNonNull(player.getUUID())); return new Scene(player, worker, warehouse);
    }
}
