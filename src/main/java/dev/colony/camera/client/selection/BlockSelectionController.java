package dev.colony.camera.client.selection;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Camera-owned picking and local selections; never sends world actions. */
public final class BlockSelectionController {
    private static final long WORK_BUDGET_NANOS = 2_000_000;
    private static final int MAX_QUEUED_ACTIONS = 32;
    private static final int WALL_SEARCH_DISTANCE = 32;
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
    private boolean faceVolumeMode;
    private final HeightSelection heightPointer = new HeightSelection();
    private boolean volumeMode;
    private VolumeScan volumeScan;
    private SurfaceTask surfaceScan;
    private SurfaceTask surfacePreview;
    private boolean jobAdditive;
    private BlockHitResult selectedHit;
    private int revision;
    private Component status = Component.empty();
    private Component description = Component.empty();
    private Component selectedName = Component.empty();
    private Component selectedCoordinates = Component.empty();

    public BlockSelectionController() {
        updateDescription();
    }

    public void requestSelection(double x, double y) {
        enqueue(new Action(x, y, null, state.additive(), currentView, null));
    }

    public void beginFaceSelection(double x, double y) {
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
        faceVolumeMode = volumeMode;
        heightPointer.reset();
        status = Component.empty();
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
        if (!faceVolumeMode) {
            BlockHitResult hit = pick(Minecraft.getInstance(), currentView, x, y);
            if (hit != null && !hit.isInside()) {
                // Surface height changes must not shift the endpoint sideways
                // through projection onto the original reference plane.
                faceSelection.followSurface(cell(hit.getBlockPos()));
                faceValid = true;
                return;
            }
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
        if (faceVolumeMode) {
            selectingHeight = true;
            heightPointer.begin(y * Minecraft.getInstance().getWindow().getScreenHeight());
            status = Component.empty();
            updateDescription();
        } else {
            SelectionState.Cuboid footprint = faceSelection.bounds(1);
            // Preview results may predate a block placement or removal. Re-scan
            // from release so an old surface cannot be committed beneath a new one.
            SurfaceTask task = createSurfaceTask(Minecraft.getInstance(), footprint, faceSelection.face());
            if (task == null || !isRegionLoaded(Minecraft.getInstance(), task.dependencyBounds)) {
                resetFaceGesture();
                setStatus("screen.colonycamera.selection.unloaded");
                return;
            }
            enqueue(new Action(0, 0, footprint, faceAdditive, null, task));
            resetFaceGesture();
            updateDescription();
        }
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
            status = Component.empty();
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
        enqueue(new Action(0, 0, bounds, faceAdditive, null, null));
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
        surfacePreview = null;
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
        currentView = null;
        state.reset();
        volumeMode = false;
        selectedHit = null;
        resetGeometry();
        status = Component.empty();
        updateDescription();
    }

    /** Cancels uncommitted work while retaining the player's existing selection. */
    public void cancelPointerInput() {
        boolean unfinished = !actions.isEmpty() || volumeScan != null || surfaceScan != null || faceSelection != null;
        actions.clear();
        volumeScan = null;
        surfaceScan = null;
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
        status = Component.empty();
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
        status = Component.empty();
        updateDescription();
    }

    public boolean volumeMode() {
        return volumeMode;
    }

    public int revision() {
        return revision;
    }

    public Component description() {
        return description;
    }

    public Component statusMessage() {
        return status;
    }

    public Component selectedName() {
        return selectedName;
    }

    public Component selectedCoordinates() {
        return selectedCoordinates;
    }

    public int selectionCount() {
        return state.size();
    }

    public boolean working() {
        return !actions.isEmpty() || surfaceScan != null || volumeScan != null;
    }

    public int heightLayers() {
        return Math.abs(heightPointer.offset()) + 1;
    }

    public boolean heightOutward() {
        return heightPointer.offset() >= 0;
    }

    public BlockHitResult selectedHit() {
        return selectedHit;
    }

    public Set<SelectionState.Cell> selectedCells() {
        return state.cells();
    }

    public void render(Minecraft mc, RenderLevelStageEvent event, boolean pointerBlocked) {
        if (mc.level == null || mc.player == null) {
            clear();
            return;
        }
        // AFTER_BLOCK_ENTITIES supplies the world view. AFTER_LEVEL's pose and
        // Camera's cached look vector do not reflect this camera in the same way.
        Matrix4f inverse = new Matrix4f(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose());
        float determinant = inverse.determinant();
        if (Float.isFinite(determinant) && Math.abs(determinant) > 1.0e-8F) {
            currentView = new FrameView(new Matrix4f(inverse).invert(), inverse, event.getCamera().getPosition(),
                    Math.min(1024, Math.max(128, mc.options.getEffectiveRenderDistance() * 32.0)));
            if (mc.isWindowActive()) {
                hovered = pointerBlocked ? null : pick(mc, currentView, mc.mouseHandler.xpos() / mc.getWindow().getScreenWidth(),
                        mc.mouseHandler.ypos() / mc.getWindow().getScreenHeight());
                if (baseDragging && trackingBase && !pointerBlocked) {
                    updateFaceSelection(mc.mouseHandler.xpos() / mc.getWindow().getScreenWidth(),
                            mc.mouseHandler.ypos() / mc.getWindow().getScreenHeight());
                }
                processActions(mc);
                advanceJob(mc);
                advanceSurfacePreview(mc);
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
        validateSelection(mc);
        advanceGeometry(mc);
        drawSelection(mc, event);
    }

    private void processActions(Minecraft mc) {
        int processed = 0;
        while (volumeScan == null && surfaceScan == null && !actions.isEmpty() && processed++ < 8) {
            Action action = actions.removeFirst();
            if (action.bounds() != null) {
                if (!action.bounds().withinLimit(SelectionState.MAX_BLOCKS)) {
                    setStatus("screen.colonycamera.selection.volume_limit", SelectionState.MAX_BLOCKS);
                    continue;
                }
                if (!isRegionLoaded(mc, action.surface() == null ? action.bounds() : action.surface().dependencyBounds)) {
                    setStatus("screen.colonycamera.selection.unloaded");
                    continue;
                }
                if (action.surface() == null) {
                    volumeScan = new VolumeScan(action.bounds());
                } else {
                    surfaceScan = action.surface();
                }
                jobAdditive = action.additive();
                setStatus("screen.colonycamera.selection.working");
            } else {
                FrameView view = action.view() == null ? currentView : action.view();
                BlockHitResult hit = pick(mc, view, action.x(), action.y());
                if (commit(hit == null ? List.of() : List.of(cell(hit.getBlockPos())), action.additive())) {
                    selectedHit = state.size() == 1 ? hit : null;
                }
            }
        }
    }

    private void advanceJob(Minecraft mc) {
        if (surfaceScan != null) {
            advanceSurface(surfaceScan);
            if (surfaceScan.job.done()) {
                SurfaceTask finished = surfaceScan;
                surfaceScan = null;
                if (finished.job.failed() || !isRegionLoaded(mc, finished.dependencyBounds)) {
                    setStatus("screen.colonycamera.selection.unloaded");
                    return;
                }
                Set<SelectionState.Cell> cells = new LinkedHashSet<>(finished.job.cells());
                cells.removeIf(c -> !isSurface(mc, c));
                if (commit(cells, jobAdditive)) {
                    selectedHit = null;
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
                if (!mc.level.isLoaded(position)
                        || !mc.level.getWorldBorder().isWithinBounds(position)) {
                    volumeScan = null;
                    setStatus("screen.colonycamera.selection.unloaded");
                    return;
                }
                if (!mc.level.getBlockState(position).isAir()) {
                    volumeScan.cells.add(c);
                }
            }
            if (!volumeScan.iterator.hasNext()) {
                VolumeScan finished = volumeScan;
                volumeScan = null;
                // A chunk scanned on an earlier frame may have unloaded before completion.
                if (!isRegionLoaded(mc, finished.bounds)) {
                    setStatus("screen.colonycamera.selection.unloaded");
                    return;
                }
                finished.cells.removeIf(c -> !isSelectable(mc, c));
                if (commit(finished.cells, jobAdditive)) {
                    selectedHit = null;
                }
            }
        }
    }

    private void advanceSurfacePreview(Minecraft mc) {
        if (!baseDragging || faceVolumeMode || !faceValid || faceSelection == null) {
            surfacePreview = null;
            return;
        }
        SelectionState.Cuboid footprint = faceSelection.bounds(1);
        if (!footprint.withinLimit(SelectionState.MAX_BLOCKS)) {
            surfacePreview = null;
            return;
        }
        if (surfacePreview != null && !surfacePreview.footprint.equals(footprint)) {
            surfacePreview = null;
        }
        // Completed selections take priority over a changing drag preview.
        if (surfaceScan != null || volumeScan != null) {
            return;
        }
        if (surfacePreview == null) {
            surfacePreview = createSurfaceTask(mc, footprint, faceSelection.face());
        }
        if (surfacePreview != null) {
            advanceSurface(surfacePreview);
        }
    }

    private static void advanceSurface(SurfaceTask task) {
        long started = System.nanoTime();
        for (int checked = 0; checked < 2048 && !task.job.done()
                && System.nanoTime() - started < WORK_BUDGET_NANOS; checked++) {
            task.job.step();
        }
    }

    private static SurfaceTask createSurfaceTask(Minecraft mc, SelectionState.Cuboid footprint, FaceSelection.Face face) {
        if (!isRegionLoaded(mc, footprint)) {
            return null;
        }
        int axis = face.axis();
        SurfaceSelectionJob.NormalRange range;
        if (axis == 1) {
            range = new SurfaceSelectionJob.NormalRange(mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight() - 1);
        } else {
            int anchor = coordinate(footprint.first(), axis);
            int minimum = anchor;
            int maximum = anchor;
            // Each added layer must be loaded for every column. The shared window
            // stops at its first unavailable edge and is then frozen for this task.
            for (int offset = 1; offset <= WALL_SEARCH_DISTANCE; offset++) {
                if (!isRegionLoaded(mc, withNormalRange(footprint, axis, anchor - offset, anchor - offset))) {
                    break;
                }
                minimum = anchor - offset;
            }
            for (int offset = 1; offset <= WALL_SEARCH_DISTANCE; offset++) {
                if (!isRegionLoaded(mc, withNormalRange(footprint, axis, anchor + offset, anchor + offset))) {
                    break;
                }
                maximum = anchor + offset;
            }
            range = new SurfaceSelectionJob.NormalRange(minimum, maximum);
        }
        return new SurfaceTask(mc, footprint, face, range);
    }

    private static SelectionState.Cuboid withNormalRange(SelectionState.Cuboid footprint, int axis, int minimum, int maximum) {
        return new SelectionState.Cuboid(withCoordinate(footprint.first(), axis, minimum),
                withCoordinate(footprint.second(), axis, maximum));
    }

    private static SelectionState.Cell withCoordinate(SelectionState.Cell cell, int axis, int coordinate) {
        return new SelectionState.Cell(axis == 0 ? coordinate : cell.x(), axis == 1 ? coordinate : cell.y(),
                axis == 2 ? coordinate : cell.z());
    }

    private static SurfaceSelectionJob.NormalRange occupiedRange(Minecraft mc, SelectionState.Cell column,
                                                                  FaceSelection.Face face, SurfaceSelectionJob.NormalRange search) {
        BlockPos position = pos(column);
        if (!mc.level.isLoaded(position) || face.axis() != 1) {
            // An unloaded column is probed as UNAVAILABLE, never optimized to empty.
            return search;
        }
        int top = Math.min(search.max(), mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, column.x(), column.z()) - 1);
        if (top < search.min()) {
            return null;
        }
        int bottom = search.min();
        if (face == FaceSelection.Face.DOWN) {
            var chunk = mc.level.getChunk(column.x() >> 4, column.z() >> 4);
            var sections = chunk.getSections();
            for (int section = 0; section < sections.length; section++) {
                if (!sections[section].hasOnlyAir()) {
                    bottom = Math.max(bottom, chunk.getSectionYFromSectionIndex(section) * 16);
                    break;
                }
            }
        }
        return bottom > top ? null : new SurfaceSelectionJob.NormalRange(bottom, top);
    }

    private static boolean isSurface(Minecraft mc, SelectionState.Cell cell) {
        BlockPos position = pos(cell);
        if (!mc.level.isLoaded(position) || !mc.level.getWorldBorder().isWithinBounds(position)) {
            return false;
        }
        BlockState block = mc.level.getBlockState(position);
        return !block.isAir() && !block.getShape(mc.level, position, CollisionContext.of(mc.player)).isEmpty();
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
        status = Component.empty();
        updateDescription();
        return true;
    }

    private static boolean isRegionLoaded(Minecraft mc, SelectionState.Cuboid bounds) {
        if (bounds.minY() < mc.level.getMinBuildHeight() || bounds.maxY() >= mc.level.getMaxBuildHeight()
                || !mc.level.getWorldBorder().isWithinBounds(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()))
                || !mc.level.getWorldBorder().isWithinBounds(new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()))) {
            return false;
        }
        for (int chunkX = bounds.minX() >> 4; chunkX <= (bounds.maxX() >> 4); chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= (bounds.maxZ() >> 4); chunkZ++) {
                if (!mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isSelectable(Minecraft mc, SelectionState.Cell cell) {
        BlockPos position = pos(cell);
        return mc.level.isLoaded(position) && !mc.level.getBlockState(position).isAir()
                && mc.level.getWorldBorder().isWithinBounds(position);
    }

    private void validateSelection(Minecraft mc) {
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
            if (!isSelectable(mc, c)) {
                invalid.add(c);
            } else if (renderedStates.get(c) != mc.level.getBlockState(pos(c))) {
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

    private void advanceGeometry(Minecraft mc) {
        if (geometryBuild == null) {
            return;
        }
        long started = System.nanoTime();
        for (int count = 0; count < 256 && geometryBuild.hasNext()
                && System.nanoTime() - started < 1_000_000; count++) {
            SelectionState.Cell c = geometryBuild.next();
            BlockPos position = pos(c);
            BlockState block = mc.level.getBlockState(position);
            renderedStates.put(c, block);
            VoxelShape shape = block.getShape(mc.level, position, CollisionContext.of(mc.player));
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
        Component mode = Component.translatable(volumeMode
                ? "screen.colonycamera.selection.mode.volume" : "screen.colonycamera.selection.mode.surface");
        Component append = Component.translatable(state.additive()
                ? "screen.colonycamera.selection.add.on" : "screen.colonycamera.selection.add.off");
        description = Component.translatable("screen.colonycamera.selection.summary", state.size(), mode, append);
        selectedName = Component.empty();
        selectedCoordinates = Component.empty();
        Minecraft mc = Minecraft.getInstance();
        if (state.size() == 1 && mc.level != null) {
            SelectionState.Cell c = state.cells().iterator().next();
            selectedName = mc.level.getBlockState(pos(c)).getBlock().getName();
            selectedCoordinates = Component.translatable("screen.colonycamera.ui.coordinates", c.x(), c.y(), c.z());
            description = description.copy().append(" | ").append(Component.translatable("screen.colonycamera.selection.single",
                    selectedName, c.x(), c.y(), c.z()));
        }
        if (selectingHeight) {
            description = description.copy().append(" | ").append(Component.translatable("screen.colonycamera.selection.height",
                    Math.abs(heightPointer.offset()) + 1, Component.translatable(heightPointer.offset() >= 0
                            ? "screen.colonycamera.selection.outward" : "screen.colonycamera.selection.inward")));
        } else if (volumeMode && volumeScan == null && surfaceScan == null) {
            description = description.copy().append(" | ").append(Component.translatable("screen.colonycamera.selection.base"));
        }
        if (!status.getString().isEmpty()) {
            description = description.copy().append(" | ").append(status);
        }
        revision++;
    }

    private void setStatus(String key, Object... arguments) {
        status = Component.translatable(key, arguments);
        updateDescription();
    }

    private static BlockHitResult pick(Minecraft mc, FrameView view, double x, double y) {
        CursorRay.Ray ray = CursorRay.unproject(x, y, 1, 1, view.inverse());
        if (ray == null) {
            return null;
        }
        CursorRay.Point origin = ray.origin();
        CursorRay.Point direction = ray.direction();
        Vec3 start = view.position().add(origin.x(), origin.y(), origin.z());
        Vec3 end = start.add(direction.x() * view.range(), direction.y() * view.range(), direction.z() * view.range());
        BlockHitResult hit = mc.level.clip(new ClipContext(start, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && mc.level.isLoaded(hit.getBlockPos())
                && mc.level.getWorldBorder().isWithinBounds(hit.getBlockPos()) ? hit : null;
    }

    private void drawSelection(Minecraft mc, RenderLevelStageEvent event) {
        if (hovered == null && state.size() == 0 && faceSelection == null && surfaceScan == null) {
            return;
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poses = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        for (AABB bounds : selectedBoxes) {
            if (event.getFrustum().isVisible(bounds)) {
                LevelRenderer.renderLineBox(poses, lines, bounds.move(-camera.x, -camera.y, -camera.z), 1, 0.8F, 0.1F, 0.9F);
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
            boolean allowed = faceValid && preview.withinLimit(SelectionState.MAX_BLOCKS) && isRegionLoaded(mc, preview)
                    && (surfacePreview == null || (!surfacePreview.job.failed()
                    && isRegionLoaded(mc, surfacePreview.dependencyBounds)));
            float red = allowed ? 0.2F : 1.0F;
            float green = allowed ? 0.9F : 0.2F;
            float blue = allowed ? 1.0F : 0.2F;
            drawFace(event, lines, faceSelection, red, green, blue);
            if (!faceVolumeMode && surfacePreview != null) {
                drawSurface(event, lines, surfacePreview, red, green, blue);
            }
            if (selectingHeight) {
                drawBox(event, lines, preview, red, green, blue);
                drawHeightAxis(event, lines, faceSelection, heightPointer.offset());
            }
        }
        if (surfaceScan != null) {
            drawSurface(event, lines, surfaceScan, 0.2F, 0.9F, 1.0F);
        }
        if (hovered != null && !state.contains(cell(hovered.getBlockPos()))) {
            BlockPos position = hovered.getBlockPos();
            drawShape(event, lines, position, mc.level.getBlockState(position)
                    .getShape(mc.level, position, CollisionContext.of(mc.player)), 1, 1, 1);
        }
        buffers.endBatch(RenderType.lines());
    }

    private static void drawBox(RenderLevelStageEvent event, VertexConsumer lines, SelectionState.Cuboid bounds,
                                float red, float green, float blue) {
        Vec3 camera = event.getCamera().getPosition();
        LevelRenderer.renderLineBox(event.getPoseStack(), lines, box(bounds).move(-camera.x, -camera.y, -camera.z),
                red, green, blue, 0.9F);
    }

    private static void drawSurface(RenderLevelStageEvent event, VertexConsumer lines, SurfaceTask task,
                                    float red, float green, float blue) {
        if (task.job.failed()) {
            return;
        }
        if (task.job.done()) {
            if (task.boxes == null) {
                task.boxes = SelectionBoxes.merge(task.fullBlocks).stream().map(BlockSelectionController::box).toList();
            }
            Vec3 camera = event.getCamera().getPosition();
            for (AABB bounds : task.boxes) {
                if (event.getFrustum().isVisible(bounds)) {
                    LevelRenderer.renderLineBox(event.getPoseStack(), lines, bounds.move(-camera.x, -camera.y, -camera.z),
                            red, green, blue, 0.9F);
                }
            }
        } else {
            // Avoid thousands of temporary boxes while a large preview is still scanning.
            int drawn = 0;
            for (SelectionState.Cell cell : task.fullBlocks) {
                if (drawn++ == 256) {
                    break;
                }
                drawBox(event, lines, new SelectionState.Cuboid(cell, cell), red, green, blue);
            }
        }
        int drawn = 0;
        for (var entry : task.specialShapes.entrySet()) {
            if (!task.job.done() && drawn++ == 256) {
                break;
            }
            BlockPos position = pos(entry.getKey());
            if (event.getFrustum().isVisible(new AABB(position))) {
                drawShape(event, lines, position, entry.getValue(), red, green, blue);
            }
        }
    }

    private static Vec3 heightAxisOrigin(FaceSelection face) {
        SelectionState.Cell endpoint = face.endpoint();
        int axis = face.face().axis();
        return new Vec3(axis == 0 ? face.planeCoordinate() : endpoint.x() + 0.5,
                axis == 1 ? face.planeCoordinate() : endpoint.y() + 0.5,
                axis == 2 ? face.planeCoordinate() : endpoint.z() + 0.5);
    }

    private static Vec3 faceNormal(FaceSelection.Face face) {
        return new Vec3(face.axis() == 0 ? face.sign() : 0, face.axis() == 1 ? face.sign() : 0,
                face.axis() == 2 ? face.sign() : 0);
    }

    private static void drawHeightAxis(RenderLevelStageEvent event, VertexConsumer lines, FaceSelection face, int offset) {
        Vec3 origin = heightAxisOrigin(face);
        Vec3 normal = faceNormal(face.face());
        double extent = Math.max(4, Math.min(96, Math.abs(offset) + 1));
        drawWorldLine(event, lines, origin, origin.add(normal.scale(extent)), 0.2F, 1, 0.4F);
        drawWorldLine(event, lines, origin, origin.add(normal.scale(-extent)), 1, 0.4F, 0.4F);
        Vec3 marker = origin.add(normal.scale(offset));
        for (int axis = 0; axis < 3; axis++) {
            if (axis != face.face().axis()) {
                Vec3 tangent = new Vec3(axis == 0 ? 0.25 : 0, axis == 1 ? 0.25 : 0, axis == 2 ? 0.25 : 0);
                drawWorldLine(event, lines, marker.subtract(tangent), marker.add(tangent), 1, 0.9F, 0.2F);
            }
        }
    }

    private static void drawWorldLine(RenderLevelStageEvent event, VertexConsumer lines, Vec3 from, Vec3 to,
                                      float red, float green, float blue) {
        Vec3 direction = to.subtract(from).normalize();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack.Pose pose = event.getPoseStack().last();
        for (Vec3 point : List.of(from, to)) {
            lines.vertex(pose.pose(), (float) (point.x - camera.x), (float) (point.y - camera.y), (float) (point.z - camera.z))
                    .color(red, green, blue, 0.9F)
                    .normal(pose.normal(), (float) direction.x, (float) direction.y, (float) direction.z).endVertex();
        }
    }

    private static void drawFace(RenderLevelStageEvent event, VertexConsumer lines, FaceSelection face,
                                 float red, float green, float blue) {
        List<CursorRay.Point> corners = face.corners();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack.Pose pose = event.getPoseStack().last();
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
                lines.vertex(pose.pose(), (float) (point.x() - camera.x + (axis == 0 ? offset : 0)),
                        (float) (point.y() - camera.y + (axis == 1 ? offset : 0)),
                        (float) (point.z() - camera.z + (axis == 2 ? offset : 0)))
                        .color(red, green, blue, 0.9F).normal(pose.normal(), nx, ny, nz).endVertex();
            }
        }
    }

    private static AABB box(SelectionState.Cuboid bounds) {
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                (double) bounds.maxX() + 1, (double) bounds.maxY() + 1, (double) bounds.maxZ() + 1);
    }

    private static void drawShape(RenderLevelStageEvent event, VertexConsumer lines, BlockPos position,
                                  VoxelShape shape, float red, float green, float blue) {
        PoseStack poses = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        poses.pushPose();
        try {
            poses.translate(position.getX() - camera.x, position.getY() - camera.y, position.getZ() - camera.z);
            PoseStack.Pose pose = poses.last();
            shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
                double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
                if (length == 0) {
                    return;
                }
                float normalX = (float) ((x2 - x1) / length);
                float normalY = (float) ((y2 - y1) / length);
                float normalZ = (float) ((z2 - z1) / length);
                lines.vertex(pose.pose(), (float) x1, (float) y1, (float) z1).color(red, green, blue, 0.9F)
                        .normal(pose.normal(), normalX, normalY, normalZ).endVertex();
                lines.vertex(pose.pose(), (float) x2, (float) y2, (float) z2).color(red, green, blue, 0.9F)
                        .normal(pose.normal(), normalX, normalY, normalZ).endVertex();
            });
        } finally {
            poses.popPose();
        }
    }

    private static SelectionState.Cell cell(BlockPos position) {
        return new SelectionState.Cell(position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos pos(SelectionState.Cell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private record FrameView(Matrix4f inverse, Matrix4f projectionView, Vec3 position, double range) {}
    private static double coordinate(Vec3 point, int axis) {
        return axis == 0 ? point.x : axis == 1 ? point.y : point.z;
    }

    private static double coordinate(CursorRay.Point point, int axis) {
        return axis == 0 ? point.x() : axis == 1 ? point.y() : point.z();
    }

    private static int coordinate(SelectionState.Cell cell, int axis) {
        return axis == 0 ? cell.x() : axis == 1 ? cell.y() : cell.z();
    }

    private record Action(double x, double y, SelectionState.Cuboid bounds, boolean additive, FrameView view,
                          SurfaceTask surface) {}
    private static final class SurfaceTask {
        private final SelectionState.Cuboid footprint;
        private final SelectionState.Cuboid dependencyBounds;
        private final SurfaceSelectionJob job;
        private final Set<SelectionState.Cell> fullBlocks = new LinkedHashSet<>();
        private final Map<SelectionState.Cell, VoxelShape> specialShapes = new HashMap<>();
        private List<AABB> boxes;

        private SurfaceTask(Minecraft mc, SelectionState.Cuboid footprint, FaceSelection.Face face,
                            SurfaceSelectionJob.NormalRange range) {
            this.footprint = footprint;
            dependencyBounds = withNormalRange(footprint, face.axis(), range.min(), range.max());
            job = new SurfaceSelectionJob(footprint, face, range, new SurfaceSelectionJob.Source() {
                @Override
                public SurfaceSelectionJob.NormalRange range(SelectionState.Cell column, FaceSelection.Face direction,
                                                            SurfaceSelectionJob.NormalRange search) {
                    return occupiedRange(mc, column, direction, search);
                }

                @Override
                public SurfaceSelectionJob.Sample sample(SelectionState.Cell cell) {
                    BlockPos position = pos(cell);
                    if (!mc.level.isLoaded(position) || !mc.level.getWorldBorder().isWithinBounds(position)) {
                        return SurfaceSelectionJob.Sample.UNAVAILABLE;
                    }
                    BlockState block = mc.level.getBlockState(position);
                    if (block.isAir()) {
                        return SurfaceSelectionJob.Sample.EMPTY;
                    }
                    VoxelShape shape = block.getShape(mc.level, position, CollisionContext.of(mc.player));
                    if (shape.isEmpty()) {
                        return SurfaceSelectionJob.Sample.EMPTY;
                    }
                    if (Block.isShapeFullBlock(shape)) {
                        fullBlocks.add(cell);
                    } else {
                        specialShapes.put(cell, shape);
                    }
                    return SurfaceSelectionJob.Sample.SURFACE;
                }
            });
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
