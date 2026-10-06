package dev.colony.camera.client.selection;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Camera-owned picking and local selections; never sends world actions. */
public final class BlockSelectionController {
    private static final long WORK_BUDGET_NANOS = 2_000_000;
    private static final int MAX_QUEUED_ACTIONS = 32;
    private final SelectionState state = new SelectionState();
    private final ArrayDeque<Action> actions = new ArrayDeque<>();
    private final Map<SelectionState.Cell, VoxelShape> specialShapes = new HashMap<>();
    private final Map<SelectionState.Cell, BlockState> renderedStates = new HashMap<>();
    private final Set<SelectionState.Cell> fullBlocks = new LinkedHashSet<>();
    private List<AABB> selectedBoxes = List.of();
    private Iterator<SelectionState.Cell> geometryBuild;
    private Iterator<SelectionState.Cell> validation;
    private FrameView currentView;
    private BlockHitResult hovered;
    private FaceSelection faceSelection;
    private boolean baseDragging;
    private boolean trackingBase;
    private boolean selectingHeight;
    private boolean faceValid;
    private boolean faceAdditive;
    private final HeightSelection heightPointer = new HeightSelection();
    private boolean volumeMode;
    private VolumeScan volumeScan;
    private SimilarTask similarScan;
    private boolean jobAdditive;
    private BlockHitResult selectedHit;
    private int revision;
    @Nonnull
    private Component status = emptyMessage();
    @Nonnull
    private Component description = emptyMessage();
    @Nonnull
    private Component selectedName = emptyMessage();
    @Nonnull
    private Component selectedCoordinates = emptyMessage();
    @Nullable
    private ClientLevel selectionLevel;
    @Nullable
    private LocalPlayer selectionPlayer;

    public BlockSelectionController() {
        updateDescription();
    }

    public void requestSelection(double x, double y) {
        enqueue(new Action(x, y, null, state.additive(), currentView, dev.colony.camera.config.SelectionConfig.similarLimit()));
    }

    public void beginFaceSelection(double x, double y) {
        if (!volumeMode) return;
        cancelBaseGesture();
        Minecraft mc = Minecraft.getInstance();
        if (currentView == null || mc.level == null || mc.player == null) {
            return;
        }
        BlockHitResult hit = pick(mc, currentView, x, y);
        // A ray that starts inside a shape has a synthetic hit point, not a real face.
        if (hit == null || hit.isInside()) {
            return;
        }
        FaceSelection.Face face = FaceSelection.Face.valueOf(hit.getDirection().name());
        faceSelection = new FaceSelection(cell(hit.getBlockPos()), face, coordinate(hit.getLocation(), face.axis()));
        baseDragging = true;
        trackingBase = false;
        faceValid = true;
        faceAdditive = state.additive();
        heightPointer.reset();
        status = emptyMessage();
        updateDescription();
    }

    public void updateFaceSelection(double x, double y) {
        if (!baseDragging || faceSelection == null) {
            return;
        }
        trackingBase = true;
        faceValid = false;
        if (currentView == null) {
            return;
        }
        CursorRay.Ray ray = CursorRay.unproject(x, y, 1, 1, currentView.inverse());
        if (ray == null) {
            return;
        }
        CursorRay.Point relative = ray.origin();
        CursorRay.Point origin = new CursorRay.Point(currentView.position().x + relative.x(),
                currentView.position().y + relative.y(), currentView.position().z + relative.z());
        int axis = faceSelection.face().axis();
        double normalDirection = coordinate(ray.direction(), axis);
        if (Math.abs(normalDirection) < 1.0e-8) {
            return;
        }
        double distance = (faceSelection.planeCoordinate() - coordinate(origin, axis)) / normalDirection;
        if (!Double.isFinite(distance) || distance < 0 || distance > currentView.range()) {
            return;
        }
        faceValid = faceSelection.update(origin, ray.direction());
    }

    public void finishFaceSelection(double x, double y) {
        if (!baseDragging || faceSelection == null) {
            setStatus("screen.colonycamera.selection.no_target");
            return;
        }
        updateFaceSelection(x, y);
        if (!faceValid) {
            cancelBaseGesture();
            setStatus("screen.colonycamera.selection.invalid_plane");
            return;
        }
        if (!faceSelection.bounds(1).withinLimit(SelectionState.MAX_BLOCKS)) {
            cancelBaseGesture();
            setStatus("screen.colonycamera.selection.area_limit", SelectionState.MAX_BLOCKS);
            return;
        }
        baseDragging = false;
        selectingHeight = true;
        heightPointer.begin(y * Minecraft.getInstance().getWindow().getScreenHeight());
        status = emptyMessage();
        updateDescription();
    }

    public boolean selectingHeight() {
        return selectingHeight;
    }

    public void updateHeightPointer(double y) {
        if (!selectingHeight || !Double.isFinite(y)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (heightPointer.move(y * mc.getWindow().getScreenHeight(), heightPixelsPerBlock(mc))) {
            status = emptyMessage();
            updateDescription();
        }
    }

    public void rebaseHeightPointer(double y) {
        if (selectingHeight) {
            heightPointer.rebase(y * Minecraft.getInstance().getWindow().getScreenHeight());
        }
    }

    private double heightPixelsPerBlock(Minecraft mc) {
        if (currentView == null || faceSelection == null) {
            return 24;
        }
        Vec3 base = heightAxisOrigin(faceSelection).subtract(currentView.position());
        Vec3 next = base.add(faceNormal(faceSelection.face()));
        Vector4f first = currentView.projectionView().transform(new Vector4f((float) base.x, (float) base.y, (float) base.z, 1));
        Vector4f second = currentView.projectionView().transform(new Vector4f((float) next.x, (float) next.y, (float) next.z, 1));
        if (first.w <= 1.0e-5F || second.w <= 1.0e-5F) {
            return 24;
        }
        double pixels = Math.hypot((second.x / second.w - first.x / first.w) * mc.getWindow().getScreenWidth() / 2,
                (second.y / second.w - first.y / first.w) * mc.getWindow().getScreenHeight() / 2);
        return Double.isFinite(pixels) && pixels > 0 ? pixels : 24;
    }

    public void confirmHeight() {
        if (!selectingHeight || faceSelection == null) {
            return;
        }
        SelectionState.Cuboid bounds = faceSelection.bounds(signedLayers());
        if (!bounds.withinLimit(SelectionState.MAX_BLOCKS)) {
            setStatus("screen.colonycamera.selection.volume_limit", SelectionState.MAX_BLOCKS);
            return;
        }
        if (!isRegionLoaded(Minecraft.getInstance(), bounds)) {
            setStatus("screen.colonycamera.selection.unloaded");
            return;
        }
        enqueue(new Action(0, 0, bounds, faceAdditive, null, 0));
        resetFaceGesture();
        updateDescription();
    }

    public void cancelBaseGesture() {
        if (baseDragging) {
            resetFaceGesture();
            updateDescription();
        }
    }

    private void resetFaceGesture() {
        faceSelection = null;
        baseDragging = false;
        trackingBase = false;
        selectingHeight = false;
        faceValid = false;
        heightPointer.reset();
    }

    private int signedLayers() {
        return heightPointer.signedLayers();
    }

    private void enqueue(Action action) {
        if (actions.size() == MAX_QUEUED_ACTIONS) {
            setStatus("screen.colonycamera.selection.queue_full");
        } else {
            actions.addLast(action);
        }
    }

    /** Ends a camera session, including its temporary selection modes. */
    public void clear() {
        cancelPointerInput();
        selectionLevel = null;
        selectionPlayer = null;
        currentView = null;
        state.reset();
        volumeMode = false;
        selectedHit = null;
        resetGeometry();
        status = emptyMessage();
        updateDescription();
    }

    /** Cancels uncommitted work while retaining the player's existing selection. */
    public void cancelPointerInput() {
        boolean unfinished = !actions.isEmpty() || volumeScan != null || similarScan != null || faceSelection != null;
        actions.clear();
        volumeScan = null;
        similarScan = null;
        hovered = null;
        resetFaceGesture();
        if (unfinished) {
            setStatus("screen.colonycamera.selection.cancelled");
        }
    }

    public void clearSelection() {
        cancelPointerInput();
        state.clear();
        selectedHit = null;
        resetGeometry();
        status = emptyMessage();
        updateDescription();
    }

    public void toggleAdditive() {
        state.toggleAdditive();
        updateDescription();
    }

    public boolean additive() {
        return state.additive();
    }

    public void toggleVolumeMode() {
        cancelPointerInput();
        volumeMode = !volumeMode;
        status = emptyMessage();
        updateDescription();
    }

    public boolean volumeMode() {
        return volumeMode;
    }

    public int revision() {
        return revision;
    }

    @Nonnull
    public Component description() {
        return description;
    }

    @Nonnull
    public Component statusMessage() {
        return status;
    }

    @Nonnull
    public Component selectedName() {
        return selectedName;
    }

    @Nonnull
    public Component selectedCoordinates() {
        return selectedCoordinates;
    }

    public int selectionCount() {
        return state.size();
    }

    public boolean working() {
        return !actions.isEmpty() || similarScan != null || volumeScan != null;
    }

    public int heightLayers() {
        return Math.abs(heightPointer.offset()) + 1;
    }

    public boolean heightOutward() {
        return heightPointer.offset() >= 0;
    }

    @Nullable
    public BlockHitResult selectedHit() {
        return selectedHit;
    }

    public Set<SelectionState.Cell> selectedCells() {
        return state.cells();
    }

    /** Shares the actual strategy-camera ray with local projection placement. */
    @Nullable
    public BlockHitResult hitAt(double x, double y) {
        return pick(Minecraft.getInstance(), currentView, x, y);
    }

    public void render(Minecraft mc, RenderLevelStageEvent event, boolean pointerBlocked) {
        WorldView world = worldView(mc);
        if (world == null) {
            clear();
            return;
        }
        if (selectionLevel != world.level() || selectionPlayer != world.player()) {
            clear();
            selectionLevel = world.level();
            selectionPlayer = world.player();
        }
        // AFTER_BLOCK_ENTITIES supplies the world view. AFTER_LEVEL's pose and
        // Camera's cached look vector do not reflect this camera in the same way.
        Matrix4f inverse = new Matrix4f(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose());
        float determinant = inverse.determinant();
        if (Float.isFinite(determinant) && Math.abs(determinant) > 1.0e-8F) {
            currentView = new FrameView(Objects.requireNonNull(new Matrix4f(inverse).invert()), inverse, Objects.requireNonNull(event.getCamera().getPosition()),
                    Math.min(1024, Math.max(128, mc.options.getEffectiveRenderDistance() * 32.0)));
            if (mc.isWindowActive()) {
                hovered = pointerBlocked ? null : pick(world, currentView, mc.mouseHandler.xpos() / mc.getWindow().getScreenWidth(),
                        mc.mouseHandler.ypos() / mc.getWindow().getScreenHeight());
                if (baseDragging && trackingBase && !pointerBlocked) {
                    updateFaceSelection(mc.mouseHandler.xpos() / mc.getWindow().getScreenWidth(),
                            mc.mouseHandler.ypos() / mc.getWindow().getScreenHeight());
                }
                processActions(world);
                advanceJob(world);
            } else {
                cancelPointerInput();
            }
        } else {
            hovered = null;
            currentView = null;
            if (baseDragging) {
                faceValid = false;
            }
        }
        validateSelection(world);
        advanceGeometry(world);
        drawSelection(mc, world, event);
    }

    private void processActions(WorldView world) {
        int processed = 0;
        while (volumeScan == null && similarScan == null && !actions.isEmpty() && processed++ < 8) {
            Action action = actions.removeFirst();
            if (action.bounds() != null) {
                if (!action.bounds().withinLimit(SelectionState.MAX_BLOCKS)) {
                    setStatus("screen.colonycamera.selection.volume_limit", SelectionState.MAX_BLOCKS);
                    continue;
                }
                if (!isRegionLoaded(world, action.bounds())) {
                    setStatus("screen.colonycamera.selection.unloaded");
                    continue;
                }
                volumeScan = new VolumeScan(action.bounds());
                jobAdditive = action.additive();
                setStatus("screen.colonycamera.selection.working");
            } else {
                FrameView view = action.view() == null ? currentView : action.view();
                BlockHitResult hit = pick(world, view, action.x(), action.y());
                if (hit == null) {
                    if (commit(List.of(), action.additive())) selectedHit = null;
                    continue;
                }
                similarScan = new SimilarTask(world, hit, action.similarLimit());
                jobAdditive = action.additive();
                setStatus("screen.colonycamera.selection.working");
            }
        }
    }

    private void advanceJob(WorldView world) {
        if (similarScan != null) {
            SimilarTask task = similarScan;
            long started = System.nanoTime();
            for (int checked = 0; checked < 2048 && !task.job.done()
                    && System.nanoTime() - started < WORK_BUDGET_NANOS; checked++) task.job.step();
            if (task.job.done()) {
                similarScan = null;
                if (task.job.failure() == SimilarSelectionJob.Failure.UNAVAILABLE || !task.dependenciesLoaded()) {
                    setStatus("screen.colonycamera.selection.unloaded");
                    return;
                }
                if (task.job.failure() == SimilarSelectionJob.Failure.CHANGED) {
                    setStatus("screen.colonycamera.selection.changed");
                    return;
                }
                if (commit(task.job.cells(), jobAdditive)) {
                    selectedHit = state.size() == 1 ? task.hit : null;
                    if (task.job.limitReached()) setStatus("screen.colonycamera.selection.similar_limit", task.limit);
                }
            }
            return;
        }
        if (volumeScan != null) {
            long started = System.nanoTime();
            int checked = 0;
            while (volumeScan.iterator.hasNext() && checked++ < 256
                    && System.nanoTime() - started < WORK_BUDGET_NANOS) {
                SelectionState.Cell c = volumeScan.iterator.next();
                BlockPos position = pos(c);
                if (!world.level().isLoaded(position)
                        || !world.level().getWorldBorder().isWithinBounds(position)) {
                    volumeScan = null;
                    setStatus("screen.colonycamera.selection.unloaded");
                    return;
                }
                if (!world.level().getBlockState(position).isAir()) volumeScan.cells.add(c);
            }
            if (!volumeScan.iterator.hasNext()) {
                VolumeScan finished = volumeScan;
                volumeScan = null;
                if (!isRegionLoaded(world, finished.bounds)) {
                    setStatus("screen.colonycamera.selection.unloaded");
                    return;
                }
                finished.cells.removeIf(c -> !isSelectable(world, c));
                if (commit(finished.cells, jobAdditive)) selectedHit = null;
            }
        }
    }

    private boolean commit(java.util.Collection<SelectionState.Cell> cells, boolean append) {
        SelectionState.ApplyResult result = state.apply(cells, append);
        if (!result.accepted()) {
            setStatus("screen.colonycamera.selection.block_limit", SelectionState.MAX_BLOCKS);
            return false;
        }
        if (result.changed()) {
            beginGeometry();
        }
        status = emptyMessage();
        updateDescription();
        return true;
    }

    private static boolean isRegionLoaded(Minecraft mc, SelectionState.Cuboid bounds) {
        WorldView world = worldView(mc);
        return world != null && isRegionLoaded(world, bounds);
    }

    private static boolean isRegionLoaded(WorldView world, SelectionState.Cuboid bounds) {
        if (bounds.minY() < world.level().getMinBuildHeight() || bounds.maxY() >= world.level().getMaxBuildHeight()
                || !world.level().getWorldBorder().isWithinBounds(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()))
                || !world.level().getWorldBorder().isWithinBounds(new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()))) {
            return false;
        }
        for (int chunkX = bounds.minX() >> 4; chunkX <= (bounds.maxX() >> 4); chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= (bounds.maxZ() >> 4); chunkZ++) {
                if (!world.level().getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSelectable(WorldView world, SelectionState.Cell cell) {
        BlockPos position = pos(cell);
        return world.level().isLoaded(position) && !world.level().getBlockState(position).isAir()
                && world.level().getWorldBorder().isWithinBounds(position);
    }

    private void validateSelection(WorldView world) {
        if (geometryBuild != null || state.size() == 0) {
            return;
        }
        if (validation == null || !validation.hasNext()) {
            validation = state.cells().iterator();
        }
        Set<SelectionState.Cell> invalid = new HashSet<>();
        boolean shapeChanged = false;
        for (int checked = 0; checked < 64 && validation.hasNext(); checked++) {
            SelectionState.Cell c = validation.next();
            if (!isSelectable(world, c)) {
                invalid.add(c);
            } else if (renderedStates.get(c) != world.level().getBlockState(pos(c))) {
                shapeChanged = true;
            }
        }
        if (!invalid.isEmpty()) {
            state.removeIf(invalid::contains);
            selectedHit = null;
            beginGeometry();
            updateDescription();
        } else if (shapeChanged) {
            beginGeometry();
            updateDescription();
        }
    }

    private void resetGeometry() {
        selectedBoxes = List.of();
        specialShapes.clear();
        renderedStates.clear();
        fullBlocks.clear();
        geometryBuild = null;
        validation = null;
    }

    private void beginGeometry() {
        resetGeometry();
        geometryBuild = state.cells().iterator();
    }

    private void advanceGeometry(WorldView world) {
        if (geometryBuild == null) {
            return;
        }
        long started = System.nanoTime();
        for (int count = 0; count < 256 && geometryBuild.hasNext()
                && System.nanoTime() - started < 1_000_000; count++) {
            SelectionState.Cell c = geometryBuild.next();
            BlockPos position = pos(c);
            BlockState block = world.level().getBlockState(position);
            renderedStates.put(c, block);
            VoxelShape shape = Objects.requireNonNull(block.getShape(world.level(), position, world.collision()));
            if (Block.isShapeFullBlock(shape)) {
                fullBlocks.add(c);
            } else if (!shape.isEmpty()) {
                specialShapes.put(c, shape);
            }
        }
        if (!geometryBuild.hasNext()) {
            selectedBoxes = SelectionBoxes.merge(fullBlocks).stream().map(BlockSelectionController::box).toList();
            geometryBuild = null;
        }
    }

    private void updateDescription() {
        Component mode = message(volumeMode
                ? "screen.colonycamera.selection.mode.volume" : "screen.colonycamera.selection.mode.similar");
        Component append = message(state.additive()
                ? "screen.colonycamera.selection.add.on" : "screen.colonycamera.selection.add.off");
        description = message("screen.colonycamera.selection.summary", state.size(), mode, append);
        selectedName = emptyMessage();
        selectedCoordinates = emptyMessage();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (state.size() == 1 && level != null) {
            SelectionState.Cell c = state.cells().iterator().next();
            selectedName = Objects.requireNonNull(level.getBlockState(pos(c)).getBlock().getName());
            selectedCoordinates = message("screen.colonycamera.ui.coordinates", c.x(), c.y(), c.z());
            description = Objects.requireNonNull(description.copy().append(" | ").append(message("screen.colonycamera.selection.single",
                    selectedName, c.x(), c.y(), c.z())));
        }
        if (selectingHeight) {
            description = Objects.requireNonNull(description.copy().append(" | ").append(message("screen.colonycamera.selection.height",
                    Math.abs(heightPointer.offset()) + 1, message(heightPointer.offset() >= 0
                            ? "screen.colonycamera.selection.outward" : "screen.colonycamera.selection.inward"))));
        } else if (volumeMode && volumeScan == null && similarScan == null) {
            description = Objects.requireNonNull(description.copy().append(" | ").append(message("screen.colonycamera.selection.base")));
        }
        if (!status.getString().isEmpty()) {
            description = Objects.requireNonNull(description.copy().append(" | ").append(status));
        }
        revision++;
    }

    private void setStatus(@Nonnull String key, @Nonnull Object... arguments) {
        status = message(key, arguments);
        updateDescription();
    }

    // JDT's automatic mode recognizes JSR-305 but not Minecraft's return defaults.
    @Nonnull
    private static Component message(@Nonnull String key, @Nonnull Object... arguments) {
        return Objects.requireNonNull(Component.translatable(key, arguments));
    }

    @Nonnull
    private static Component emptyMessage() {
        return Objects.requireNonNull(Component.empty());
    }

    @Nullable
    private static BlockHitResult pick(Minecraft mc, @Nullable FrameView view, double x, double y) {
        WorldView world = worldView(mc);
        return world == null ? null : pick(world, view, x, y);
    }

    @Nullable
    private static BlockHitResult pick(WorldView world, @Nullable FrameView view, double x, double y) {
        if (view == null) {
            return null;
        }
        CursorRay.Ray ray = CursorRay.unproject(x, y, 1, 1, view.inverse());
        if (ray == null) {
            return null;
        }
        CursorRay.Point origin = ray.origin();
        CursorRay.Point direction = ray.direction();
        Vec3 start = Objects.requireNonNull(view.position().add(origin.x(), origin.y(), origin.z()));
        Vec3 end = Objects.requireNonNull(start.add(direction.x() * view.range(), direction.y() * view.range(), direction.z() * view.range()));
        BlockHitResult hit = world.level().clip(new ClipContext(start, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, world.player()));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos position = Objects.requireNonNull(hit.getBlockPos());
        return world.level().isLoaded(position) && world.level().getWorldBorder().isWithinBounds(position) ? hit : null;
    }

    private void drawSelection(Minecraft mc, WorldView world, RenderLevelStageEvent event) {
        if (hovered == null && state.size() == 0 && faceSelection == null && similarScan == null) {
            return;
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType lineType = Objects.requireNonNull(RenderType.lines());
        VertexConsumer lines = Objects.requireNonNull(buffers.getBuffer(lineType));
        PoseStack poses = Objects.requireNonNull(event.getPoseStack());
        Vec3 camera = event.getCamera().getPosition();
        for (AABB bounds : selectedBoxes) {
            if (event.getFrustum().isVisible(Objects.requireNonNull(bounds))) {
                LevelRenderer.renderLineBox(poses, lines, Objects.requireNonNull(bounds.move(-camera.x, -camera.y, -camera.z)), 1, 0.8F, 0.1F, 0.9F);
            }
        }
        // During rebuilding, draw the completed prefix as compact boxes too.
        if (geometryBuild != null && state.size() <= 256) {
            for (SelectionState.Cell c : fullBlocks) {
                drawBox(event, lines, new SelectionState.Cuboid(c, c), 1, 0.8F, 0.1F);
            }
        }
        for (var entry : specialShapes.entrySet()) {
            BlockPos position = pos(entry.getKey());
            if (event.getFrustum().isVisible(new AABB(position))) {
                drawShape(event, lines, position, entry.getValue(), 1, 0.8F, 0.1F);
            }
        }
        if (faceSelection != null) {
            SelectionState.Cuboid preview = faceSelection.bounds(selectingHeight ? signedLayers() : 1);
            boolean allowed = faceValid && preview.withinLimit(SelectionState.MAX_BLOCKS) && isRegionLoaded(world, preview);
            float red = allowed ? 0.2F : 1.0F;
            float green = allowed ? 0.9F : 0.2F;
            float blue = allowed ? 1.0F : 0.2F;
            drawFace(event, lines, faceSelection, red, green, blue);

            if (selectingHeight) {
                drawBox(event, lines, preview, red, green, blue);
                drawHeightAxis(event, lines, faceSelection, heightPointer.offset());
            }
        }

        if (hovered != null && !state.contains(cell(hovered.getBlockPos()))) {
            BlockPos position = Objects.requireNonNull(hovered.getBlockPos());
            drawShape(event, lines, position, world.level().getBlockState(position)
                    .getShape(world.level(), position, world.collision()), 1, 1, 1);
        }
        buffers.endBatch(lineType);
    }

    private static void drawBox(RenderLevelStageEvent event, @Nonnull VertexConsumer lines, SelectionState.Cuboid bounds,
                                float red, float green, float blue) {
        Vec3 camera = event.getCamera().getPosition();
        LevelRenderer.renderLineBox(Objects.requireNonNull(event.getPoseStack()), lines,
                Objects.requireNonNull(box(bounds).move(-camera.x, -camera.y, -camera.z)),
                red, green, blue, 0.9F);
    }

    @Nonnull
    private static Vec3 heightAxisOrigin(FaceSelection face) {
        SelectionState.Cell endpoint = face.endpoint();
        int axis = face.face().axis();
        return new Vec3(axis == 0 ? face.planeCoordinate() : endpoint.x() + 0.5,
                axis == 1 ? face.planeCoordinate() : endpoint.y() + 0.5,
                axis == 2 ? face.planeCoordinate() : endpoint.z() + 0.5);
    }

    @Nonnull
    private static Vec3 faceNormal(FaceSelection.Face face) {
        return new Vec3(face.axis() == 0 ? face.sign() : 0, face.axis() == 1 ? face.sign() : 0,
                face.axis() == 2 ? face.sign() : 0);
    }

    private static void drawHeightAxis(RenderLevelStageEvent event, @Nonnull VertexConsumer lines, FaceSelection face, int offset) {
        Vec3 origin = heightAxisOrigin(face);
        Vec3 normal = faceNormal(face.face());
        double extent = Math.max(4, Math.min(96, Math.abs(offset) + 1));
        drawWorldLine(event, lines, origin, offset(origin, normal, extent), 0.2F, 1, 0.4F);
        drawWorldLine(event, lines, origin, offset(origin, normal, -extent), 1, 0.4F, 0.4F);
        Vec3 marker = offset(origin, normal, offset);
        for (int axis = 0; axis < 3; axis++) {
            if (axis != face.face().axis()) {
                Vec3 tangent = new Vec3(axis == 0 ? 0.25 : 0, axis == 1 ? 0.25 : 0, axis == 2 ? 0.25 : 0);
                drawWorldLine(event, lines, offset(marker, tangent, -1), offset(marker, tangent, 1), 1, 0.9F, 0.2F);
            }
        }
    }

    @Nonnull
    private static Vec3 offset(@Nonnull Vec3 origin, @Nonnull Vec3 direction, double distance) {
        return new Vec3(origin.x + direction.x * distance, origin.y + direction.y * distance,
                origin.z + direction.z * distance);
    }

    private static void drawWorldLine(RenderLevelStageEvent event, @Nonnull VertexConsumer lines, @Nonnull Vec3 from, @Nonnull Vec3 to,
                                      float red, float green, float blue) {
        Vec3 direction = to.subtract(from).normalize();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack.Pose pose = event.getPoseStack().last();
        Matrix4f matrix = Objects.requireNonNull(pose.pose());
        Matrix3f normals = Objects.requireNonNull(pose.normal());
        for (Vec3 point : List.of(from, to)) {
            lines.vertex(matrix, (float) (point.x - camera.x), (float) (point.y - camera.y), (float) (point.z - camera.z))
                    .color(red, green, blue, 0.9F)
                    .normal(normals, (float) direction.x, (float) direction.y, (float) direction.z).endVertex();
        }
    }

    private static void drawFace(RenderLevelStageEvent event, @Nonnull VertexConsumer lines, FaceSelection face,
                                 float red, float green, float blue) {
        List<CursorRay.Point> corners = face.corners();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack.Pose pose = event.getPoseStack().last();
        Matrix4f matrix = Objects.requireNonNull(pose.pose());
        Matrix3f normals = Objects.requireNonNull(pose.normal());
        double offset = face.face().sign() * 0.002;
        int axis = face.face().axis();
        for (int index = 0; index < corners.size(); index++) {
            CursorRay.Point from = corners.get(index);
            CursorRay.Point to = corners.get((index + 1) % corners.size());
            double length = Math.sqrt(Math.pow(to.x() - from.x(), 2) + Math.pow(to.y() - from.y(), 2)
                    + Math.pow(to.z() - from.z(), 2));
            float nx = (float) ((to.x() - from.x()) / length);
            float ny = (float) ((to.y() - from.y()) / length);
            float nz = (float) ((to.z() - from.z()) / length);
            for (CursorRay.Point point : List.of(from, to)) {
                lines.vertex(matrix, (float) (point.x() - camera.x + (axis == 0 ? offset : 0)),
                        (float) (point.y() - camera.y + (axis == 1 ? offset : 0)),
                        (float) (point.z() - camera.z + (axis == 2 ? offset : 0)))
                        .color(red, green, blue, 0.9F).normal(normals, nx, ny, nz).endVertex();
            }
        }
    }

    @Nonnull
    private static AABB box(SelectionState.Cuboid bounds) {
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                (double) bounds.maxX() + 1, (double) bounds.maxY() + 1, (double) bounds.maxZ() + 1);
    }

    private static void drawShape(RenderLevelStageEvent event, @Nonnull VertexConsumer lines, BlockPos position,
                                  VoxelShape shape, float red, float green, float blue) {
        PoseStack poses = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        poses.pushPose();
        try {
            poses.translate(position.getX() - camera.x, position.getY() - camera.y, position.getZ() - camera.z);
            PoseStack.Pose pose = poses.last();
            Matrix4f matrix = Objects.requireNonNull(pose.pose());
            Matrix3f normals = Objects.requireNonNull(pose.normal());
            shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
                double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
                if (length == 0) {
                    return;
                }
                float normalX = (float) ((x2 - x1) / length);
                float normalY = (float) ((y2 - y1) / length);
                float normalZ = (float) ((z2 - z1) / length);
                lines.vertex(matrix, (float) x1, (float) y1, (float) z1).color(red, green, blue, 0.9F)
                        .normal(normals, normalX, normalY, normalZ).endVertex();
                lines.vertex(matrix, (float) x2, (float) y2, (float) z2).color(red, green, blue, 0.9F)
                        .normal(normals, normalX, normalY, normalZ).endVertex();
            });
        } finally {
            poses.popPose();
        }
    }

    @Nonnull
    private static SelectionState.Cell cell(BlockPos position) {
        return new SelectionState.Cell(position.getX(), position.getY(), position.getZ());
    }

    @Nonnull
    private static BlockPos pos(SelectionState.Cell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    /** Keeps all queries in a frame or queued selection scan on one checked world. */
    private record WorldView(@Nonnull ClientLevel level, @Nonnull LocalPlayer player,
                             @Nonnull CollisionContext collision) {}

    @Nullable
    private static WorldView worldView(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return null;
        }
        return new WorldView(level, player, Objects.requireNonNull(CollisionContext.of(player)));
    }

    private record FrameView(@Nonnull Matrix4f inverse, @Nonnull Matrix4f projectionView,
                             @Nonnull Vec3 position, double range) {}
    private static double coordinate(Vec3 point, int axis) {
        return axis == 0 ? point.x : axis == 1 ? point.y : point.z;
    }

    private static double coordinate(CursorRay.Point point, int axis) {
        return axis == 0 ? point.x() : axis == 1 ? point.y() : point.z();
    }

    private record Action(double x, double y, SelectionState.Cuboid bounds, boolean additive, FrameView view,
                          int similarLimit) {}
    private static final class SimilarTask {
        private final WorldView world;
        private final BlockHitResult hit;
        private final int limit;
        private final Set<Long> chunks = new HashSet<>();
        private final SimilarSelectionJob job;

        private SimilarTask(WorldView world, BlockHitResult hit, int limit) {
            this.world = world;
            this.hit = hit;
            this.limit = limit;
            BlockPos seed = Objects.requireNonNull(hit.getBlockPos());
            Block target = Objects.requireNonNull(world.level().getBlockState(seed).getBlock());
            job = new SimilarSelectionJob(cell(seed), limit, candidate -> {
                BlockPos position = pos(candidate);
                if (position.getY() < world.level().getMinBuildHeight() || position.getY() >= world.level().getMaxBuildHeight()
                        || !world.level().getWorldBorder().isWithinBounds(position)) return SimilarSelectionJob.Sample.OTHER;
                int chunkX = position.getX() >> 4, chunkZ = position.getZ() >> 4;
                if (!world.level().getChunkSource().hasChunk(chunkX, chunkZ)) return SimilarSelectionJob.Sample.UNAVAILABLE;
                chunks.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
                return world.level().getBlockState(position).is(target) ? SimilarSelectionJob.Sample.MATCH : SimilarSelectionJob.Sample.OTHER;
            });
        }

        private boolean dependenciesLoaded() {
            for (Long chunk : chunks) {
                long value = Objects.requireNonNull(chunk);
                if (!world.level().getChunkSource().hasChunk((int) (value >> 32), (int) value)) return false;
            }
            return true;
        }
    }

    private static final class VolumeScan {
        private final SelectionState.Cuboid bounds;
        private final Iterator<SelectionState.Cell> iterator;
        private final Set<SelectionState.Cell> cells = new LinkedHashSet<>();

        private VolumeScan(SelectionState.Cuboid bounds) {
            this.bounds = bounds;
            this.iterator = bounds.cells().iterator();
        }
    }
}
