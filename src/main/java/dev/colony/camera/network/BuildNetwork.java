package dev.colony.camera.network;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.construction.BuildCatalogService;
import dev.colony.camera.construction.ConstructionManager;
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
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;

@ParametersAreNonnullByDefault
public final class BuildNetwork {
    public static final int MAX_ENTRIES = 4096;
    @Nonnull public static final SimpleChannel CHANNEL = Objects.requireNonNull(NetworkRegistry.newSimpleChannel(
            Objects.requireNonNull(ResourceLocation.tryParse(ColonyCamera.ID + ":building")), () -> "1", "1"::equals, "1"::equals));
    public record Request(@Nonnull UUID id, boolean place, @Nonnull String item, @Nonnull BlockPos pos, int rotation, @Nonnull String recipe, @Nullable BlockPos station) {}
    public record Catalog(@Nonnull UUID request, @Nonnull String dimension, @Nonnull List<BuildCatalogService.Entry> entries) {
        public Catalog { entries = Objects.requireNonNull(List.copyOf(entries)); }
    }
    public record Reply(@Nonnull UUID id, @Nonnull String dimension, boolean accepted, @Nonnull Component notice) {}
    public static final class CatalogEvent extends Event {
        @Nonnull public final Catalog value; public CatalogEvent(Catalog value) { this.value = value; }
    }
    public static final class ReplyEvent extends Event {
        @Nonnull public final Reply value; public ReplyEvent(Reply value) { this.value = value; }
    }
    private BuildNetwork() {}
    public static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER).encoder((value, buf) -> {
            buf.writeUUID(value.id()); buf.writeBoolean(value.place()); buf.writeUtf(value.item(), 256); buf.writeBlockPos(value.pos()); buf.writeByte(value.rotation());
            buf.writeUtf(value.recipe(), 256); writePosition(buf, value.station());
        }).decoder(buf -> new Request(Objects.requireNonNull(buf.readUUID()), buf.readBoolean(), Objects.requireNonNull(buf.readUtf(256)), Objects.requireNonNull(buf.readBlockPos()), buf.readUnsignedByte(), Objects.requireNonNull(buf.readUtf(256)), readPosition(buf)))
                .consumerMainThread((value, context) -> { ServerPlayer player = context.get().getSender(); if (player != null) handle(player, Objects.requireNonNull(value)); }).add();
        CHANNEL.messageBuilder(Catalog.class, 1, NetworkDirection.PLAY_TO_CLIENT).encoder((value, buf) -> {
            buf.writeUUID(value.request()); buf.writeUtf(value.dimension(), 128); buf.writeVarInt(value.entries().size());
            for (var entry : value.entries()) { buf.writeUtf(entry.item(), 256); buf.writeVarInt(entry.available()); buf.writeUtf(entry.recipe(), 256); writePosition(buf, entry.station()); }
        }).decoder(buf -> {
            UUID request = Objects.requireNonNull(buf.readUUID()); String dimension = Objects.requireNonNull(buf.readUtf(128)); int size = buf.readVarInt();
            if (size < 0 || size > MAX_ENTRIES) throw new IllegalArgumentException("limit");
            var entries = new ArrayList<BuildCatalogService.Entry>();
            for (int i = 0; i < size; i++) entries.add(new BuildCatalogService.Entry(Objects.requireNonNull(buf.readUtf(256)), buf.readVarInt(), Objects.requireNonNull(buf.readUtf(256)), readPosition(buf)));
            return new Catalog(request, dimension, entries);
        }).consumerMainThread((value, context) -> MinecraftForge.EVENT_BUS.post(new CatalogEvent(Objects.requireNonNull(value)))).add();
        CHANNEL.messageBuilder(Reply.class, 2, NetworkDirection.PLAY_TO_CLIENT).encoder((value, buf) -> {
            buf.writeUUID(value.id()); buf.writeUtf(value.dimension(), 128); buf.writeBoolean(value.accepted()); buf.writeComponent(value.notice());
        }).decoder(buf -> new Reply(Objects.requireNonNull(buf.readUUID()), Objects.requireNonNull(buf.readUtf(128)), buf.readBoolean(), Objects.requireNonNull(buf.readComponent())))
                .consumerMainThread((value, context) -> MinecraftForge.EVENT_BUS.post(new ReplyEvent(Objects.requireNonNull(value)))).add();
    }
    private static void writePosition(FriendlyByteBuf buf, @Nullable BlockPos pos) { buf.writeBoolean(pos != null); if (pos != null) buf.writeBlockPos(pos); }
    @Nullable private static BlockPos readPosition(FriendlyByteBuf buf) { return buf.readBoolean() ? buf.readBlockPos() : null; }
    private static void handle(ServerPlayer player, Request request) {
        if (!request.place()) { BuildCatalogService.request(player, request.id()); return; }
        boolean accepted = false; Component notice;
        try {
            ResourceLocation id = ResourceLocation.tryParse(request.item());
            if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) throw new IllegalArgumentException("unsupported");
            BuildCatalogService.place(player, request.id(), Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(id)), request.pos(), request.rotation(), request.recipe(), request.station());
            accepted = true; notice = Component.translatable("colonycamera.build.created");
        } catch (RuntimeException failure) {
            String code = failure.getMessage(); if (code == null || !List.of("data", "limit", "location", "overlap", "duplicate", "changed", "station", "recipe", "denied").contains(code)) code = "unsupported";
            notice = Component.translatable("colonycamera.build.error." + code);
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Reply(request.id(), Objects.requireNonNull(player.serverLevel().dimension().location().toString()), accepted, Objects.requireNonNull(notice)));
        var manager = ConstructionManager.get(Objects.requireNonNull(player.serverLevel())); ConstructionNetwork.snapshot(player, manager, manager.workers(Objects.requireNonNull(player.serverLevel())));
    }
    public static void catalog(ServerPlayer player, UUID request, List<BuildCatalogService.Entry> entries) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Catalog(request, player.serverLevel().dimension().location().toString(), entries));
    }
}
