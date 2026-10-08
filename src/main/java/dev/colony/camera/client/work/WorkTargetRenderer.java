package dev.colony.camera.client.work;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.work.WorkNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The designation is server intent; clearing the cursor selection does not clear pending work. */
@Mod.EventBusSubscriber(modid = ColonyCamera.ID, value = Dist.CLIENT)
@ParametersAreNonnullByDefault
public final class WorkTargetRenderer {
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new BufferBuilder(65536));
    @Nonnull private static List<BlockPos> mining = Objects.requireNonNull(List.of()), sweeping = Objects.requireNonNull(List.of());
    @Nonnull private static List<AABB> legacy = Objects.requireNonNull(List.of());
    private static final Map<Integer, WorkNetwork.MarkPage> staged = new HashMap<>();
    @Nullable private static ClientLevel level;
    private static int request, ticks, requestedAt = -100;
    private static long signature = Long.MIN_VALUE;
    private static boolean waiting;
    private WorkTargetRenderer() {}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var current = Minecraft.getInstance().level;
        if (current != level) {
            level = current; mining = Objects.requireNonNull(List.of()); sweeping = Objects.requireNonNull(List.of()); legacy = Objects.requireNonNull(List.of());
            staged.clear(); signature = Long.MIN_VALUE; waiting = false; requestedAt = -100; ticks = 0; request++;
        }
        if (current == null || !StrategyCameraController.instance().isActive()) return;
        ticks++;
        if (ticks - requestedAt < (waiting ? 100 : 40)) return;
        JsonObject payload = new JsonObject(); payload.addProperty("request", ++request); payload.addProperty("known", signature);
        requestedAt = ticks; waiting = true; staged.clear(); ColonyWorkClient.request("marks", payload);
    }
    @SubscribeEvent public static void receive(WorkNetwork.MarkPageEvent event) {
        var page = event.page; ClientLevel current = level;
        if (current == null || page.request() != request || !current.dimension().location().toString().equals(page.dimension())) return;
        if (page.pages() == 0) { if (page.signature() == signature) waiting = false; return; }
        if (page.pages() < 1 || page.pages() > 256 || page.index() < 0 || page.index() >= page.pages()
                || page.mining().length + page.sweeping().length + page.legacy().length > 2048) return;
        if (!staged.isEmpty()) {
            var first = staged.values().iterator().next();
            if (first.pages() != page.pages() || first.signature() != page.signature()) return;
        }
        staged.put(page.index(), page); if (staged.size() != page.pages()) return;
        List<BlockPos> mines = new ArrayList<>(), floors = new ArrayList<>(); List<Long> old = new ArrayList<>();
        for (int index = 0; index < page.pages(); index++) {
            var part = staged.get(index); if (part == null) return;
            for (long pos : part.mining()) mines.add(BlockPos.of(pos));
            for (long pos : part.sweeping()) floors.add(BlockPos.of(pos));
            for (long pos : part.legacy()) old.add(pos);
        }
        if ((old.size() & 1) != 0) return;
        List<AABB> boxes = new ArrayList<>();
        for (int index = 0; index < old.size(); index += 2) {
            BlockPos min = BlockPos.of(Objects.requireNonNull(old.get(index))), max = BlockPos.of(Objects.requireNonNull(old.get(index + 1)));
            boxes.add(new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0));
        }
        mining = Objects.requireNonNull(List.copyOf(mines)); sweeping = Objects.requireNonNull(List.copyOf(floors)); legacy = Objects.requireNonNull(List.copyOf(boxes));
        signature = page.signature(); waiting = false; staged.clear();
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        ClientLevel current = level;
        if (current == null || !StrategyCameraController.instance().isActive()
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        VertexConsumer lines = Objects.requireNonNull(BUFFERS.getBuffer(Objects.requireNonNull(RenderType.lines())));
        for (BlockPos candidate : mining) {
            BlockPos pos = Objects.requireNonNull(candidate);
            if (loaded(current, pos) && !current.getBlockState(pos).isAir()) draw(event, lines, Objects.requireNonNull(new AABB(pos).inflate(0.005)), 1, 0.82F, 0.1F);
        }
        for (BlockPos candidate : sweeping) {
            BlockPos pos = Objects.requireNonNull(candidate); if (!loaded(current, pos)) continue;
            var state = current.getBlockState(pos); if (state.isAir()) continue;
            var shape = state.getCollisionShape(current, pos);
            if (shape.isEmpty()) drawTop(event, lines, new AABB(pos));
            else for (AABB part : shape.toAabbs()) drawTop(event, lines, Objects.requireNonNull(Objects.requireNonNull(part).move(pos)));
        }
        for (AABB box : legacy) draw(event, lines, Objects.requireNonNull(box), 0.2F, 0.65F, 1);
        BUFFERS.endBatch(Objects.requireNonNull(RenderType.lines()));
    }
    private static boolean loaded(ClientLevel current, BlockPos pos) { return current.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4); }
    private static void drawTop(RenderLevelStageEvent event, VertexConsumer lines, AABB shape) {
        draw(event, lines, new AABB(shape.minX, shape.maxY + 0.008, shape.minZ, shape.maxX, shape.maxY + 0.025, shape.maxZ), 0.2F, 0.65F, 1);
    }
    private static void draw(RenderLevelStageEvent event, VertexConsumer lines, AABB box, float red, float green, float blue) {
        if (!event.getFrustum().isVisible(box)) return;
        var camera = event.getCamera().getPosition();
        LevelRenderer.renderLineBox(Objects.requireNonNull(event.getPoseStack()), lines,
                Objects.requireNonNull(box.move(-camera.x, -camera.y, -camera.z)), red, green, blue, 0.85F);
    }
}
