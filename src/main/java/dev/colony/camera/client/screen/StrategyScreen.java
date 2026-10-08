package dev.colony.camera.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.input.CameraKeyMappings;
import dev.colony.camera.client.planning.ProjectionController;
import dev.colony.camera.client.input.PointerGesture;
import dev.colony.camera.client.work.ColonyWorkClient;
import dev.colony.camera.client.work.WorkPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Captures planning input without passing movement or clicks to the player. */
public final class StrategyScreen extends Screen {
    // Screen.minecraft is nullable until init(); this screen is created by the live client.
    private final Minecraft client = Minecraft.getInstance();
    private final StrategyCameraController controller;
    private final StrategyHud hud = new StrategyHud();
    private final ProjectionPanel projections = new ProjectionPanel();
    private final WorkPanel work = new WorkPanel(true);
    private final SelectionLimitPanel similarLimit = new SelectionLimitPanel();
    private final RegistryPicker volumeFilter = new RegistryPicker();
    private final BuildWindows building = new BuildWindows();
    private final PointerGesture middleGesture = new PointerGesture();
    private int dragButton = -1;
    private final Set<InputConstants.Key> heldActionKeys = new HashSet<>();
    private double selectionStartX;
    private double selectionStartY;
    private boolean faceDragging;
    private boolean rightDragging;
    private boolean clearOnRightRelease;
    private double rightStartX;
    private double rightStartY;
    private double heightCursorX = Double.NaN;
    private boolean cursorConstraintQueued;
    private boolean pointerOverUi;

    public StrategyScreen(StrategyCameraController controller) {
        super(Component.translatable("screen.colonycamera.strategy"));
        this.controller = controller;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        clearPointerGesture();
        controller.selection().cancelPointerInput();
        heightCursorX = Double.NaN;
        hud.resize(Objects.requireNonNull(font), width, height);
        similarLimit.resize(Objects.requireNonNull(font), hud.layout().similarLimitDialog());
        volumeFilter.resize(Objects.requireNonNull(font), width, height);
        projections.resize(Objects.requireNonNull(font), width, height, hud.layout().hint().y());
        work.resize(Objects.requireNonNull(font), width, height);
        building.resize(Objects.requireNonNull(font), width, height, hud.layout().dock().y() - 4, hud.layout().placement());
        pointerOverUi = false;
    }

    @Override
    public void removed() {
        clearInputState();
        building.dispose();
        controller.exit(Minecraft.getInstance());
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (CameraKeyMappings.matchesToggle(key, scan)) {
            client.setScreen(null);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && hud.closeMenu()) { pauseCameraInput(); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE && hud.helpOpen()) { hud.closeHelp(); pauseCameraInput(); return true; }
        if (volumeFilter.keyPressed(key, scan, modifiers) || similarLimit.keyPressed(key, scan, modifiers)) return true;
        if (work.keyPressed(key, scan, modifiers)) return true;
        if (projections.keyPressed(key, scan, modifiers)) return true;
        if (building.keyPressed(key, scan, modifiers)) return true;
        ProjectionController planning = ProjectionController.instance();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (planning.draft() != null) planning.cancel();
            else client.setScreen(null);
            return true;
        }
        if (!hud.helpOpen() && key == GLFW.GLFW_KEY_P) {
            clearInputState(); building.releaseDraft(); work.close(); hud.closeMenu(); projections.openPresets(); return true;
        }
        if (!hud.helpOpen() && (key == GLFW.GLFW_KEY_N || key == GLFW.GLFW_KEY_6)) { openBuilding(); return true; }
        if (!hud.helpOpen() && key == GLFW.GLFW_KEY_O) {
            openWork(WorkPanel.Tab.TASKS); return true;
        }
        if (!hud.helpOpen() && key == GLFW.GLFW_KEY_B && planning.draft() == null) {
            building.releaseDraft(); hud.closeMenu(); projections.saveDialog(); clearInputState(); return true;
        }
        if (hud.menuOpen()) return true;
        if (!hud.helpOpen() && planning.draft() != null) {
            switch (key) {
                case GLFW.GLFW_KEY_R -> { if (heldActionKeys.add(InputConstants.getKey(key, scan))) planning.rotate(); return true; }
                case GLFW.GLFW_KEY_M -> { if (heldActionKeys.add(InputConstants.getKey(key, scan))) planning.mirror(); return true; }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { planning.confirm(); return true; }
                case GLFW.GLFW_KEY_LEFT -> { planning.move(-1, 0, 0); return true; }
                case GLFW.GLFW_KEY_RIGHT -> { planning.move(1, 0, 0); return true; }
                case GLFW.GLFW_KEY_UP -> { planning.move(0, 0, -1); return true; }
                case GLFW.GLFW_KEY_DOWN -> { planning.move(0, 0, 1); return true; }
                case GLFW.GLFW_KEY_PAGE_UP -> { planning.move(0, 1, 0); return true; }
                case GLFW.GLFW_KEY_PAGE_DOWN -> { planning.move(0, -1, 0); return true; }
                case GLFW.GLFW_KEY_F -> { planning.follow(); return true; }
                default -> { }
            }
        }
        if (!hud.helpOpen()) {
            CameraKeyMappings.recordElevationKey(key, scan, true);
        }
        if (!hud.helpOpen() && (CameraKeyMappings.matchesAngleLock(key, scan)
                || CameraKeyMappings.matchesAddSelection(key, scan)
                || CameraKeyMappings.matchesVolumeSelection(key, scan)
                || CameraKeyMappings.matchesClearSelection(key, scan))) {
            if (heldActionKeys.add(InputConstants.getKey(key, scan))) {
                clearPointerGesture();
                if (CameraKeyMappings.matchesAngleLock(key, scan)) {
                    controller.toggleAngleLock();
                } else if (CameraKeyMappings.matchesAddSelection(key, scan)) {
                    prepareSelectionTool();
                    controller.selection().toggleAdditive();
                } else if (CameraKeyMappings.matchesVolumeSelection(key, scan)) {
                    toggleVolumeSelection();
                } else {
                    controller.selection().clearSelection();
                }
            }
        }
        return true;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (volumeFilter.charTyped(character, modifiers) || similarLimit.charTyped(character, modifiers)) return true;
        if (work.isOpen()) return work.charTyped(character, modifiers);
        if (projections.modal()) return projections.charTyped(character, modifiers);
        if (building.charTyped(character, modifiers)) return true;
        return projections.charTyped(character, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        CameraKeyMappings.recordElevationKey(key, scan, false);
        heldActionKeys.remove(InputConstants.getKey(key, scan));
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        clearPointerGesture();
        if (!client.isWindowActive()) {
            return true;
        }
        if (volumeFilter.click(x, y, button) || similarLimit.click(x, y, button)) return true;
        if (work.click(x, y, button)) return true;
        if (projections.click(x, y, button)) return true;
        if (hud.helpOpen()) {
            StrategyHudLayout.Rect help = hud.layout().help();
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && (!help.contains(x, y)
                    || (x >= help.right() - 26 && y < help.y() + 26))) {
                hud.closeHelp();
            }
            return true;
        }
        if (hud.menuOpen()) {
            var action = hud.actionAt(x, y);
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action != null) runHudAction(action);
            else hud.closeMenu();
            return true;
        }
        var hudAction = hud.actionAt(x, y);
        if (hudAction != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) runHudAction(hudAction);
            return true;
        }
        if (hud.blocksWorld(x, y, controller, building.isOpen())) return true;
        if (building.click(x, y, button)) return true;
        if (blocksWorldPointer(x, y)) return true;
        if (CameraKeyMappings.matchesVolumeSelectionMouse(button)) { toggleVolumeSelection(); return true; }
        if (CameraKeyMappings.matchesAddSelectionMouse(button)) {
            prepareSelectionTool(); controller.selection().toggleAdditive(); return true;
        }
        if (CameraKeyMappings.matchesClearSelectionMouse(button) && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            controller.selection().clearSelection(); return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            dragButton = button; middleGesture.begin(x, y); return true;
        }
        if (ProjectionController.instance().draft() != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                ProjectionController.instance().followPointer(normalized(x, width), normalized(y, height), false);
                if (ProjectionController.instance().blockDraft()) ProjectionController.instance().confirm();
                else ProjectionController.instance().pin();
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                clearOnRightRelease = true;
                dragButton = button;
                rightStartX = x;
                rightStartY = y;
            }
            return true;
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            dragButton = button;
            rightStartX = x;
            rightStartY = y;
            clearOnRightRelease = true;

        } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (!controller.selection().volumeMode()) {
                controller.selectAt(x, y, width, height);
            } else if (controller.selection().selectingHeight()) {
                controller.selection().updateHeightPointer(normalized(y, height));
                controller.selection().confirmHeight();
            } else {
                dragButton = button;
                selectionStartX = x;
                selectionStartY = y;
                controller.selection().beginFaceSelection(normalized(x, width), normalized(y, height));
            }
        }
        // All clicks remain owned by the planning screen, never by the player.
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double deltaX, double deltaY) {
        if (similarLimit.isOpen() || work.isOpen() || projections.modal() || hud.helpOpen() || hud.menuOpen()) return true;
        if (building.drag(x, y, button)) return true;
        if (button == dragButton && client.isWindowActive()) {
            switch (button) {
                case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> {
                    if (middleGesture.move(x, y, client.getWindow().getGuiScale()) && !controller.angleLocked())
                        controller.rotate(deltaX, deltaY);
                }
                case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                    rightDragging |= Math.hypot(x - rightStartX, y - rightStartY) * client.getWindow().getGuiScale() >= 4;
                    if (rightDragging) controller.drag(deltaX, deltaY);
                }
                case GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                    double pixelScale = client.getWindow().getGuiScale();
                    faceDragging |= Math.hypot(x - selectionStartX, y - selectionStartY) * pixelScale >= 4;
                    if (faceDragging && !blocksWorldPointer(x, y)) {
                        controller.selection().updateFaceSelection(normalized(x, width), normalized(y, height));
                    }
                }
                default -> { }
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (similarLimit.isOpen()) return true;
        if (building.release(button)) { controller.selection().rebaseHeightPointer(normalized(y, height)); return true; }
        if (button == dragButton) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                boolean click = middleGesture.release(x, y, client.getWindow().getGuiScale());
                if (click && client.isWindowActive() && !blocksWorldPointer(x, y)) {
                    prepareSelectionTool(); controller.selection().cycleMode();
                }
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && clearOnRightRelease && !rightDragging
                    && client.isWindowActive() && !blocksWorldPointer(x, y)
                    && Math.hypot(x - rightStartX, y - rightStartY) * client.getWindow().getGuiScale() < 4) {
                controller.selection().clearSelection();
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && client.isWindowActive() && !blocksWorldPointer(x, y)) {
                controller.selection().finishFaceSelection(normalized(x, width), normalized(y, height));
                if (controller.selection().selectingHeight()) {
                    heightCursorX = normalized(x, width) * client.getWindow().getScreenWidth();
                }
            }
            controller.selection().rebaseHeightPointer(normalized(y, height));
            clearPointerGesture();
        }
        return true;
    }

    public void clearPointerGesture() {
        dragButton = -1;
        middleGesture.cancel();
        faceDragging = false;
        rightDragging = false;
        clearOnRightRelease = false;
        controller.selection().cancelBaseGesture();
        if (!controller.selection().selectingHeight()) {
            heightCursorX = Double.NaN;
        }
    }

    public void clearInputState() {
        clearPointerGesture();
        building.cancelGesture();
        CameraKeyMappings.clearElevationKeys();
        heldActionKeys.clear();
        controller.selection().cancelPointerInput();
        heightCursorX = Double.NaN;
        pointerOverUi = false;
    }

    /** Menu/help navigation pauses an unfinished height choice without discarding it. */
    private void pauseCameraInput() {
        clearPointerGesture();
        CameraKeyMappings.clearElevationKeys();
        heldActionKeys.clear();
        pointerOverUi = true;
    }

    @Override
    public void tick() {
        similarLimit.tick();
        volumeFilter.tick();
        projections.tick();
        building.tick();
        work.tick();
        if (!client.isWindowActive()) {
            clearInputState();
        } else {
            heldActionKeys.removeIf(key -> key.getType() == InputConstants.Type.KEYSYM
                    && !InputConstants.isKeyDown(client.getWindow().getWindow(), key.getValue()));
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (volumeFilter.scroll(delta) || similarLimit.isOpen()) return true;
        if (hud.helpOpen() || hud.menuOpen()) return true;
        if (work.scroll(x, y, delta)) return true;
        if (building.scroll(x, y, delta)) return true;
        if (projections.scroll(x, y, delta)) return true;
        if (client.isWindowActive() && !hud.helpOpen()) {
            controller.zoom(delta);
        }
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) {
        boolean wasOverUi = pointerOverUi;
        pointerOverUi = blocksWorldPointer(x, y);
        if (!client.isWindowActive() || !controller.selection().selectingHeight()) {
            return;
        }
        if (dragButton >= 0 || building.dragging() || pointerOverUi || wasOverUi) {
            controller.selection().rebaseHeightPointer(normalized(y, height));
            if (pointerOverUi || wasOverUi) {
                heightCursorX = normalized(x, width) * client.getWindow().getScreenWidth();
            }
            return;
        }
        controller.selection().updateHeightPointer(normalized(y, height));
        double rawX = normalized(x, width) * client.getWindow().getScreenWidth();
        if (Double.isFinite(heightCursorX) && Math.abs(rawX - heightCursorX) > 0.5 && !cursorConstraintQueued) {
            cursorConstraintQueued = true;
            // tell() always queues, unlike execute(). Let MouseHandler finish
            // storing this event before GLFW can generate another mouse callback.
            client.tell(() -> {
                cursorConstraintQueued = false;
                if (client.screen != this || !client.isWindowActive()
                        || !controller.selection().selectingHeight() || dragButton >= 0 || building.dragging()
                        || !Double.isFinite(heightCursorX)) {
                    return;
                }
                double cursorX = client.mouseHandler.xpos();
                double cursorY = client.mouseHandler.ypos();
                if (blocksWorldPointer(cursorX * width / client.getWindow().getScreenWidth(),
                        cursorY * height / client.getWindow().getScreenHeight())) {
                    return;
                }
                if (Double.isFinite(cursorX) && Double.isFinite(cursorY) && Math.abs(cursorX - heightCursorX) > 0.5) {
                    GLFW.glfwSetCursorPos(client.getWindow().getWindow(), heightCursorX, cursorY);
                }
            });
        }
    }

    public boolean blocksWorldPointer(double x, double y) {
        return volumeFilter.isOpen() || similarLimit.isOpen() || building.blocks(x, y) || work.blocks(x, y) || projections.blocks(x, y) || hud.blocksWorld(x, y, controller, building.isOpen());
    }

    public boolean allowsCameraInput() {
        return !volumeFilter.isOpen() && !similarLimit.isOpen() && !building.capturesKeyboard() && !building.dragging() && !hud.menuOpen() && !hud.helpOpen() && !projections.modal() && !work.isOpen();
    }

    public void openWork(@Nonnull WorkPanel.Tab page) {
        clearInputState(); building.blur(); projections.close(); hud.closeHelp(); hud.closeMenu(); work.open(page);
    }
    private void openBuilding() {
        clearInputState(); work.close(); projections.close(); hud.closeHelp(); hud.closeMenu(); building.toggle();
    }
    public void openWorkStation(@Nonnull BlockPos pos, boolean research) {
        clearInputState(); building.blur(); projections.close(); hud.closeHelp(); hud.closeMenu(); work.openStation(pos, research);
    }
    public void openResidence(@Nonnull BlockPos pos) { clearInputState(); building.blur(); projections.close(); hud.closeHelp(); hud.closeMenu(); work.openResidences(pos); }

    /** Selecting a tool releases only the unplaced draft, never accepted projects. */
    private void prepareSelectionTool() {
        clearPointerGesture();
        heightCursorX = Double.NaN;
        building.releaseDraft();
        ProjectionController.instance().cancel();
    }

    private void toggleVolumeSelection() {
        prepareSelectionTool();
        controller.selection().toggleVolumeMode();
    }

    private void runHudAction(StrategyHudLayout.Action action) {
        if (!hud.enabled(action, controller)) return;
        switch (action) {
            case PROJECTION_MENU, COLONY_MENU -> {
                pauseCameraInput(); building.blur();
                hud.toggleMenu(action == StrategyHudLayout.Action.PROJECTION_MENU ? StrategyHudLayout.Menu.PROJECTION : StrategyHudLayout.Menu.COLONY);
            }
            case SAVE_PRESET -> { building.releaseDraft(); work.close(); hud.closeMenu(); hud.closeHelp(); projections.saveDialog(); clearInputState(); }
            case LIBRARY -> { building.releaseDraft(); work.close(); clearInputState(); hud.closeMenu(); hud.closeHelp(); projections.openPresets(); }
            case BUILD -> openBuilding();
            case WORK -> openWork(WorkPanel.Tab.TASKS);
            case PEOPLE -> openWork(WorkPanel.Tab.PEOPLE);
            case CRAFT -> openWork(WorkPanel.Tab.CRAFT);
            case TECHNOLOGY -> openWork(WorkPanel.Tab.TECHNOLOGY);
            case PROJECTS -> openWork(WorkPanel.Tab.PROJECTS);
            case MINE -> { hud.closeMenu(); ColonyWorkClient.mine(); }
            case SWEEP -> { clearInputState(); building.blur(); projections.close(); hud.closeMenu(); hud.closeHelp(); work.openSweep(); }
            case RESIDENCES -> { clearInputState(); building.blur(); projections.close(); hud.closeMenu(); hud.closeHelp(); work.openResidences(null); }
            case VOLUME_FILTER -> {
                clearInputState(); building.blur(); work.close(); projections.close(); hud.closeMenu(); hud.closeHelp();
                volumeFilter.blocks(controller.selection().volumeFilter().snapshot(), chosen -> controller.selection().volumeFilter().set(Objects.requireNonNull(chosen)));
            }
            case SIMILAR_LIMIT -> { clearInputState(); building.blur(); work.close(); projections.close(); hud.closeMenu(); hud.closeHelp(); similarLimit.open(); }
            case ANGLE_LOCK -> controller.toggleAngleLock();
            case HELP -> { pauseCameraInput(); building.blur(); hud.toggleHelp(); }
            case EXIT -> client.setScreen(null);
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!work.isOpen() && !projections.modal() && !hud.helpOpen() && !similarLimit.isOpen() && !volumeFilter.isOpen()) {
            building.render(graphics, hud.menuContains(mouseX, mouseY) ? -10000 : mouseX, hud.menuContains(mouseX, mouseY) ? -10000 : mouseY, partialTick);
        }
        hud.render(graphics, Objects.requireNonNull(font), controller, mouseX, mouseY, width, height, building.isOpen());
        projections.render(graphics, mouseX, mouseY, partialTick);
        work.render(graphics, mouseX, mouseY);
        if (!work.isOpen() && !projections.modal() && !similarLimit.isOpen() && !volumeFilter.isOpen())
            hud.renderOverlay(graphics, Objects.requireNonNull(font), controller, mouseX, mouseY, width, height);
        similarLimit.render(graphics, mouseX, mouseY, partialTick, width, height);
        volumeFilter.render(graphics, mouseX, mouseY);
    }

    private static double normalized(double coordinate, int size) {
        return coordinate / Math.max(1, size);
    }
}
