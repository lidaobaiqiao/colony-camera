package dev.colony.camera.client.construction;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.client.planning.ProjectionController;
import dev.colony.camera.construction.BuildCatalogService;
import dev.colony.camera.network.BuildNetwork;
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
import java.util.*;

@Mod.EventBusSubscriber(modid = ColonyCamera.ID, value = Dist.CLIENT)
@ParametersAreNonnullByDefault
public final class BuildingClient {
    private static final Map<String, BuildCatalogService.Entry> entries = new HashMap<>();
    @Nullable private static ClientLevel level;
    @Nullable private static UUID catalogRequest;
    @Nullable private static UUID placementRequest;
    private static long lastRequest;
    private static long placementTime;
    private static long noticeTime;
    private static boolean ready;
    private static int revision;
    @Nonnull private static Component notice = Objects.requireNonNull(Component.empty());
    private BuildingClient() {}
    public static boolean ready() { return ready; }
    public static int revision() { return revision; }
    public static boolean pending() { return placementRequest != null; }
    @Nonnull public static Component notice() { return notice; }
    public static boolean recentNotice() { return !notice.getString().isEmpty() && (pending() || System.nanoTime() - noticeTime < 4_000_000_000L); }
    private static void notice(Component value) { notice = value; noticeTime = System.nanoTime(); }
    @Nullable public static BuildCatalogService.Entry entry(String id) { return entries.get(id); }
    public static void refresh() {
        ClientLevel current = Minecraft.getInstance().level; if (current == null) return;
        if (catalogRequest != null && System.nanoTime() - lastRequest < 15_000_000_000L) return;
        if (System.nanoTime() - lastRequest < 2_000_000_000L) return;
        level = current; catalogRequest = Objects.requireNonNull(UUID.randomUUID()); lastRequest = System.nanoTime();
        BuildNetwork.CHANNEL.sendToServer(new BuildNetwork.Request(Objects.requireNonNull(catalogRequest), false, "", Objects.requireNonNull(BlockPos.ZERO), 0, "", null));
    }
    public static boolean place(String item, ProjectionPlacement placement) {
        if (pending()) return false;
        if (!ready) { notice(text("loading")); refresh(); return false; }
        var value = entries.get(item); String recipe = value == null || value.available() > 0 ? "" : value.recipe();
        placementRequest = placement.id(); placementTime = System.nanoTime(); notice(text("sending"));
        BuildNetwork.CHANNEL.sendToServer(new BuildNetwork.Request(placement.id(), true, item, new BlockPos(placement.x(), placement.y(), placement.z()), placement.quarterTurns(), recipe, value == null ? null : value.station()));
        return true;
    }
    @SubscribeEvent public static void catalog(BuildNetwork.CatalogEvent event) {
        var current = Minecraft.getInstance().level;
        if (current == null || !current.dimension().location().toString().equals(event.value.dimension()) || !event.value.request().equals(catalogRequest)) return;
        level = current; entries.clear(); for (var entry : event.value.entries()) entries.put(entry.item(), entry);
        ready = true; catalogRequest = null; revision++;
    }
    @SubscribeEvent public static void reply(BuildNetwork.ReplyEvent event) {
        var current = Minecraft.getInstance().level;
        if (current == null || !current.dimension().location().toString().equals(event.value.dimension()) || !event.value.id().equals(placementRequest)) return;
        placementRequest = null; notice(event.value.notice());
        if (event.value.accepted()) ProjectionController.instance().placedBlock(event.value.id());
        lastRequest = 0; refresh();
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var current = Minecraft.getInstance().level;
        if (current != level) { level = current; entries.clear(); ready = false; catalogRequest = placementRequest = null; lastRequest = 0; notice = Objects.requireNonNull(Component.empty()); revision++; }
        if (pending() && System.nanoTime() - placementTime > 15_000_000_000L) { placementRequest = null; notice(text("timeout")); ConstructionClient.refresh(); }
    }
    @Nonnull private static Component text(String key) { return Objects.requireNonNull(Component.translatable("screen.colonycamera.build." + key)); }
}
