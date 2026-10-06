package dev.colony.camera.client.construction;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.network.ConstructionNetwork;
import dev.colony.camera.network.ConstructionPayload;
import dev.colony.camera.planning.ProjectionCodec;
import dev.colony.camera.planning.ProjectionPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ParametersAreNonnullByDefault
@Mod.EventBusSubscriber(modid = ColonyCamera.ID, value = Dist.CLIENT)
public final class ConstructionClient {
    @Nonnull private static List<ConstructionNetwork.View> projects = Objects.requireNonNull(List.of());
    @Nonnull private static Component notice = Objects.requireNonNull(Component.empty());
    private static int workers;
    @Nonnull private static List<BlockPos> cores = Objects.requireNonNull(List.of());
    @Nullable private static ClientLevel currentLevel;
    private ConstructionClient() {}
    @Nonnull public static List<ConstructionNetwork.View> projects() { return projects; }
    @Nonnull public static List<ProjectionPlacement> palettePlans() {
        return Objects.requireNonNull(projects.stream().filter(view -> !view.cancelled() && view.completed() < view.total())
                .map(view -> Objects.requireNonNull(view).palettePlan()).filter(Objects::nonNull).toList());
    }
    @Nonnull public static Component notice() { return notice; }
    public static int workers() { return workers; }
    public static int cores() { return cores.size(); }
    public static void print(@Nullable ConstructionNetwork.View project) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        BlockPos target = project == null ? Objects.requireNonNull(player.blockPosition()) : new BlockPos(project.x(), project.y(), project.z());
        var core = cores.stream().min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(target))).orElse(null);
        if (core == null) { notice = Objects.requireNonNull(Component.translatable("colonycamera.print.no_core")); return; }
        ConstructionNetwork.CHANNEL.sendToServer(new ConstructionNetwork.Request("print", ConstructionNetwork.NONE, 0, Objects.requireNonNull(String.valueOf(core.asLong()))));
    }
    public static void request(String action, UUID id, int value) { ConstructionNetwork.CHANNEL.sendToServer(new ConstructionNetwork.Request(action, id, value, "")); }
    public static void refresh() { request("list", ConstructionNetwork.NONE, 0); }
    public static void create(ProjectionPlacement placement) {
        String payload = Objects.requireNonNull(ProjectionCodec.encode(placement).toString());
        try { ConstructionPayload.encode(payload); }
        catch (IllegalArgumentException failure) {
            notice = Objects.requireNonNull(Component.translatable("colonycamera.construction.error.large")); return;
        }
        ConstructionNetwork.CHANNEL.sendToServer(new ConstructionNetwork.Request("create", placement.id(), 5, payload));
    }
    @SubscribeEvent public static void receive(ConstructionNetwork.SnapshotEvent event) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().toString().equals(event.snapshot.dimension())) return;
        currentLevel = level;
        projects = Objects.requireNonNull(event.snapshot.projects()); workers = event.snapshot.workers();
        cores = Objects.requireNonNull(event.snapshot.cores());
        if (!event.snapshot.notice().getString().isEmpty()) notice = Objects.requireNonNull(event.snapshot.notice());
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        var level = Minecraft.getInstance().level;
        if (event.phase == TickEvent.Phase.END && level != currentLevel) {
            projects = Objects.requireNonNull(List.of()); notice = Objects.requireNonNull(Component.empty()); workers = 0;
            cores = Objects.requireNonNull(List.of());
            currentLevel = level;
        }
    }
}
