package dev.colony.camera.work;

import com.mojang.authlib.GameProfile;
import dev.colony.camera.ColonyCamera;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.network.NetworkDirection;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@GameTestHolder(ColonyCamera.ID)
@PrefixGameTestTemplate(false)
@ParametersAreNonnullByDefault
public final class WorkMarkGameTests {
    @GameTest(template = "construction", timeoutTicks = 20, batch = "work_marks")
    public static void marksKeepPausedIntentAndDropCompletedOrCancelledTargets(GameTestHelper helper) {
        FakePlayer player = player(helper); var manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel()));
        helper.setBlock(new BlockPos(3, 1, 3), Objects.requireNonNull(Blocks.STONE)); helper.setBlock(new BlockPos(4, 1, 3), Objects.requireNonNull(Blocks.STONE));
        BlockPos first = Objects.requireNonNull(helper.absolutePos(new BlockPos(3, 1, 3))), second = Objects.requireNonNull(helper.absolutePos(new BlockPos(4, 1, 3)));
        manager.mine(player, Objects.requireNonNull(List.of(first, second))); ColonyJob mine = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(player.getUUID())).get(0));
        mine.paused = true; mine.completed.set(0); mine.done = 1;
        helper.setBlock(new BlockPos(3, 0, 5), Objects.requireNonNull(Blocks.STONE)); helper.setBlock(new BlockPos(8, 0, 5), Objects.requireNonNull(Blocks.STONE));
        BlockPos floor = Objects.requireNonNull(helper.absolutePos(new BlockPos(3, 0, 5))), disconnected = Objects.requireNonNull(helper.absolutePos(new BlockPos(8, 0, 5)));
        manager.sweep(player, Objects.requireNonNull(List.of(floor, disconnected))); ColonyJob sweep = Objects.requireNonNull(manager.jobs(Objects.requireNonNull(player.getUUID())).get(1)); sweep.paused = true;
        var initial = WorkTargetMarks.pages(player, 7, Long.MIN_VALUE);
        helper.assertTrue(initial.size() == 1 && Arrays.equals(initial.get(0).mining(), new long[]{second.asLong()})
                && Arrays.equals(initial.get(0).sweeping(), new long[]{floor.asLong(), disconnected.asLong()}), "Paused designation disappeared or completed mining remained marked");
        var unchanged = WorkTargetMarks.pages(player, 8, initial.get(0).signature());
        helper.assertTrue(unchanged.size() == 1 && unchanged.get(0).pages() == 0 && unchanged.get(0).mining().length == 0 && unchanged.get(0).sweeping().length == 0, "Unchanged marks retransmitted all targets");
        manager.update(Objects.requireNonNull(player.getUUID()), mine.id, "cancel", 0); manager.update(Objects.requireNonNull(player.getUUID()), sweep.id, "cancel", 0);
        var cancelled = WorkTargetMarks.pages(player, 9, initial.get(0).signature());
        helper.assertTrue(cancelled.size() == 1 && cancelled.get(0).signature() != initial.get(0).signature()
                && cancelled.get(0).mining().length == 0 && cancelled.get(0).sweeping().length == 0, "Cancelled work retained its terrain marks");
        helper.succeed();
    }

    @GameTest(template = "construction", timeoutTicks = 30, batch = "large_work_marks")
    public static void thousandsOfDesignationsFitBoundedActualClientPackets(GameTestHelper helper) {
        FakePlayer player = player(helper); var manager = ColonyWorkManager.get(Objects.requireNonNull(helper.getLevel())); List<BlockPos> targets = new ArrayList<>();
        for (int x = 0; x < 12; x++) for (int z = 0; z < 8; z++) for (int y = 1; y <= 32; y++) {
            BlockPos relative = new BlockPos(x, y, z); helper.setBlock(relative, Objects.requireNonNull(Blocks.STONE)); targets.add(Objects.requireNonNull(helper.absolutePos(relative)));
        }
        manager.mine(player, targets); manager.sweep(player, targets);
        for (ColonyJob job : manager.jobs(Objects.requireNonNull(player.getUUID()))) job.paused = true;
        var pages = WorkTargetMarks.pages(player, 17, Long.MIN_VALUE); int mines = 0, floors = 0;
        helper.assertTrue(pages.size() == 3, "Large designation snapshot did not split into bounded pages");
        for (int index = 0; index < pages.size(); index++) {
            var page = Objects.requireNonNull(pages.get(index));
            helper.assertTrue(page.index() == index && page.pages() == pages.size() && page.mining().length + page.sweeping().length + page.legacy().length <= 2048, "Page exceeds coordinate limit or lost ordering");
            var packet = (ClientboundCustomPayloadPacket) WorkNetwork.CHANNEL.toVanillaPacket(page, NetworkDirection.PLAY_TO_CLIENT);
            var payload = Objects.requireNonNull(packet.getData());
            try {
                helper.assertTrue(payload.readableBytes() < 32767 && payload.readVarInt() == 5, "Mark packet is oversized or has the wrong message ID");
                helper.assertTrue(payload.readUtf(128).equals(page.dimension()) && payload.readInt() == 17 && payload.readLong() == page.signature()
                        && payload.readVarInt() == index && payload.readVarInt() == pages.size(), "Mark packet header changed");
                for (long[] positions : List.of(page.mining(), page.sweeping(), page.legacy())) {
                    helper.assertTrue(payload.readVarInt() == positions.length, "Mark position count changed");
                    for (long position : positions) helper.assertTrue(payload.readLong() == position, "Mark coordinate changed in transit");
                }
                helper.assertTrue(!payload.isReadable(), "Trailing bytes in a designation page");
            } finally { payload.release(); }
            mines += page.mining().length; floors += page.sweeping().length;
        }
        helper.assertTrue(mines == targets.size() && floors == targets.size(), "Pagination lost a mining or sweep coordinate");
        for (ColonyJob job : manager.jobs(Objects.requireNonNull(player.getUUID()))) manager.update(Objects.requireNonNull(player.getUUID()), job.id, "cancel", 0);
        helper.succeed();
    }

    @Nonnull private static FakePlayer player(GameTestHelper helper) {
        FakePlayer player = Objects.requireNonNull(FakePlayerFactory.get(Objects.requireNonNull(helper.getLevel()), new GameProfile(UUID.randomUUID(), "WorkMarkTest")));
        BlockPos pos = Objects.requireNonNull(helper.absolutePos(new BlockPos(2, 1, 2))); player.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5); return player;
    }
}
