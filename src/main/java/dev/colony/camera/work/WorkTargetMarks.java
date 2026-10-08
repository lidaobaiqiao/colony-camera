package dev.colony.camera.work;

import dev.colony.camera.resources.GroundResources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Separate bounded pages keep terrain designations out of the regular people/task snapshot. */
@ParametersAreNonnullByDefault
public final class WorkTargetMarks {
    private WorkTargetMarks() {}
    public static void send(ServerPlayer player, int request, long known) {
        for (WorkNetwork.MarkPage page : pages(player, request, known)) send(player, Objects.requireNonNull(page));
    }
    @Nonnull public static List<WorkNetwork.MarkPage> pages(ServerPlayer player, int request, long known) {
        var level = Objects.requireNonNull(player.serverLevel());
        var mining = new LinkedHashSet<Long>(); var sweeping = new LinkedHashSet<Long>(); var legacy = new ArrayList<Long>();
        for (ColonyJob job : ColonyWorkManager.get(level).jobs(Objects.requireNonNull(player.getUUID()))) {
            if (job.cancelled || job.finished()) continue;
            if (job.kind == ColonyJob.Kind.MINE) {
                for (int index = 0; index < job.targets.size(); index++) {
                    BlockPos pos = Objects.requireNonNull(job.targets.get(index));
                    if (!job.completed.get(index) && GroundResources.loaded(level, pos)) mining.add(pos.asLong());
                }
            } else if (job.kind == ColonyJob.Kind.SWEEP) {
                if (job.surfaceSweep) {
                    for (BlockPos pos : job.targets) if (GroundResources.loaded(level, Objects.requireNonNull(pos))) sweeping.add(pos.asLong());
                } else if (!job.itemSweep()) { legacy.add(job.targets.get(0).asLong()); legacy.add(job.targets.get(1).asLong()); }
            }
        }
        long signature = 1125899906842597L;
        for (long pos : mining) signature = signature * 31 + pos;
        signature = signature * 31 + 1;
        for (long pos : sweeping) signature = signature * 31 + pos;
        signature = signature * 31 + 2;
        for (long pos : legacy) signature = signature * 31 + pos;
        String dimension = Objects.requireNonNull(level.dimension().location().toString());
        if (signature == known) return Objects.requireNonNull(List.of(new WorkNetwork.MarkPage(dimension, request, signature, 0, 0, new long[0], new long[0], new long[0])));
        var mines = new ArrayList<>(mining); var floors = new ArrayList<>(sweeping);
        int pages = Math.max(1, (mines.size() + floors.size() + legacy.size() + 2047) / 2048);
        List<WorkNetwork.MarkPage> result = new ArrayList<>();
        for (int page = 0; page < pages; page++) {
            int start = page * 2048, end = start + 2048;
            long[] mine = slice(mines, Math.min(start, mines.size()), Math.min(end, mines.size()));
            long[] sweep = slice(floors, Math.max(0, start - mines.size()), Math.min(floors.size(), Math.max(0, end - mines.size())));
            long[] older = slice(legacy, Math.max(0, start - mines.size() - floors.size()), Math.max(0, end - mines.size() - floors.size()));
            result.add(new WorkNetwork.MarkPage(dimension, request, signature, page, pages, mine, sweep, older));
        }
        return Objects.requireNonNull(List.copyOf(result));
    }
    @Nonnull private static long[] slice(List<Long> positions, int start, int end) {
        start = Math.min(start, positions.size()); end = Math.max(start, Math.min(end, positions.size()));
        long[] result = new long[end - start]; for (int index = start; index < end; index++) result[index - start] = Objects.requireNonNull(positions.get(index)); return result;
    }
    private static void send(ServerPlayer player, WorkNetwork.MarkPage page) {
        WorkNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), page);
    }
}
