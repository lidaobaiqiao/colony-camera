package dev.colony.camera.client.planning;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.preset.MinecraftPresetAdapter;
import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.planning.ProjectionRepository;
import dev.colony.camera.preset.BlockPreset;
import dev.colony.camera.preset.PresetRepository;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Local designs and placement drafts; block drafts submit server intentions through BuildingClient. */
@ParametersAreNonnullByDefault
public final class ProjectionController {
    private static final Logger LOGGER = LogUtils.getLogger();
    @Nonnull private static final ProjectionController INSTANCE = new ProjectionController();
    public static final int MAX_TOTAL_BLOCKS = 32768;
    @Nonnull private List<BlockPreset> presets = Objects.requireNonNull(List.of());
    private final List<ProjectionPlacement> placements = new ArrayList<>();
    @Nullable private ClientLevel level;
    @Nullable private PresetRepository presetFiles;
    @Nullable private ProjectionRepository projectionFiles;
    @Nullable private ProjectionPlacement draft;
    @Nullable private String blockItem;
    @Nullable private java.util.function.Consumer<UUID> blockPlacedListener;
    private boolean following;
    private boolean cursorValid;
    private int heightOffset;
    @Nonnull private Component status = Objects.requireNonNull(Component.empty());
    private long statusTime;
    private int revision;

    private ProjectionController() {}
    @Nonnull public static ProjectionController instance() { return INSTANCE; }
    @Nonnull public List<BlockPreset> presets() { return presets; }
    @Nonnull public List<ProjectionPlacement> placements() { return Objects.requireNonNull(List.copyOf(placements)); }
    @Nullable public ProjectionPlacement draft() { return draft; }
    public boolean blockDraft() { return blockItem != null && draft != null; }
    @Nullable public String blockItem() { return blockItem; }
    public boolean following() { return following; }
    @Nullable public BlockDraftState blockDraftState() {
        ProjectionPlacement current = draft; String item = blockItem;
        return current == null || item == null ? null : new BlockDraftState(current, item, following, cursorValid, heightOffset);
    }
    public void restoreBlockDraft(@Nullable BlockDraftState saved) {
        draft = saved == null ? null : saved.placement();
        blockItem = saved == null ? null : saved.item();
        following = saved != null && saved.following();
        // The cursor must be checked again against the live world after a switch.
        cursorValid = saved != null && !saved.following() && saved.cursorValid();
        heightOffset = saved == null ? 0 : saved.heightOffset();
        revision++;
    }
    public void blockPlacedListener(@Nullable java.util.function.Consumer<UUID> listener) { blockPlacedListener = listener; }
    public int revision() { return revision; }
    @Nonnull public Component status() { return status; }
    public boolean recentStatus() { return System.nanoTime() - statusTime < 6_000_000_000L; }

    public void tick(Minecraft mc) {
        ClientLevel current = mc.level;
        if (current == level) return;
        level = null;
        placements.clear();
        draft = null;
        blockItem = null;
        projectionFiles = null;
        revision++;
        ProjectionRenderer.invalidate();
        if (current == null) return;
        Path root = Objects.requireNonNull(mc.gameDirectory.toPath().resolve("colonycamera"));
        presetFiles = new PresetRepository(Objects.requireNonNull(root.resolve("presets")));
        String identity;
        var server = mc.getSingleplayerServer();
        var serverData = mc.getCurrentServer();
        if (server != null) identity = "local:" + server.getWorldPath(Objects.requireNonNull(LevelResource.ROOT)).toAbsolutePath().normalize();
        else if (serverData != null) identity = "server:" + serverData.ip;
        else { message("world_unavailable"); return; }
        level = current;
        Path directory = Objects.requireNonNull(root.resolve("projections").resolve(digest(identity))
                .resolve(digest(Objects.requireNonNull(current.dimension().location().toString()))));
        ProjectionRepository repository = new ProjectionRepository(directory);
        projectionFiles = repository;
        reloadLibrary();
        try {
            ProjectionRepository.Loaded loaded = repository.load();
            int skipped = loaded.rejected().size();
            for (ProjectionPlacement placement : loaded.placements()) {
                try {
                    validateStates(Objects.requireNonNull(placement));
                    if (blockCount() + placement.preset().blocks().size() > MAX_TOTAL_BLOCKS) throw new IllegalArgumentException("Block limit");
                    placements.add(placement);
                } catch (RuntimeException failure) { skipped++; }
            }
            if (skipped > 0) message("rejected", skipped);
        } catch (IOException failure) { ioFailure(failure); }
    }

    public void reloadLibrary() {
        PresetRepository repository = presetFiles;
        if (repository == null) return;
        try {
            PresetRepository.Loaded loaded = repository.load();
            presets = loaded.presets();
            revision++;
            if (!loaded.rejected().isEmpty()) message("rejected", loaded.rejected().size());
        } catch (IOException failure) { ioFailure(failure); }
    }

    public boolean saveSelection(String name) {
        ClientLevel current = level;
        PresetRepository repository = presetFiles;
        var selection = StrategyCameraController.instance().selection();
        if (current == null || repository == null || selection.working() || selection.selectingHeight()) {
            message("selection_busy"); return false;
        }
        try {
            if (presets.size() >= 512) { message("library_limit"); return false; }
            BlockPreset preset = MinecraftPresetAdapter.capture(current, Objects.requireNonNull(selection.selectedCells()), name);
            repository.save(preset);
            reloadLibrary();
            message("saved", preset.name());
            return true;
        } catch (IOException failure) { ioFailure(failure); }
        catch (RuntimeException failure) { LOGGER.warn("Preset capture rejected", failure); message("capture_error", BlockPreset.MAX_BLOCKS, BlockPreset.MAX_SIZE); }
        return false;
    }

    public boolean rename(BlockPreset preset, String name) {
        PresetRepository repository = presetFiles;
        if (repository == null) return false;
        try { repository.save(preset.renamed(name)); reloadLibrary(); message("renamed"); return true; }
        catch (IOException failure) { ioFailure(failure); }
        catch (RuntimeException failure) { message("invalid_name"); }
        return false;
    }

    public void deletePreset(BlockPreset preset) {
        PresetRepository repository = presetFiles;
        if (repository == null) return;
        try { repository.delete(preset.id()); reloadLibrary(); message("deleted"); }
        catch (IOException failure) { ioFailure(failure); }
    }

    public boolean start(BlockPreset preset) {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || level == null) return false;
        ProjectionPlacement candidate = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset,
                player.getBlockX(), player.getBlockY(), player.getBlockZ(), 0, false, true);
        return begin(candidate, true);
    }

    public boolean edit(ProjectionPlacement placement) { return begin(placement.visibility(true), false); }
    public boolean startBlock(net.minecraft.world.item.Item item) {
        try {
            if (!start(dev.colony.camera.construction.BlockBuilding.design(item))) return false;
            blockItem = Objects.requireNonNull(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item)).toString();
            status = Objects.requireNonNull(Component.translatable("screen.colonycamera.build.draft_hint")); statusTime = System.nanoTime();
            return true;
        } catch (RuntimeException failure) { message("unsupported"); return false; }
    }
    public void placedBlock(UUID id) {
        BlockDraftState current = blockDraftState();
        if (current != null && current.placement().id().equals(id)) restoreBlockDraft(current.accepted(id));
        var listener = blockPlacedListener;
        if (listener != null) listener.accept(id);
    }

    private boolean begin(ProjectionPlacement placement, boolean follow) {
        try { validateStates(Objects.requireNonNull(placement)); }
        catch (RuntimeException failure) { message("unsupported"); return false; }
        draft = placement;
        blockItem = null;
        following = follow;
        cursorValid = !follow;
        heightOffset = 0;
        StrategyCameraController.instance().selection().cancelPointerInput();
        revision++;
        message(follow ? "follow_hint" : "edit_hint");
        return true;
    }

    public void followPointer(double x, double y, boolean blocked) {
        ProjectionPlacement current = draft;
        if (current == null || !following || blocked || !Minecraft.getInstance().isWindowActive()
                || blockDraft() && dev.colony.camera.client.construction.BuildingClient.pending()) return;
        BlockHitResult hit = StrategyCameraController.instance().selection().hitAt(x, y);
        cursorValid = hit != null;
        if (hit == null) return;
        BlockPos clicked = Objects.requireNonNull(hit.getBlockPos());
        ClientLevel world = level;
        BlockPos target = blockDraft() && world != null && dev.colony.camera.construction.BlockBuilding.canReplace(Objects.requireNonNull(world.getBlockState(clicked)))
                ? clicked : Objects.requireNonNull(clicked.relative(Objects.requireNonNull(hit.getDirection())));
        ProjectionPlacement moved = current.at(target.getX(), target.getY() + heightOffset, target.getZ());
        if (!moved.equals(current)) { draft = moved; revision++; }
    }

    public void pin() {
        if (draft == null) return;
        if (!cursorValid) { message("no_target"); return; }
        following = false;
        message("edit_hint");
    }

    public void follow() { if (draft != null) { following = true; cursorValid = false; heightOffset = 0; message("follow_hint"); } }
    public void rotate() { ProjectionPlacement current = draft; if (current != null) { draft = current.rotated(); revision++; } }
    public void mirror() { ProjectionPlacement current = draft; if (current != null && !blockDraft()) { draft = current.mirror(); revision++; } }
    public void move(int dx, int dy, int dz) {
        ProjectionPlacement current = draft;
        if (current == null) return;
        try {
            draft = current.moved(dx, dy, dz);
            if (following && dx == 0 && dz == 0) heightOffset += dy;
            else { following = false; cursorValid = true; }
            revision++;
        } catch (RuntimeException failure) { message("outside"); }
    }

    public boolean confirm() {
        ProjectionPlacement current = draft;
        ProjectionRepository repository = projectionFiles;
        ClientLevel world = level;
        if (current == null || repository == null || world == null) return false;
        if (following && !cursorValid) { message("no_target"); return false; }
        String buildingItem = blockItem;
        if (buildingItem != null) return dev.colony.camera.client.construction.BuildingClient.place(buildingItem, current);
        boolean editing = placements.stream().anyMatch(item -> item.id().equals(current.id()));
        if ((!editing && placements.size() >= ProjectionRepository.MAX_PLACEMENTS)
                || blockCountWithout(current.id()) + current.preset().blocks().size() > MAX_TOTAL_BLOCKS) {
            message("placement_limit", ProjectionRepository.MAX_PLACEMENTS, MAX_TOTAL_BLOCKS); return false;
        }
        for (BlockPreset.Cell cell : current.preset().blocks()) {
            ProjectionPlacement.Position target = current.transform(Objects.requireNonNull(cell));
            BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
            if (!world.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    || world.isOutsideBuildHeight(pos) || !world.getWorldBorder().isWithinBounds(pos)) {
                message("outside"); return false;
            }
        }
        try {
            repository.save(current);
            placements.removeIf(item -> item.id().equals(current.id()));
            placements.add(current);
            draft = null;
            revision++;
            message("confirmed");
            return true;
        } catch (IOException failure) { ioFailure(failure); return false; }
    }

    public void cancel() { if (draft != null) { draft = null; blockItem = null; revision++; message("cancelled"); } }

    public void toggleVisible(ProjectionPlacement placement) {
        ProjectionRepository repository = projectionFiles;
        if (repository == null) return;
        ProjectionPlacement next = placement.visibility(!placement.visible());
        try {
            repository.save(next);
            placements.replaceAll(item -> item.id().equals(next.id()) ? next : item);
            revision++;
        } catch (IOException failure) { ioFailure(failure); }
    }

    public void remove(ProjectionPlacement placement) {
        ProjectionRepository repository = projectionFiles;
        if (repository == null) return;
        try {
            repository.delete(placement.id());
            placements.removeIf(item -> item.id().equals(placement.id()));
            ProjectionPlacement current = draft;
            if (current != null && current.id().equals(placement.id())) draft = null;
            revision++;
            message("removed");
        } catch (IOException failure) { ioFailure(failure); }
    }

    private int blockCount() { return placements.stream().mapToInt(item -> item.preset().blocks().size()).sum(); }
    private int blockCountWithout(UUID id) { return placements.stream().filter(item -> !item.id().equals(id)).mapToInt(item -> item.preset().blocks().size()).sum(); }
    private static void validateStates(ProjectionPlacement placement) {
        for (BlockPreset.State state : placement.preset().blocks().stream().map((BlockPreset.Cell checkedElement) -> Objects.requireNonNull(checkedElement).state()).distinct().toList()) {
            MinecraftPresetAdapter.resolve(Objects.requireNonNull(state), placement.quarterTurns(), placement.mirrored());
        }
    }
    private void message(String suffix, Object... args) {
        status = Objects.requireNonNull(Component.translatable("screen.colonycamera.projection." + suffix, args));
        statusTime = System.nanoTime();
    }
    private void ioFailure(IOException failure) { LOGGER.warn("Projection file operation failed", failure); message("io_error"); }
    @Nonnull
    private static String digest(String value) {
        try { return Objects.requireNonNull(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
