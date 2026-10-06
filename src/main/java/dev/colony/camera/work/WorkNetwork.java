package dev.colony.camera.work;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.network.ConstructionPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ParametersAreNonnullByDefault
public final class WorkNetwork {
    @Nonnull public static final SimpleChannel CHANNEL = Objects.requireNonNull(NetworkRegistry.newSimpleChannel(Objects.requireNonNull(ResourceLocation.tryParse(ColonyCamera.ID + ":work")), () -> "1", "1"::equals, "1"::equals));
    public record Request(@Nonnull String action, @Nonnull String payload) {}
    public record Snapshot(@Nonnull String dimension, @Nonnull String json) {}
    public record OpenStation(@Nonnull BlockPos pos, boolean research) {}
    public static final class SnapshotEvent extends Event {
        @Nonnull public final Snapshot snapshot;
        public SnapshotEvent(Snapshot snapshot) { this.snapshot = snapshot; }
    }
    public static final class OpenStationEvent extends Event {
        @Nonnull public final BlockPos pos;
        public final boolean research;
        public OpenStationEvent(BlockPos pos, boolean research) { this.pos = pos; this.research = research; }
    }
    private WorkNetwork() {}
    public static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((request, buffer) -> { buffer.writeUtf(request.action(), 16); write(buffer, request.payload()); })
                .decoder(buffer -> new Request(Objects.requireNonNull(buffer.readUtf(16)), read(Objects.requireNonNull(buffer))))
                .consumerMainThread((request, context) -> { var player = context.get().getSender(); if (player != null) handle(player, Objects.requireNonNull(request)); }).add();
        CHANNEL.messageBuilder(Snapshot.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((state, buffer) -> { buffer.writeUtf(state.dimension(), 128); write(buffer, state.json()); })
                .decoder(buffer -> new Snapshot(Objects.requireNonNull(buffer.readUtf(128)), read(Objects.requireNonNull(buffer))))
                .consumerMainThread((state, context) -> MinecraftForge.EVENT_BUS.post(new SnapshotEvent(Objects.requireNonNull(state)))).add();
        CHANNEL.messageBuilder(OpenStation.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((station, buffer) -> { buffer.writeBlockPos(station.pos()); buffer.writeBoolean(station.research()); })
                .decoder(buffer -> new OpenStation(Objects.requireNonNull(buffer.readBlockPos()), buffer.readBoolean()))
                .consumerMainThread((station, context) -> MinecraftForge.EVENT_BUS.post(new OpenStationEvent(station.pos(), station.research()))).add();
    }
    private static void write(FriendlyByteBuf buffer, String json) { byte[] compressed = ConstructionPayload.encode(json); buffer.writeVarInt(compressed.length); buffer.writeBytes(compressed); }
    @Nonnull private static String read(FriendlyByteBuf buffer) {
        int length = buffer.readVarInt(); if (length < 0 || length > ConstructionPayload.MAX_WIRE || length > buffer.readableBytes()) throw new IllegalArgumentException("limit");
        byte[] compressed = new byte[length]; buffer.readBytes(compressed); return Objects.requireNonNull(ConstructionPayload.decode(compressed));
    }
    public static void handle(ServerPlayer player, Request request) {
        String notice = "colonycamera.work.notice.created";
        try {
            ColonyWorkManager manager = ColonyWorkManager.get(Objects.requireNonNull(player.serverLevel()));
            JsonObject data = request.payload().isEmpty() ? new JsonObject() : Objects.requireNonNull(JsonParser.parseString(request.payload()).getAsJsonObject());
            switch (request.action()) {
                case "mine" -> {
                    var array = data.getAsJsonArray("targets"); if (array == null || array.size() > 8192) throw new IllegalArgumentException("limit");
                    var targets = new ArrayList<BlockPos>(); for (var value : array) targets.add(BlockPos.of(value.getAsLong())); manager.mine(player, targets);
                }
                case "sweep" -> manager.sweep(player, Objects.requireNonNull(BlockPos.of(data.get("min").getAsLong())), Objects.requireNonNull(BlockPos.of(data.get("max").getAsLong())));
                case "craft" -> manager.craft(player, Objects.requireNonNull(BlockPos.of(data.get("station").getAsLong())), Objects.requireNonNull(data.get("recipe").getAsString()), data.get("count").getAsInt());
                case "research" -> manager.research(player, Objects.requireNonNull(BlockPos.of(data.get("station").getAsLong())));
                case "priority", "pause", "cancel", "remove" -> { manager.control(Objects.requireNonNull(player.serverLevel()), Objects.requireNonNull(player.getUUID()), Objects.requireNonNull(UUID.fromString(data.get("id").getAsString())), request.action(), data.has("value") ? data.get("value").getAsInt() : 0); notice = "colonycamera.work.notice.updated"; }
                case "list" -> notice = "";
                default -> throw new IllegalArgumentException("invalid");
            }
        } catch (RuntimeException failure) {
            String reason = failure.getMessage();
            if (reason == null || !List.of("empty", "limit", "location", "overlap", "station", "recipe", "research", "data", "invalid", "denied").contains(reason)) reason = "invalid";
            notice = "colonycamera.work.error." + reason;
        }
        snapshot(player, notice);
    }
    public static void snapshot(ServerPlayer player, String notice) {
        JsonObject data = ColonyWorkManager.get(Objects.requireNonNull(player.serverLevel())).describe(player);
        data.addProperty("notice", ColonyWorkManager.get(Objects.requireNonNull(player.serverLevel())).readOnly() ? "colonycamera.work.error.data" : notice);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Snapshot(Objects.requireNonNull(player.serverLevel().dimension().location().toString()), Objects.requireNonNull(data.toString())));
    }
    public static void openStation(ServerPlayer player, BlockPos pos, boolean research) {
        ColonyWorkManager.station(player, pos, research); snapshot(player, "");
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenStation(pos, research));
    }
}
