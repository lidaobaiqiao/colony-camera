package dev.colony.camera.crafting;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.StorageResources;
import dev.colony.camera.work.ColonyJob;
import dev.colony.camera.work.ColonyWorkManager;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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
public final class RecipeBookGameTests {
    private record Scene(@Nonnull FakePlayer player, @Nonnull WorkerEntity worker, @Nonnull MachineBlockEntity box, @Nonnull BlockPos station) {}
    @GameTest(template = "construction", timeoutTicks = 500, batch = "recipe_book")
    public static void logsBecomePlanksThenSticksThroughTheActualWorkerQueue(GameTestHelper helper) {
        Scene scene = scene(helper); scene.box().inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_LOG), 2));
        var manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        manager.craft(scene.player(), scene.station(), "minecraft:oak_planks", 2); manager.craft(scene.player(), scene.station(), "minecraft:stick", 1);
        helper.succeedWhen(() -> {
            helper.assertTrue(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).stream().allMatch(job -> Objects.requireNonNull(job).finished()), "Basic native recipes stalled");
            helper.assertTrue(count(scene, Objects.requireNonNull(Items.OAK_LOG)) == 0 && count(scene, Objects.requireNonNull(Items.OAK_PLANKS)) == 6
                    && count(scene, Objects.requireNonNull(Items.STICK)) == 4, "Native recipe batch output or material conservation failed");
            scene.worker().discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 600, batch = "recipe_remainders")
    public static void nativeFoodAndRemainingBucketsAreProducedWithoutLosingIngredients(GameTestHelper helper) {
        Scene scene = scene(helper);
        for (int slot = 0; slot < 6; slot++) scene.box().inventory.setStackInSlot(slot, new ItemStack(Objects.requireNonNull(Items.MILK_BUCKET)));
        scene.box().inventory.setStackInSlot(6, new ItemStack(Objects.requireNonNull(Items.SUGAR), 4));
        scene.box().inventory.setStackInSlot(7, new ItemStack(Objects.requireNonNull(Items.EGG), 2));
        scene.box().inventory.setStackInSlot(8, new ItemStack(Objects.requireNonNull(Items.WHEAT), 9));
        BlockPos stand = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 1, 5))); scene.worker().moveTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5);
        var preview = CraftingService.previewPage(scene.player(), scene.station(), Objects.requireNonNull(List.of("minecraft:cake")), 2, 1);
        helper.assertTrue(preview.getAsJsonArray("states").get(0).getAsJsonObject().get("reason").getAsString().equals("ready"), "Native remainder recipe could not be previewed in batches");
        var manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        manager.craft(scene.player(), scene.station(), "minecraft:bread", 1); manager.craft(scene.player(), scene.station(), "minecraft:cake", 2);
        helper.succeedWhen(() -> {
            helper.assertTrue(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).stream().allMatch(job -> Objects.requireNonNull(job).finished()), "Food or remainder recipe stalled");
            helper.assertTrue(count(scene, Objects.requireNonNull(Items.BREAD)) == 1 && count(scene, Objects.requireNonNull(Items.CAKE)) == 2
                    && count(scene, Objects.requireNonNull(Items.BUCKET)) == 6 && count(scene, Objects.requireNonNull(Items.MILK_BUCKET)) == 0
                    && count(scene, Objects.requireNonNull(Items.WHEAT)) == 0 && count(scene, Objects.requireNonNull(Items.SUGAR)) == 0
                    && count(scene, Objects.requireNonNull(Items.EGG)) == 0, "Native food production lost or duplicated output, buckets or ingredients");
            scene.worker().discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "recipe_preview")
    public static void recipePageChecksBatchQuantityWithoutChangingStockOrReservations(GameTestHelper helper) {
        Scene scene = scene(helper);
        ItemStack named = new ItemStack(Objects.requireNonNull(Items.OAK_LOG)); named.setHoverName(Objects.requireNonNull(net.minecraft.network.chat.Component.literal("Named raw material")));
        scene.box().inventory.setStackInSlot(0, named);
        BlockPos stand = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 1, 5))); scene.worker().moveTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5);
        CompoundTag before = Objects.requireNonNull(scene.box().inventory.save());
        var one = CraftingService.previewPage(scene.player(), scene.station(), Objects.requireNonNull(List.of("minecraft:oak_planks", "minecraft:bread", "minecraft:iron_pickaxe")), 1, 1);
        var two = CraftingService.previewPage(scene.player(), scene.station(), Objects.requireNonNull(List.of("minecraft:oak_planks")), 2, 2);
        helper.assertTrue(one.getAsJsonArray("states").get(0).getAsJsonObject().get("reason").getAsString().equals("ready")
                && two.getAsJsonArray("states").get(0).getAsJsonObject().get("reason").getAsString().equals("materials"), "Batch availability counted the same log twice or rejected valid NBT ingredients");
        helper.assertTrue(one.getAsJsonArray("states").get(1).getAsJsonObject().get("reason").getAsString().equals("materials")
                && one.getAsJsonArray("states").get(2).getAsJsonObject().get("reason").getAsString().equals("technology"), "Recipe page did not distinguish missing ingredients from locked research");
        helper.assertTrue(before.equals(scene.box().inventory.save()) && dev.colony.camera.construction.ConstructionManager.get(Objects.requireNonNull(helper.getLevel())).ledger().claim(Objects.requireNonNull(scene.worker().getUUID())) == null
                && ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel())).jobs(Objects.requireNonNull(scene.player().getUUID())).isEmpty(), "Browsing recipes moved stock or created work claims");
        scene.worker().discard(); helper.succeed();
    }
    @GameTest(template = "construction", timeoutTicks = 400, batch = "recipe_corner")
    public static void nearTheWorkStandButBehindACornerMustKeepWalkingBeforeCrafting(GameTestHelper helper) {
        Scene scene = scene(helper);
        helper.setBlock(new BlockPos(4, 1, 4), Objects.requireNonNull(Blocks.STONE)); helper.setBlock(new BlockPos(4, 2, 4), Objects.requireNonNull(Blocks.STONE));
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(3, 1, 4)));
        scene.worker().moveTo(start.getX() + .69999, start.getY(), start.getZ() + .87);
        scene.box().inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_PLANKS), 3)); scene.box().inventory.setStackInSlot(1, new ItemStack(Objects.requireNonNull(Items.STICK), 2));
        BlockPos stand = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 1, 5)));
        helper.assertTrue(scene.worker().distanceToSqr(dev.colony.camera.resources.GroundResources.bottom(stand)) < 1.1
                && !StorageResources.canTake(Objects.requireNonNull(helper.getLevel()), scene.worker(), scene.station())
                && helper.getLevel().noCollision(scene.worker(), Objects.requireNonNull(scene.worker().getBoundingBox())), "Corner fixture did not reproduce premature arrival without embedding the worker");
        var manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel())); manager.craft(scene.player(), scene.station(), "minecraft:wooden_pickaxe", 1);
        ColonyJob job = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(scene.player().getUUID())).get(0));
        helper.succeedWhen(() -> {
            helper.assertTrue(job.finished(), "Worker stopped before leaving the corner: " + job.reason + " at " + scene.worker().position());
            helper.assertTrue(count(scene, Objects.requireNonNull(Items.WOODEN_PICKAXE)) == 1 && count(scene, Objects.requireNonNull(Items.OAK_PLANKS)) == 0
                    && count(scene, Objects.requireNonNull(Items.STICK)) == 0, "Corner recovery duplicated output or lost materials"); scene.worker().discard();
        });
    }
    @GameTest(template = "construction", timeoutTicks = 30, batch = "recipe_preview")
    public static void previewRespectsConstructionReservationsAndOutputSpace(GameTestHelper helper) {
        Scene scene = scene(helper); scene.box().inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_LOG)));
        BlockPos stand = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 1, 5))); scene.worker().moveTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5);
        var ledger = dev.colony.camera.construction.ConstructionManager.get(Objects.requireNonNull(helper.getLevel())).ledger();
        var source = Objects.requireNonNull(StorageResources.resolve(Objects.requireNonNull(helper.getLevel()), Objects.requireNonNull(scene.box().getBlockPos()), Objects.requireNonNull(scene.player().getUUID()), Objects.requireNonNull(Items.OAK_LOG)));
        UUID claimant = Objects.requireNonNull(UUID.randomUUID());
        ledger.reserve(claimant, Objects.requireNonNull(UUID.randomUUID()), source.identity(), 1, 1);
        var reserved = CraftingService.previewPage(scene.player(), scene.station(), Objects.requireNonNull(List.of("minecraft:oak_planks")), 1, 1);
        helper.assertTrue(!reserved.getAsJsonArray("states").get(0).getAsJsonObject().get("reason").getAsString().equals("ready"), "Recipe preview stole reserved construction material"); ledger.release(claimant);
        for (int slot = 1; slot < scene.box().inventory.getSlots(); slot++) scene.box().inventory.setStackInSlot(slot, new ItemStack(Objects.requireNonNull(Items.DIRT), 64));
        scene.box().inventory.setStackInSlot(0, new ItemStack(Objects.requireNonNull(Items.OAK_LOG), 2));
        var full = CraftingService.previewPage(scene.player(), scene.station(), Objects.requireNonNull(List.of("minecraft:oak_planks")), 1, 2);
        helper.assertTrue(full.getAsJsonArray("states").get(0).getAsJsonObject().get("reason").getAsString().equals("full") && count(scene, Objects.requireNonNull(Items.OAK_LOG)) == 2,
                "Recipe preview ignored output space or consumed stock"); scene.worker().discard(); helper.succeed();
    }
    private static int count(Scene scene, net.minecraft.world.item.Item item) {
        int total = 0; for (int slot = 0; slot < scene.box().inventory.getSlots(); slot++) { var stack = scene.box().inventory.getStackInSlot(slot); if (stack.is(item)) total += stack.getCount(); } return total;
    }
    @Nonnull private static Scene scene(GameTestHelper helper) {
        for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Objects.requireNonNull(Blocks.STONE)); for (int y = 1; y < 6; y++) helper.setBlock(new BlockPos(x, y, z), Objects.requireNonNull(Blocks.AIR));
        }
        var level = Objects.requireNonNull(helper.getLevel());
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "RecipeBookTest")));
        BlockPos start = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 2))); player.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5);
        WorkerEntity worker = Objects.requireNonNull(ColonyEntities.WORKER.get().create(level)); worker.owner(Objects.requireNonNull(player.getUUID()));
        worker.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5); level.addFreshEntity(worker);
        helper.setBlock(new BlockPos(5, 1, 5), Objects.requireNonNull(ColonyContent.COLONY_WORKBENCH.get()));
        helper.setBlock(new BlockPos(5, 1, 6), Objects.requireNonNull(ColonyContent.WAREHOUSE.get()));
        var workbench = Objects.requireNonNull((MachineBlockEntity) helper.getBlockEntity(new BlockPos(5, 1, 5))); workbench.owner(Objects.requireNonNull(player.getUUID()));
        var box = Objects.requireNonNull((MachineBlockEntity) helper.getBlockEntity(new BlockPos(5, 1, 6))); box.owner(Objects.requireNonNull(player.getUUID()));
        return new Scene(player, worker, box, Objects.requireNonNull(helper.absolutePos(new BlockPos(5, 1, 5))));
    }
}
