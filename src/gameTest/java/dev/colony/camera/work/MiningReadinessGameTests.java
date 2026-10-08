package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.ReservationLedger;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class MiningReadinessGameTests {
    private record Scene(@Nonnull FakePlayer player, @Nonnull WorkerEntity worker, @Nonnull ColonyWorkManager manager) {}

    @GameTest(template = "construction", timeoutTicks = 20, batch = "mining_readiness")
    public static void lostToolYieldsPartialTargetToReadyBlock(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        BlockPos stone = new BlockPos(3, 2, 3), dirt = new BlockPos(2, 1, 4);
        helper.setBlock(stone, Objects.requireNonNull(Blocks.STONE)); helper.setBlock(dirt, Objects.requireNonNull(Blocks.DIRT));
        scene.worker().setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        ColonyJob job = mine(helper, scene, Objects.requireNonNull(List.of(stone, dirt)));
        long time = level.getGameTime(); ReservationLedger ledger = new ReservationLedger();
        WorkTask first = Objects.requireNonNull(MiningService.assign(level, scene.worker(), job, Objects.requireNonNull(Set.of()), ledger, time));
        helper.assertTrue(job.targets.get(first.targetIndex).equals(helper.absolutePos(stone)), "Fixture did not start the exposed upper stone");
        scene.worker().workTask = first; MiningService.execute(level, scene.worker(), job, first, ledger, time);
        int saved = job.miningProgress().get(first.targetIndex);
        helper.assertTrue(saved > 0 && saved < 10000, "Fixture did not establish partial digging progress");
        MachineBlockEntity warehouse = warehouse(helper, scene, new BlockPos(6, 1, 6));
        warehouse.inventory.setStackInSlot(0, Objects.requireNonNull(scene.worker().getOffhandItem()));
        scene.worker().setItemSlot(EquipmentSlot.OFFHAND, Objects.requireNonNull(ItemStack.EMPTY)); ledger.clear();
        WorkTask next = Objects.requireNonNull(MiningService.assign(level, scene.worker(), job, Objects.requireNonNull(Set.of()), ledger, time + 1));
        helper.assertTrue(!next.toolPickup && job.targets.get(next.targetIndex).equals(helper.absolutePos(dirt)),
                "Missing tool retained an old block ahead of directly mineable dirt: " + job.reason);
        helper.assertTrue(job.miningProgress().get(first.targetIndex) == saved && !job.completed.get(first.targetIndex),
                "Yielding discarded partial progress or pretended stone was completed");
        scene.worker().discard(); helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 600, batch = "mining_readiness")
    public static void warehouseToolPickupWalksAroundWall(GameTestHelper helper) {
        Scene scene = scene(helper); MachineBlockEntity warehouse = walledWarehouse(helper, scene);
        ItemStack tool = namedPick(9); warehouse.inventory.setStackInSlot(0, tool);
        scene.worker().setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Objects.requireNonNull(Items.DIRT), 3));
        UUID cargo = Objects.requireNonNull(UUID.randomUUID()); scene.worker().cargoTask(cargo);
        BlockPos stone = new BlockPos(3, 1, 6); helper.setBlock(stone, Objects.requireNonNull(Blocks.STONE));
        ColonyJob job = mine(helper, scene, Objects.requireNonNull(List.of(stone)));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Worker never collected the tool behind a wall: " + job.reason + ", " + scene.worker().position());
            helper.assertTrue(warehouse.inventory.getStackInSlot(0).isEmpty() && scene.worker().getOffhandItem().is(Objects.requireNonNull(Items.WOODEN_PICKAXE))
                    && scene.worker().getOffhandItem().getDamageValue() == 10 && scene.worker().getOffhandItem().getHoverName().getString().equals("Warehouse pick"),
                    "Actual warehouse tool damage/name did not survive pickup and mining");
            helper.assertTrue(scene.worker().minedBlocks() == 1 && drops(helper, Objects.requireNonNull(Items.COBBLESTONE)) == 1
                    && scene.worker().getMainHandItem().getCount() == 3 && cargo.equals(scene.worker().cargoTask()), "Mining changed cargo or duplicated output");
            scene.worker().discard();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 700, batch = "mining_readiness")
    public static void brokenPickIsReplacedFromRealWarehouse(GameTestHelper helper) {
        Scene scene = scene(helper); MachineBlockEntity warehouse = walledWarehouse(helper, scene);
        warehouse.inventory.setStackInSlot(0, namedPick(5));
        ItemStack worn = new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)); worn.setDamageValue(worn.getMaxDamage() - 1);
        scene.worker().setItemSlot(EquipmentSlot.OFFHAND, worn);
        BlockPos first = new BlockPos(3, 1, 3), second = new BlockPos(3, 1, 6);
        helper.setBlock(first, Objects.requireNonNull(Blocks.STONE)); helper.setBlock(second, Objects.requireNonNull(Blocks.STONE));
        ColonyJob job = mine(helper, scene, Objects.requireNonNull(List.of(first, second))); boolean[] broke = {false};
        helper.onEachTick(() -> { if (job.done == 1 && scene.worker().getOffhandItem().isEmpty()) broke[0] = true; });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Broken tool left the volume stalled: " + job.reason);
            helper.assertTrue(broke[0] && warehouse.inventory.getStackInSlot(0).isEmpty()
                    && scene.worker().getOffhandItem().getDamageValue() == 6 && scene.worker().getOffhandItem().getHoverName().getString().equals("Warehouse pick"),
                    "Worker did not physically replace the broken pick");
            helper.assertTrue(scene.worker().minedBlocks() == 2 && drops(helper, Objects.requireNonNull(Items.COBBLESTONE)) == 2, "Tool replacement lost or repeated mining output");
            scene.worker().discard();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 320, batch = "mining_readiness")
    public static void refusedBreakYieldsThenRetriesWhenProtectionIsRemoved(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        BlockPos stone = new BlockPos(3, 1, 3), dirt = new BlockPos(2, 1, 5);
        helper.setBlock(stone, Objects.requireNonNull(Blocks.STONE)); helper.setBlock(dirt, Objects.requireNonNull(Blocks.DIRT));
        scene.worker().setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)));
        ColonyJob job = mine(helper, scene, Objects.requireNonNull(List.of(stone, dirt)));
        int index = job.targets.indexOf(helper.absolutePos(stone));
        WorkTask first = Objects.requireNonNull(MiningService.task(level, scene.worker(), job, index,
                Objects.requireNonNull(scene.worker().blockPosition()), null, new ReservationLedger(), level.getGameTime()));
        first.progress = 10000; scene.worker().workTask = first;
        AtomicBoolean protectedNow = new AtomicBoolean(true), registered = new AtomicBoolean(true); int[] refusals = {0};
        Consumer<BlockEvent.BreakEvent> protection = event -> {
            if (event.getPos().equals(helper.absolutePos(stone)) && protectedNow.get()) { event.setCanceled(true); refusals[0]++; }
        };
        Runnable cleanup = () -> { if (registered.compareAndSet(true, false)) MinecraftForge.EVENT_BUS.unregister(protection); };
        MinecraftForge.EVENT_BUS.addListener(protection);
        helper.onEachTick(() -> { if (helper.getBlockState(dirt).isAir()) protectedNow.set(false); });
        helper.runAtTickTime(180, () -> {
            try { helper.assertTrue(helper.getBlockState(dirt).isAir(), "Protected stone starved mineable dirt"); }
            finally { cleanup.run(); }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Protection retry never resumed: " + job.reason + ", refusals=" + refusals[0]);
            helper.assertTrue(refusals[0] > 0 && scene.worker().minedBlocks() == 2 && scene.worker().getOffhandItem().getDamageValue() == 1
                    && drops(helper, Objects.requireNonNull(Items.COBBLESTONE)) == 1 && drops(helper, Objects.requireNonNull(Items.DIRT)) == 1, "Cancelled break consumed tool/XP or retry duplicated output");
            cleanup.run(); scene.worker().discard();
        });
    }

    @GameTest(template = "construction", timeoutTicks = 500, batch = "mining_readiness")
    public static void readyDirtContinuesBeforeLaterGroundToolBecomesAvailable(GameTestHelper helper) {
        Scene scene = scene(helper); var level = Objects.requireNonNull(helper.getLevel());
        BlockPos stone = new BlockPos(3, 2, 3), dirt = new BlockPos(2, 1, 5);
        helper.setBlock(stone, Objects.requireNonNull(Blocks.STONE)); helper.setBlock(dirt, Objects.requireNonNull(Blocks.DIRT));
        ColonyJob job = mine(helper, scene, Objects.requireNonNull(List.of(stone, dirt))); boolean[] supplied = {false};
        helper.onEachTick(() -> {
            if (supplied[0] || !helper.getBlockState(dirt).isAir()) return;
            helper.assertTrue(helper.getBlockState(stone).is(Objects.requireNonNull(Blocks.STONE)), "Stone was mined without a tool");
            supplied[0] = true; BlockPos point = Objects.requireNonNull(helper.absolutePos(new BlockPos(6, 1, 6)));
            ItemEntity tool = new ItemEntity(level, point.getX() + 0.5, point.getY(), point.getZ() + 0.5, namedPick(3));
            tool.setNoPickUpDelay(); level.addFreshEntity(tool);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished() && supplied[0], "Worker ignored a newly available ground tool: " + job.reason);
            helper.assertTrue(scene.worker().getOffhandItem().getDamageValue() == 4 && scene.worker().getOffhandItem().getHoverName().getString().equals("Warehouse pick")
                    && scene.worker().minedBlocks() == 2 && drops(helper, Objects.requireNonNull(Items.COBBLESTONE)) == 1
                    && drops(helper, Objects.requireNonNull(Items.WOODEN_PICKAXE)) == 0,
                    "Ground pickup bypassed real item conservation");
            scene.worker().discard();
        });
    }

    @Nonnull private static ItemStack namedPick(int damage) {
        ItemStack tool = new ItemStack(Objects.requireNonNull(Items.WOODEN_PICKAXE)); tool.setDamageValue(damage);
        tool.setHoverName(Objects.requireNonNull(Component.literal("Warehouse pick"))); return tool;
    }
    @Nonnull private static MachineBlockEntity walledWarehouse(GameTestHelper helper, Scene scene) {
        for (int y = 1; y <= 2; y++) for (int z = 2; z <= 4; z++) helper.setBlock(new BlockPos(5, y, z), Objects.requireNonNull(Blocks.STONE));
        return warehouse(helper, scene, new BlockPos(6, 1, 3));
    }
    @Nonnull private static MachineBlockEntity warehouse(GameTestHelper helper, Scene scene, BlockPos pos) {
        helper.setBlock(pos, Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        MachineBlockEntity warehouse = (MachineBlockEntity) Objects.requireNonNull(helper.getBlockEntity(pos));
        warehouse.owner(Objects.requireNonNull(scene.player().getUUID())); return warehouse;
    }
    @Nonnull private static ColonyJob mine(GameTestHelper helper, Scene scene, List<BlockPos> positions) {
        scene.manager().mine(scene.player(), Objects.requireNonNull(positions.stream().map(pos -> helper.absolutePos(Objects.requireNonNull(pos))).toList()));
        return Objects.requireNonNull(scene.manager().jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
    }
    private static int drops(GameTestHelper helper, Item item) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds(helper)).stream()
                .filter(entity -> Objects.requireNonNull(entity).getItem().is(item)).mapToInt(entity -> Objects.requireNonNull(entity).getItem().getCount()).sum();
    }
    @Nonnull private static AABB bounds(GameTestHelper helper) {
        return new AABB(Objects.requireNonNull(helper.absolutePos(new BlockPos(0, 0, 0))), Objects.requireNonNull(helper.absolutePos(new BlockPos(12, 7, 12))));
    }
    @Nonnull private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE));
            for (int y = 1; y < 7; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        var level = Objects.requireNonNull(helper.getLevel());
        level.getEntitiesOfClass(WorkerEntity.class, bounds(helper)).forEach(entity -> Objects.requireNonNull(entity).discard());
        level.getEntitiesOfClass(ItemEntity.class, bounds(helper)).forEach(entity -> Objects.requireNonNull(entity).discard());
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(Objects.requireNonNull(UUID.randomUUID()), "MiningReadiness")));
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 3))); player.setPos(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(level)); worker.owner(Objects.requireNonNull(player.getUUID()));
        worker.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0); worker.setOnGround(true); level.addFreshEntity(worker);
        return new Scene(player, worker, ColonyWorkManager.get(level));
    }
}
