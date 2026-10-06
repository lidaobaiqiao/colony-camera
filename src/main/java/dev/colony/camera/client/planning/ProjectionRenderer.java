package dev.colony.camera.client.planning;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.colony.camera.client.preset.MinecraftPresetAdapter;
import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.preset.BlockPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Draws models in a separate translucent pass; never inserts ghost blocks into ClientLevel. */
@ParametersAreNonnullByDefault
public final class ProjectionRenderer {
    // Separate builders prevent line/model render types from flushing and reusing
    // the same immediate buffer while both consumers are still being written.
    private static final MultiBufferSource.BufferSource MODEL_BUFFERS = MultiBufferSource.immediate(new BufferBuilder(262144));
    private static final MultiBufferSource.BufferSource LINE_BUFFERS = MultiBufferSource.immediate(new BufferBuilder(65536));
    private static final Map<BlockState, List<BakedQuad>> QUADS = new HashMap<>();
    private static final Map<DesignKey, List<PreparedBlock>> DESIGNS = new HashMap<>();
    @Nonnull private static final int[] LIGHTS = {LightTexture.FULL_BRIGHT, LightTexture.FULL_BRIGHT, LightTexture.FULL_BRIGHT, LightTexture.FULL_BRIGHT};
    @Nonnull private static final float[] SHADE = {1, 1, 1, 1};
    @Nullable private static ProjectionPlacement scanned;
    @Nullable private static ClientLevel scannedLevel;
    private static int scanIndex;
    private static int missing;
    private static int matching;
    private static int conflicting;
    private static int unavailable;
    private static long scanCompletedAt;

    private ProjectionRenderer() {}

    public static void invalidate() { QUADS.clear(); DESIGNS.clear(); scanned = null; scannedLevel = null; }

    @Nonnull
    public static Statistics statistics() {
        ProjectionPlacement current = scanned;
        return new Statistics(missing, matching, conflicting, unavailable, current != null && scanIndex >= current.preset().blocks().size());
    }

    public static void render(Minecraft mc, RenderLevelStageEvent event) {
        ClientLevel level = mc.level;
        if (level == null) { invalidate(); return; }
        ProjectionController controller = ProjectionController.instance();
        ProjectionPlacement draft = controller.draft();
        if (!Objects.equals(scanned, draft) || scannedLevel != level) {
            scanned = draft; scannedLevel = level; scanIndex = missing = matching = conflicting = unavailable = 0;
        }
        if (draft != null) scan(level, draft);
        var placements = new ArrayList<>(controller.placements());
        placements.addAll(dev.colony.camera.client.construction.ConstructionClient.palettePlans());
        if (placements.isEmpty() && draft == null) return;
        RenderType type = Objects.requireNonNull(RenderType.entityTranslucent(Objects.requireNonNull(InventoryMenu.BLOCK_ATLAS)));
        VertexConsumer models = Objects.requireNonNull(MODEL_BUFFERS.getBuffer(type));
        RenderType lineType = Objects.requireNonNull(RenderType.lines());
        VertexConsumer lines = Objects.requireNonNull(LINE_BUFFERS.getBuffer(lineType));
        Vec3 camera = Objects.requireNonNull(event.getCamera().getPosition());
        try {
            for (ProjectionPlacement placement : placements) {
                if (placement.visible() && (draft == null || !draft.id().equals(placement.id()))) {
                    draw(mc, level, event, Objects.requireNonNull(placement), camera, models, lines);
                }
            }
            if (draft != null) draw(mc, level, event, draft, camera, models, lines);
        } finally {
            MODEL_BUFFERS.endBatch(type);
            LINE_BUFFERS.endBatch(lineType);
        }
    }

    private static void scan(ClientLevel level, ProjectionPlacement placement) {
        List<PreparedBlock> blocks = prepare(placement);
        if (scanIndex == blocks.size() && level.getGameTime() - scanCompletedAt >= 40) {
            scanIndex = missing = matching = conflicting = unavailable = 0;
        }
        int before = scanIndex;
        for (int end = Math.min(blocks.size(), scanIndex + 256); scanIndex < end; scanIndex++) {
            PreparedBlock block = Objects.requireNonNull(blocks.get(scanIndex));
            BlockPos pos = new BlockPos(placement.x() + block.x(), placement.y() + block.y(), placement.z() + block.z());
            if (!loaded(level, pos)) unavailable++;
            else {
                BlockState actual = level.getBlockState(pos);
                if (actual.equals(block.state())) matching++;
                else if (actual.isAir()) missing++;
                else conflicting++;
            }
        }
        // Revisit dynamic terrain after a complete sweep, without blocking a frame on a whole structure.
        if (before < blocks.size() && scanIndex == blocks.size()) scanCompletedAt = level.getGameTime();
    }

    private static void draw(Minecraft mc, ClientLevel level, RenderLevelStageEvent event, ProjectionPlacement placement,
                             Vec3 camera, VertexConsumer models, VertexConsumer lines) {
        double distance = Math.max(32, mc.options.getEffectiveRenderDistance() * 16.0);
        if (camera.x < placement.x() - distance || camera.x > placement.x() + placement.width() + distance
                || camera.z < placement.z() - distance || camera.z > placement.z() + placement.depth() + distance) return;
        PoseStack poses = event.getPoseStack();
        poses.pushPose();
        try {
            poses.translate(placement.x() - camera.x, placement.y() - camera.y, placement.z() - camera.z);
            LevelRenderer.renderLineBox(poses, lines, 0, 0, 0, placement.width(), placement.preset().sizeY(), placement.depth(), 0.4F, 0.95F, 0.85F, 0.8F);
            for (PreparedBlock block : prepare(placement)) {
                BlockPos pos = new BlockPos(placement.x() + block.x(), placement.y() + block.y(), placement.z() + block.z());
                if (!loaded(level, pos)) continue;
                BlockState actual = level.getBlockState(pos);
                if (actual.equals(block.state())) continue;
                boolean conflict = !actual.isAir();
                float red = conflict ? 1 : 0.65F;
                float green = conflict ? 0.35F : 1;
                float blue = conflict ? 0.3F : 0.95F;
                if (block.state().getRenderShape() != RenderShape.MODEL) {
                    double inset = conflict ? -0.003 : 0.03;
                    LevelRenderer.renderLineBox(poses, lines, block.x() + inset, block.y() + inset, block.z() + inset,
                            block.x() + 1 - inset, block.y() + 1 - inset, block.z() + 1 - inset, red, green, blue, 0.8F);
                    continue;
                }
                poses.pushPose();
                try {
                    double inset = conflict ? -0.001 : 0.001;
                    float scale = conflict ? 1.002F : 0.998F;
                    poses.translate(block.x() + inset, block.y() + inset, block.z() + inset);
                    poses.scale(scale, scale, scale);
                    for (BakedQuad quad : quads(mc, block.state())) {
                        if (block.hiddenFaces()[quad.getDirection().ordinal()]) continue;
                        int tint = quad.isTinted() ? mc.getBlockColors().getColor(block.state(), level, pos, quad.getTintIndex()) : 0xFFFFFF;
                        models.putBulkData(Objects.requireNonNull(poses.last()), quad, SHADE, red * ((tint >> 16) & 255) / 255F,
                                green * ((tint >> 8) & 255) / 255F, blue * (tint & 255) / 255F,
                                conflict ? 0.55F : 0.42F, LIGHTS, OverlayTexture.NO_OVERLAY, true);
                    }
                } finally { poses.popPose(); }
            }
        } finally { poses.popPose(); }
    }

    private static boolean loaded(ClientLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Nonnull
    private static List<PreparedBlock> prepare(ProjectionPlacement placement) {
        DesignKey key = new DesignKey(placement.preset(), placement.quarterTurns(), placement.mirrored());
        if (DESIGNS.size() >= 64 && !DESIGNS.containsKey(key)) DESIGNS.clear();
        return Objects.requireNonNull(DESIGNS.computeIfAbsent(key, ignored -> {
            var local = placement.at(0, 0, 0);
            var blocks = new ArrayList<PreparedBlock>();
            Map<BlockPos, BlockState> states = new HashMap<>();
            Map<BlockPreset.State, BlockState> palette = new HashMap<>();
            for (BlockPreset.Cell cell : placement.preset().blocks()) {
                var point = local.transform(Objects.requireNonNull(cell));
                var state = Objects.requireNonNull(palette.computeIfAbsent(cell.state(), stored ->
                        MinecraftPresetAdapter.resolve(Objects.requireNonNull(stored), placement.quarterTurns(), placement.mirrored())));
                states.put(new BlockPos(point.x(), point.y(), point.z()), state);
            }
            ClientLevel world = Minecraft.getInstance().level;
            for (var entry : states.entrySet()) {
                var pos = Objects.requireNonNull(entry.getKey());
                var state = Objects.requireNonNull(entry.getValue());
                boolean[] hidden = new boolean[6];
                if (world != null && state.canOcclude() && state.isCollisionShapeFullBlock(world, pos)) {
                    for (Direction direction : Direction.values()) {
                        BlockState neighbor = states.get(Objects.requireNonNull(pos.relative(Objects.requireNonNull(direction))));
                        hidden[direction.ordinal()] = neighbor != null && neighbor.canOcclude() && neighbor.isCollisionShapeFullBlock(world, Objects.requireNonNull(pos.relative(Objects.requireNonNull(direction))));
                    }
                }
                blocks.add(new PreparedBlock(pos.getX(), pos.getY(), pos.getZ(), state, hidden));
            }
            return Objects.requireNonNull(List.copyOf(blocks));
        }));
    }

    @Nonnull
    private static List<BakedQuad> quads(Minecraft mc, BlockState state) {
        return Objects.requireNonNull(QUADS.computeIfAbsent(state, ignored -> {
            var model = mc.getBlockRenderer().getBlockModel(state);
            var result = new ArrayList<BakedQuad>();
            for (var layer : model.getRenderTypes(state, Objects.requireNonNull(RandomSource.create(42)), Objects.requireNonNull(ModelData.EMPTY))) {
                // IForgeBakedModel declares this side as JetBrains @Nullable. ECJ
                // uses our JSR305 contract set, so isolate this verified API boundary.
                @SuppressWarnings("null")
                var unculled = model.getQuads(state, null, Objects.requireNonNull(RandomSource.create(42)), Objects.requireNonNull(ModelData.EMPTY), Objects.requireNonNull(layer));
                result.addAll(unculled);
                for (Direction face : Direction.values()) result.addAll(model.getQuads(state, Objects.requireNonNull(face), Objects.requireNonNull(RandomSource.create(42)), Objects.requireNonNull(ModelData.EMPTY), Objects.requireNonNull(layer)));
            }
            return Objects.requireNonNull(List.copyOf(result));
        }));
    }

    private record DesignKey(BlockPreset preset, int turns, boolean mirrored) {
        // Moving the cursor must not hash thousands of immutable block/property entries every frame.
        @Override public int hashCode() { return 31 * (31 * System.identityHashCode(preset) + turns) + (mirrored ? 1 : 0); }
        @Override public boolean equals(@Nullable Object value) {
            return value instanceof DesignKey other && preset == other.preset && turns == other.turns && mirrored == other.mirrored;
        }
    }
    private record PreparedBlock(int x, int y, int z, @Nonnull BlockState state, boolean[] hiddenFaces) {}
    public record Statistics(int missing, int matching, int conflicting, int unavailable, boolean complete) {}
}
