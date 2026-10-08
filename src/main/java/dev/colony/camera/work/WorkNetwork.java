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
    @Nonnull public static final SimpleChannel CHANNEL = Objects.requireNonNull(NetworkRegistry.newSimpleChannel(Objects.requireNonNull(ResourceLocation.tryParse(ColonyCamera.ID + ":work")), () -> "4", "4"::equals, "4"::equals));
    public record Request(@Nonnull String action, @Nonnull String payload) {}
    public record Snapshot(@Nonnull String dimension, @Nonnull String json) {}
    public record OpenStation(@Nonnull BlockPos pos, boolean research) {}
    public record OpenPeople(@javax.annotation.Nullable BlockPos residence) { public OpenPeople() { this(null); } }
    public record CraftView(@Nonnull String dimension, @Nonnull String json) {}
    public record MarkPage(@Nonnull String dimension, int request, long signature, int index, int pages,
                           @Nonnull long[] mining, @Nonnull long[] sweeping, @Nonnull long[] legacy) {}
    public static final class CraftViewEvent extends Event {
        @Nonnull public final CraftView view;
        public CraftViewEvent(CraftView view) { this.view = view; }
    }
    public static final class MarkPageEvent extends Event {
        @Nonnull public final MarkPage page;
        public MarkPageEvent(MarkPage page) { this.page = page; }
    }
    @Nonnull private static final java.util.Map<ServerPlayer, Long> CRAFT_QUERIES = new java.util.WeakHashMap<>();
    @Nonnull private static final java.util.Map<ServerPlayer, Long> MARK_QUERIES = new java.util.WeakHashMap<>();
    public static final class OpenPeopleEvent extends Event { @javax.annotation.Nullable public final BlockPos residence; public OpenPeopleEvent(@javax.annotation.Nullable BlockPos pos) { residence = pos; } }
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
        CHANNEL.messageBuilder(OpenPeople.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((message, buffer) -> { var pos = message.residence(); buffer.writeBoolean(pos != null); if (pos != null) buffer.writeBlockPos(pos); })
                .decoder(buffer -> new OpenPeople(buffer.readBoolean() ? buffer.readBlockPos() : null))
                .consumerMainThread((message, context) -> MinecraftForge.EVENT_BUS.post(new OpenPeopleEvent(message.residence()))).add();
        CHANNEL.messageBuilder(CraftView.class, 4, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((view, buffer) -> { buffer.writeUtf(view.dimension(), 128); write(buffer, view.json()); })
                .decoder(buffer -> new CraftView(Objects.requireNonNull(buffer.readUtf(128)), read(Objects.requireNonNull(buffer))))
                .consumerMainThread((view, context) -> MinecraftForge.EVENT_BUS.post(new CraftViewEvent(Objects.requireNonNull(view)))).add();
        CHANNEL.messageBuilder(MarkPage.class, 5, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((page, buffer) -> {
                    buffer.writeUtf(page.dimension(), 128); buffer.writeInt(page.request()); buffer.writeLong(page.signature());
                    buffer.writeVarInt(page.index()); buffer.writeVarInt(page.pages());
                    writePositions(buffer, page.mining()); writePositions(buffer, page.sweeping()); writePositions(buffer, page.legacy());
                }).decoder(buffer -> new MarkPage(Objects.requireNonNull(buffer.readUtf(128)), buffer.readInt(), buffer.readLong(),
                        buffer.readVarInt(), buffer.readVarInt(), positions(buffer), positions(buffer), positions(buffer)))
                .consumerMainThread((page, context) -> MinecraftForge.EVENT_BUS.post(new MarkPageEvent(Objects.requireNonNull(page)))).add();
    }
    private static void writePositions(FriendlyByteBuf buffer, long[] positions) {
        if (positions.length > 2048) throw new IllegalArgumentException("limit");
        buffer.writeVarInt(positions.length); for (long position : positions) buffer.writeLong(position);
    }
    @Nonnull private static long[] positions(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt(); if (count < 0 || count > 2048 || count * 8 > buffer.readableBytes()) throw new IllegalArgumentException("limit");
        long[] positions = new long[count]; for (int index = 0; index < count; index++) positions[index] = buffer.readLong(); return positions;
    }
    private static boolean queryAllowed(java.util.Map<ServerPlayer, Long> queries, ServerPlayer player, int interval) {
        long time = player.serverLevel().getGameTime(); Long previous = queries.get(player);
        if (previous != null && time >= previous && time - previous < interval) return false;
        queries.put(player, time); return true;
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
                case "sweep" -> {
                    var array = data.getAsJsonArray("targets"); if (array == null || array.size() > 8192) throw new IllegalArgumentException("limit");
                    var targets = new ArrayList<BlockPos>(); for (var value : array) targets.add(BlockPos.of(value.getAsLong())); manager.sweep(player, targets);
                }
                case "sweep_items" -> {
                    var array = data.getAsJsonArray("items"); if (array == null || array.size() > 64) throw new IllegalArgumentException("limit");
                    var items = new ArrayList<String>(); for (var value : array) items.add(Objects.requireNonNull(value.getAsString())); manager.sweepItems(player, items);
                }
                case "craft_view" -> {
                    if (!queryAllowed(CRAFT_QUERIES, player, 10)) return;
                    var ids = new ArrayList<String>(); var array = data.getAsJsonArray("recipes");
                    if (array == null || array.size() > 24) throw new IllegalArgumentException("limit");
                    for (var id : array) { String value = Objects.requireNonNull(id.getAsString()); if (value.length() > 256) throw new IllegalArgumentException("limit"); ids.add(value); }
                    JsonObject view = dev.colony.camera.crafting.CraftingService.previewPage(player,
                            Objects.requireNonNull(BlockPos.of(data.get("station").getAsLong())), ids, data.get("count").getAsInt(), data.get("request").getAsInt());
                    CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CraftView(Objects.requireNonNull(player.serverLevel().dimension().location().toString()), Objects.requireNonNull(view.toString()))); return;
                }
                case "marks" -> { if (queryAllowed(MARK_QUERIES, player, 20)) WorkTargetMarks.send(player, data.get("request").getAsInt(), data.get("known").getAsLong()); return; }
                case "craft" -> manager.craft(player, Objects.requireNonNull(BlockPos.of(data.get("station").getAsLong())), Objects.requireNonNull(data.get("recipe").getAsString()), data.get("count").getAsInt());
                case "research" -> manager.research(player, Objects.requireNonNull(BlockPos.of(data.get("station").getAsLong())));
                case "worker" -> dev.colony.camera.worker.WorkerOrders.request(player, data);
                case "residence" -> dev.colony.camera.worker.ResidenceOrders.request(player, data);
                case "print" -> {
                    String result = dev.colony.camera.construction.ConstructionManager.get(Objects.requireNonNull(player.serverLevel())).printWorker(player,
                            Objects.requireNonNull(BlockPos.of(data.get("core").getAsLong())),
                            dev.colony.camera.worker.WorkerProfile.parse(dev.colony.camera.worker.WorkerProfile.Interest.class, Objects.requireNonNull(data.get("interest").getAsString())),
                            dev.colony.camera.worker.WorkerProfile.parse(dev.colony.camera.worker.WorkerProfile.Temperament.class, Objects.requireNonNull(data.get("temperament").getAsString())));
                    notice = "colonycamera.print." + result;
                }
                case "priority", "pause", "cancel", "remove" -> { manager.control(Objects.requireNonNull(player.serverLevel()), Objects.requireNonNull(player.getUUID()), Objects.requireNonNull(UUID.fromString(data.get("id").getAsString())), request.action(), data.has("value") ? data.get("value").getAsInt() : 0); notice = "colonycamera.work.notice.updated"; }
                case "list" -> notice = "";
                default -> throw new IllegalArgumentException("invalid");
            }
        } catch (RuntimeException failure) {
            String reason = failure.getMessage();
            if (reason == null || !List.of("empty", "limit", "location", "overlap", "station", "recipe", "research", "data", "invalid", "denied", "home", "school", "course", "loyalty", "equipment", "inventory_full").contains(reason)) reason = "invalid";
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
    public static void openPeople(ServerPlayer player) { snapshot(player, ""); CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenPeople()); }
    public static void openResidence(ServerPlayer player, BlockPos pos) { snapshot(player, ""); CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenPeople(pos)); }
}
