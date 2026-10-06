package dev.colony.camera.worker;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.work.ColonyJob;
import dev.colony.camera.work.ColonyWorkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.ForgeMod;
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
public final class WorkerWaterGameTests {
    @GameTest(template = "construction", timeoutTicks = 250, batch = "water_navigation")
    public static void upstreamRouteOvercomesRealFlow(GameTestHelper helper) {
        channel(helper, false);
        WorkerEntity worker = worker(helper, Objects.requireNonNull(UUID.randomUUID()));
        Objects.requireNonNull(worker.getAttribute(Objects.requireNonNull(ForgeMod.SWIM_SPEED.get()))).setBaseValue(1);
        BlockPos target = Objects.requireNonNull(helper.absolutePos(new BlockPos(3, 1, 5)));
        double startX = worker.getX();
        boolean[] started = {false};
        boolean[] boosted = {false};
        helper.runAfterDelay(10, () -> {
            BlockPos flow = Objects.requireNonNull(helper.absolutePos(new BlockPos(6, 1, 5)));
            helper.assertTrue(helper.getLevel().getFluidState(flow).getFlow(Objects.requireNonNull(helper.getLevel()), flow).x > 0.5,
                    "Fixture has no opposing current");
            var path = GroundResources.path(Objects.requireNonNull(helper.getLevel()), worker, target);
            helper.assertTrue(path != null && GroundResources.follow(Objects.requireNonNull(helper.getLevel()), worker, Objects.requireNonNull(path)),
                    "Shallow upstream route was rejected");
            started[0] = true;
        });
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(worker.getX() >= startX - 1, "Unmodified swim thrust already overcame the fixture current");
            Objects.requireNonNull(worker.getAttribute(Objects.requireNonNull(ForgeMod.SWIM_SPEED.get()))).setBaseValue(5);
            boosted[0] = true;
        });
        // This is a movement test without a colony job; the dispatcher stops idle workers periodically.
        helper.onEachTick(() -> {
            if (started[0] && worker.getNavigation().isDone()) {
                var path = GroundResources.path(Objects.requireNonNull(helper.getLevel()), worker, target);
                if (path != null) GroundResources.follow(Objects.requireNonNull(helper.getLevel()), worker, path);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(boosted[0] && worker.getX() < startX - 4 && worker.distanceToSqr(GroundResources.bottom(target)) < 1.2,
                    "Worker could not walk upstream: " + worker.position() + ", start=" + startX + ", target=" + target
                    + ", water=" + worker.isInWater() + ", swim=" + worker.getAttributeValue(Objects.requireNonNull(ForgeMod.SWIM_SPEED.get()))
                    + ", ground=" + worker.onGround() + ", speed=" + worker.getSpeed() + ", delta=" + worker.getDeltaMovement()
                    + ", route=" + worker.getNavigation().getPath());
            helper.assertTrue(worker.getAttributeValue(Objects.requireNonNull(Attributes.MOVEMENT_SPEED)) == 0.3,
                    "Water fix changed land speed");
            helper.assertTrue(worker.getFluidMotionScale(Objects.requireNonNull(ForgeMod.WATER_TYPE.get())) > 0,
                    "Walking worker became immune to fluid push");
            worker.discard(); helper.succeed();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 250, batch = "water_exit")
    public static void upstreamWorkerClimbsOutOntoADryStep(GameTestHelper helper) {
        channel(helper, false);
        helper.setBlock(new BlockPos(1, 2, 5), Objects.requireNonNull(Blocks.AIR));
        helper.setBlock(new BlockPos(1, 3, 5), Objects.requireNonNull(Blocks.AIR));
        WorkerEntity worker = worker(helper, Objects.requireNonNull(UUID.randomUUID()));
        BlockPos target = Objects.requireNonNull(helper.absolutePos(new BlockPos(1, 2, 5)));
        helper.onEachTick(() -> {
            if (worker.getNavigation().isDone()) {
                var path = GroundResources.path(Objects.requireNonNull(helper.getLevel()), worker, target);
                if (path != null) GroundResources.follow(Objects.requireNonNull(helper.getLevel()), worker, path);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(worker.onGround() && !worker.isInWater() && worker.distanceToSqr(GroundResources.bottom(target)) < 1.2,
                    "Worker could not leave flowing water over a step: " + worker.position());
            worker.discard(); helper.succeed();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 450, batch = "water_mining")
    public static void miningOpensAStreamAndContinuesUpstreamOnItsBed(GameTestHelper helper) {
        channel(helper, true);
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "WaterMiner")));
        WorkerEntity worker = worker(helper, Objects.requireNonNull(player.getUUID()));
        player.setPos(worker.getX(), worker.getY(), worker.getZ());
        worker.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        manager.mine(player, Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(4, 1, 5)))));
        ColonyJob dam = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(player.getUUID())).get(0));
        ColonyJob[] floorJob = {null};
        boolean[] waded = {false};
        helper.onEachTick(() -> {
            BlockPos flow = Objects.requireNonNull(helper.absolutePos(new BlockPos(6, 1, 5)));
            if (dam.finished() && floorJob[0] == null && !helper.getLevel().getFluidState(flow).isEmpty()) {
                helper.assertTrue(helper.getLevel().getFluidState(flow).getFlow(Objects.requireNonNull(helper.getLevel()), flow).x > 0.5, "Opened dam has no current");
                manager.mine(player, Objects.requireNonNull(List.of(helper.absolutePos(new BlockPos(3, 0, 5)))));
                floorJob[0] = manager.jobs(Objects.requireNonNull(player.getUUID())).stream().filter(job -> !job.id.equals(dam.id)).findFirst().orElseThrow();
            }
            if (worker.workTask != null && worker.isInWater()) waded[0] = true;
        });
        helper.succeedWhen(() -> {
            ColonyJob job = floorJob[0];
            helper.assertTrue(job != null, "Waiting for water to spread after mining the dam");
            job = Objects.requireNonNull(job);
            var navigation = worker.getNavigation();
            var route = navigation.getPath();
            String routeDetails = route == null ? "none" : route.getNextNodeIndex() + "/" + route.getNodeCount()
                    + ", next=" + (route.isDone() ? "done" : route.getNextNodePos())
                    + ", first=" + route.getNode(0).asBlockPos();
            helper.assertTrue(job.finished(), "Mining stalled after opening water: " + job.reason + ", " + worker.position()
                    + ", water=" + worker.isInWater() + ", target=" + worker.getNavigation().getTargetPos()
                    + ", route=" + routeDetails + ", delta=" + worker.getDeltaMovement()
                    + ", wanted=" + worker.getMoveControl().getWantedX() + "/" + worker.getMoveControl().getWantedY()
                    + "/" + worker.getMoveControl().getWantedZ());
            helper.assertTrue(waded[0] && worker.getX() < helper.absolutePos(new BlockPos(6, 1, 5)).getX(),
                    "Worker bypassed the flowing-water route: waded=" + waded[0] + ", pos=" + worker.position());
            helper.assertTrue(worker.miningXp() == 2 && worker.getOffhandItem().getDamageValue() == 2,
                    "Flooded mining lost XP or durability callbacks");
            AABB bounds = new AABB(Objects.requireNonNull(helper.absolutePos(new BlockPos(0, -2, 0))),
                    Objects.requireNonNull(helper.absolutePos(new BlockPos(12, 6, 12))));
            int cobble = helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds).stream()
                    .filter(item -> item.getItem().is(Objects.requireNonNull(Items.COBBLESTONE)))
                    .mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(cobble == 2, "Stream mining lost or duplicated its normal block drops");
            worker.discard(); helper.succeed();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "water_safety")
    public static void workStandsRejectDeepWaterLavaAndMissingFooting(GameTestHelper helper) {
        channel(helper, false);
        BlockPos pos = Objects.requireNonNull(helper.absolutePos(new BlockPos(5, 1, 5)));
        helper.assertTrue(ConstructionManager.workStandable(Objects.requireNonNull(helper.getLevel()), pos)
                && !ConstructionManager.standable(Objects.requireNonNull(helper.getLevel()), pos), "Shallow work/spawn rules were mixed");
        helper.setBlock(new BlockPos(5, 2, 5), Objects.requireNonNull(Blocks.WATER));
        helper.assertTrue(!ConstructionManager.workStandable(Objects.requireNonNull(helper.getLevel()), pos), "Underwater head space accepted");
        helper.setBlock(new BlockPos(5, 2, 5), Objects.requireNonNull(Blocks.AIR));
        helper.setBlock(new BlockPos(5, 1, 5), Objects.requireNonNull(Blocks.LAVA));
        helper.assertTrue(!ConstructionManager.workStandable(Objects.requireNonNull(helper.getLevel()), pos), "Lava accepted as water");
        helper.setBlock(new BlockPos(5, 1, 5), Objects.requireNonNull(Blocks.WATER));
        helper.setBlock(new BlockPos(5, 0, 5), Objects.requireNonNull(Blocks.AIR));
        helper.assertTrue(!ConstructionManager.workStandable(Objects.requireNonNull(helper.getLevel()), pos), "Unsupported work stand accepted");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 20, batch = "water_save")
    public static void oldWorkerSwimAttributeMigratesWithoutChangingItsInventory(GameTestHelper helper) {
        channel(helper, false);
        UUID owner = Objects.requireNonNull(UUID.randomUUID());
        WorkerEntity worker = worker(helper, owner);
        worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Objects.requireNonNull(Items.DIRT), 7));
        worker.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        worker.gainMiningExperience(18);
        Objects.requireNonNull(worker.getAttribute(Objects.requireNonNull(ForgeMod.SWIM_SPEED.get()))).setBaseValue(1);
        CompoundTag saved = new CompoundTag(); worker.addAdditionalSaveData(saved);
        var attributes = saved.getList("Attributes", Tag.TAG_COMPOUND);
        boolean changed = false;
        for (int i = 0; i < attributes.size(); i++) {
            CompoundTag attribute = attributes.getCompound(i);
            if (attribute.getString("Name").equals("forge:swim_speed")) { attribute.putDouble("Base", 1); changed = true; }
        }
        helper.assertTrue(changed, "Old attribute fixture was not serialized");
        worker.readAdditionalSaveData(saved);
        helper.assertTrue(worker.getAttributeValue(Objects.requireNonNull(ForgeMod.SWIM_SPEED.get())) == 5
                && worker.getAttributeValue(Objects.requireNonNull(Attributes.MOVEMENT_SPEED)) == 0.3, "Saved bases defeated the water fix");
        helper.assertTrue(owner.equals(worker.owner()) && worker.miningXp() == 18 && worker.getMainHandItem().getCount() == 7
                && worker.getOffhandItem().is(Objects.requireNonNull(Items.WOODEN_PICKAXE)), "Migration lost owner, cargo, tool or skill");
        worker.discard(); helper.succeed();
    }

    private static void channel(GameTestHelper helper, boolean dam) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        for (int x = 1; x <= 10; x++) for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(x, y, 4), Objects.requireNonNull(Blocks.STONE));
            helper.setBlock(new BlockPos(x, y, 6), Objects.requireNonNull(Blocks.STONE));
        }
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(1, y, 5), Objects.requireNonNull(Blocks.STONE));
            helper.setBlock(new BlockPos(10, y, 5), Objects.requireNonNull(Blocks.STONE));
        }
        helper.setBlock(new BlockPos(2, 1, 5), Objects.requireNonNull(Blocks.WATER));
        for (int x = 3; x <= (dam ? 3 : 9); x++) helper.setBlock(new BlockPos(x, 1, 5),
                Objects.requireNonNull(Fluids.FLOWING_WATER.getFlowing(10 - x, false).createLegacyBlock()));
        if (dam) helper.setBlock(new BlockPos(4, 1, 5), Objects.requireNonNull(Blocks.STONE));
    }

    @Nonnull private static WorkerEntity worker(GameTestHelper helper, UUID owner) {
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(Objects.requireNonNull(helper.getLevel())));
        worker.owner(owner);
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(8, 1, 5)));
        worker.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        helper.getLevel().addFreshEntity(worker); return worker;
    }
}
