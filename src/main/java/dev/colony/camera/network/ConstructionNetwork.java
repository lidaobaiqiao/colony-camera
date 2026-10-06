package dev.colony.camera.network;

import com.google.gson.JsonParser;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.construction.ConstructionManager;
import dev.colony.camera.planning.ProjectionCodec;
import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.worker.WorkerEntity;
import dev.colony.camera.content.PrintingCores;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ParametersAreNonnullByDefault
public final class ConstructionNetwork {
    public static final int MAX_JSON = ConstructionPayload.MAX_JSON;
    @Nonnull public static final UUID NONE = new UUID(0, 0);
    @Nonnull public static final SimpleChannel CHANNEL = Objects.requireNonNull(NetworkRegistry.newSimpleChannel(
            Objects.requireNonNull(ResourceLocation.tryParse(ColonyCamera.ID + ":construction")), () -> "3", "3"::equals, "3"::equals));
    private ConstructionNetwork() {}
    public record Request(@Nonnull String action, @Nonnull UUID id, int value, @Nonnull String payload) {}
    public record View(@Nonnull UUID id, @Nonnull String name, int x, int y, int z, int priority, int completed, int total,
                       int workers, int ground, int reserved, @Nonnull String neededItem, int neededCount,
                       @Nonnull String reason, boolean paused, boolean cancelled, long sequence, @Nullable ProjectionPlacement palettePlan) {}
    public record Snapshot(@Nonnull String dimension, @Nonnull List<View> projects, int workers, @Nonnull List<BlockPos> cores, @Nonnull Component notice) {
        public Snapshot { projects = Objects.requireNonNull(List.copyOf(projects)); cores = Objects.requireNonNull(List.copyOf(cores)); }
    }
    /** The common packet handler publishes a value event; only a client subscriber consumes it. */
    public static final class SnapshotEvent extends Event {
        @Nonnull public final Snapshot snapshot;
        public SnapshotEvent(Snapshot snapshot) { this.snapshot = snapshot; }
    }
    public static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((request, buf) -> {
                    buf.writeUtf(request.action(), 16); buf.writeUUID(request.id()); buf.writeInt(request.value());
                    byte[] compressed = ConstructionPayload.encode(request.payload()); buf.writeVarInt(compressed.length); buf.writeBytes(compressed);
                })
                .decoder(buf -> {
                    String action = Objects.requireNonNull(buf.readUtf(16)); UUID id = Objects.requireNonNull(buf.readUUID()); int value = buf.readInt();
                    int length = buf.readVarInt();
                    if (length < 0 || length > ConstructionPayload.MAX_WIRE) throw new IllegalArgumentException("large");
                    byte[] compressed = new byte[length]; buf.readBytes(compressed);
                    return new Request(action, id, value, Objects.requireNonNull(ConstructionPayload.decode(compressed)));
                })
                .consumerMainThread((request, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null) handle(player, Objects.requireNonNull(request));
                }).add();
        CHANNEL.messageBuilder(Snapshot.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ConstructionNetwork::encodeSnapshot).decoder(ConstructionNetwork::decodeSnapshot)
                .consumerMainThread((snapshot, context) -> MinecraftForge.EVENT_BUS.post(new SnapshotEvent(Objects.requireNonNull(snapshot)))).add();
    }
    private static void handle(ServerPlayer player, Request request) {
        ConstructionManager manager = ConstructionManager.get(Objects.requireNonNull(player.serverLevel()));
        Component notice = Component.empty();
        try {
            switch (request.action()) {
                case "create" -> {
                    manager.create(player, ProjectionCodec.decode(Objects.requireNonNull(JsonParser.parseString(request.payload()).getAsJsonObject())), request.value());
                    notice = Component.translatable("colonycamera.construction.created");
                }
                case "print" -> notice = Component.translatable("colonycamera.print." + manager.printWorker(player, Objects.requireNonNull(BlockPos.of(Long.parseLong(request.payload())))));
                case "priority", "pause", "cancel", "remove" -> manager.update(Objects.requireNonNull(player.getUUID()), request.id(), request.action(), request.value());
                case "list" -> { }
                default -> { return; }
            }
        } catch (RuntimeException failure) {
            String code = failure.getMessage();
            if (!List.of("duplicate", "limit", "location", "overlap", "data").contains(code)) code = "unsupported";
            notice = Component.translatable("colonycamera.construction.error." + code);
        }
        snapshot(player, manager, manager.workers(Objects.requireNonNull(player.serverLevel())), Objects.requireNonNull(notice));
    }
    public static void snapshot(ServerPlayer player, ConstructionManager manager, List<WorkerEntity> workers) {
        snapshot(player, manager, workers, Objects.requireNonNull(Component.empty()));
    }
    private static void snapshot(ServerPlayer player, ConstructionManager manager, List<WorkerEntity> workers, Component notice) {
        if (manager.readOnly()) notice = Objects.requireNonNull(Component.translatable("colonycamera.construction.error.data"));
        List<View> views = manager.projects(Objects.requireNonNull(player.getUUID())).stream().map(project -> new View(project.id(), project.placement.preset().name(),
                project.placement.x(), project.placement.y(), project.placement.z(), project.priority, project.completedBlocks(), project.placement.preset().blocks().size(),
                project.activeWorkers, project.groundKnown, project.reserved, project.neededItem, project.neededCount,
                project.reason, project.paused, project.cancelled, project.sequence, project.palette ? project.placement : null)).toList();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Snapshot(player.serverLevel().dimension().location().toString(), Objects.requireNonNull(views),
                (int) workers.stream().filter(worker -> player.getUUID().equals(worker.owner())).count(),
                Objects.requireNonNull(PrintingCores.owned(Objects.requireNonNull(player.serverLevel()), Objects.requireNonNull(player.getUUID())).stream().limit(256).toList()), notice));
    }
    private static void encodeSnapshot(Snapshot snapshot, FriendlyByteBuf buf) {
        buf.writeUtf(snapshot.dimension(), 128); buf.writeInt(snapshot.workers()); buf.writeComponent(snapshot.notice());
        buf.writeVarInt(snapshot.cores().size()); for (BlockPos core : snapshot.cores()) buf.writeBlockPos(Objects.requireNonNull(core));
        buf.writeVarInt(snapshot.projects().size());
        for (View view : snapshot.projects()) {
            buf.writeUUID(view.id()); buf.writeUtf(view.name(), 48);
            buf.writeInt(view.x()); buf.writeInt(view.y()); buf.writeInt(view.z()); buf.writeInt(view.priority());
            buf.writeInt(view.completed()); buf.writeInt(view.total()); buf.writeInt(view.workers()); buf.writeInt(view.ground()); buf.writeInt(view.reserved());
            buf.writeUtf(view.neededItem(), 256); buf.writeInt(view.neededCount());
            buf.writeUtf(view.reason(), 32); buf.writeBoolean(view.paused()); buf.writeBoolean(view.cancelled()); buf.writeLong(view.sequence());
            ProjectionPlacement plan = view.palettePlan(); buf.writeBoolean(plan != null);
            if (plan != null) buf.writeUtf(Objects.requireNonNull(ProjectionCodec.encode(plan).toString()), 4096);
        }
    }
    @Nonnull private static Snapshot decodeSnapshot(FriendlyByteBuf buf) {
        String dimension = Objects.requireNonNull(buf.readUtf(128)); int workers = buf.readInt();
        Component notice = Objects.requireNonNull(buf.readComponent()); int coreCount = buf.readVarInt();
        if (coreCount < 0 || coreCount > 256) throw new IllegalArgumentException("Invalid core snapshot");
        var cores = new ArrayList<BlockPos>(); for (int i = 0; i < coreCount; i++) cores.add(Objects.requireNonNull(buf.readBlockPos()));
        int count = buf.readVarInt();
        if (count < 0 || count > ConstructionManager.MAX_PROJECTS) throw new IllegalArgumentException("Invalid project snapshot");
        var views = new ArrayList<View>();
        for (int i = 0; i < count; i++) views.add(new View(Objects.requireNonNull(buf.readUUID()), Objects.requireNonNull(buf.readUtf(48)),
                buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                Objects.requireNonNull(buf.readUtf(256)), buf.readInt(),
                Objects.requireNonNull(buf.readUtf(32)), buf.readBoolean(), buf.readBoolean(), buf.readLong(),
                buf.readBoolean() ? ProjectionCodec.decode(Objects.requireNonNull(JsonParser.parseString(buf.readUtf(4096)).getAsJsonObject())) : null));
        return new Snapshot(dimension, views, workers, cores, notice);
    }
}
