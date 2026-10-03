package dev.colony.camera.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.input.CameraKeyMappings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

/** Captures planning input without passing movement or clicks to the player. */
public final class StrategyScreen extends Screen {
    private final StrategyCameraController controller;
    private final StrategyHud hud = new StrategyHud();
    private int dragButton = -1;
    private final Set<InputConstants.Key> heldActionKeys = new HashSet<>();
    private double selectionStartX;
    private double selectionStartY;
    private boolean faceDragging;
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
        hud.resize(width, height);
        pointerOverUi = false;
    }

    @Override
    public void removed() {
        clearInputState();
        controller.exit(Minecraft.getInstance());
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!hud.helpOpen()) {
            CameraKeyMappings.recordElevationKey(key, scan, true);
        }
        if (CameraKeyMappings.matchesToggle(key, scan) || key == GLFW.GLFW_KEY_ESCAPE) {
            minecraft.setScreen(null);
        } else if (!hud.helpOpen() && (CameraKeyMappings.matchesAngleLock(key, scan)
                || CameraKeyMappings.matchesAddSelection(key, scan)
                || CameraKeyMappings.matchesVolumeSelection(key, scan)
                || CameraKeyMappings.matchesClearSelection(key, scan))) {
            if (heldActionKeys.add(InputConstants.getKey(key, scan))) {
                clearPointerGesture();
                if (CameraKeyMappings.matchesAngleLock(key, scan)) {
                    controller.toggleAngleLock();
                } else if (CameraKeyMappings.matchesAddSelection(key, scan)) {
                    controller.selection().toggleAdditive();
                } else if (CameraKeyMappings.matchesVolumeSelection(key, scan)) {
                    controller.selection().toggleVolumeMode();
                } else {
                    controller.selection().clearSelection();
                }
            }
        }
        return true;
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
        if (!minecraft.isWindowActive()) {
            return true;
        }
        if (hud.helpOpen()) {
            StrategyHudLayout.Rect help = hud.layout().help();
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && (!help.contains(x, y)
                    || (x >= help.right() - 26 && y < help.y() + 26))) {
                hud.closeHelp();
            }
            return true;
        }
        if (blocksWorldPointer(x, y)) {
            StrategyHudLayout.Action action = hud.layout().actionAt(x, y);
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action != null) {
                runHudAction(action);
            }
            return true;
        }
        if (CameraKeyMappings.matchesAddSelectionMouse(button)) {
            controller.selection().toggleAdditive();
        } else if (CameraKeyMappings.matchesVolumeSelectionMouse(button)) {
            controller.selection().toggleVolumeMode();
        } else if (CameraKeyMappings.matchesClearSelectionMouse(button)) {
            controller.selection().clearSelection();
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (controller.selection().selectingHeight()) {
                controller.selection().updateHeightPointer(normalized(y, height));
                controller.selection().confirmHeight();
            } else {
                dragButton = button;
                selectionStartX = x;
                selectionStartY = y;
                controller.selection().beginFaceSelection(normalized(x, width), normalized(y, height));
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                || (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && !controller.angleLocked())) {
            dragButton = button;
        }
        // All clicks remain owned by the planning screen, never by the player.
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double deltaX, double deltaY) {
        if (button == dragButton && minecraft.isWindowActive()) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                controller.rotate(deltaX, deltaY);
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                controller.drag(deltaX, deltaY);
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                double pixelScale = minecraft.getWindow().getGuiScale();
                faceDragging |= Math.hypot(x - selectionStartX, y - selectionStartY) * pixelScale >= 4;
                if (faceDragging && !blocksWorldPointer(x, y)) {
                    controller.selection().updateFaceSelection(normalized(x, width), normalized(y, height));
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == dragButton) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && minecraft.isWindowActive() && !blocksWorldPointer(x, y)) {
                if (faceDragging || controller.selection().volumeMode()) {
                    controller.selection().finishFaceSelection(normalized(x, width), normalized(y, height));
                    if (controller.selection().selectingHeight()) {
                        heightCursorX = normalized(x, width) * minecraft.getWindow().getScreenWidth();
                    }
                } else {
                    controller.selection().cancelBaseGesture();
                    controller.selectAt(x, y, width, height);
                }
            }
            controller.selection().rebaseHeightPointer(normalized(y, height));
            clearPointerGesture();
        }
        return true;
    }

    public void clearPointerGesture() {
        dragButton = -1;
        faceDragging = false;
        controller.selection().cancelBaseGesture();
        if (!controller.selection().selectingHeight()) {
            heightCursorX = Double.NaN;
        }
    }

    public void clearInputState() {
        clearPointerGesture();
        CameraKeyMappings.clearElevationKeys();
        heldActionKeys.clear();
        controller.selection().cancelPointerInput();
        heightCursorX = Double.NaN;
        pointerOverUi = false;
    }

    @Override
    public void tick() {
        if (!minecraft.isWindowActive()) {
            clearInputState();
        } else {
            heldActionKeys.removeIf(key -> key.getType() == InputConstants.Type.KEYSYM
                    && !InputConstants.isKeyDown(minecraft.getWindow().getWindow(), key.getValue()));
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (minecraft.isWindowActive() && !hud.helpOpen()) {
            controller.zoom(delta);
        }
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) {
        boolean wasOverUi = pointerOverUi;
        pointerOverUi = blocksWorldPointer(x, y);
        if (!minecraft.isWindowActive() || !controller.selection().selectingHeight()) {
            return;
        }
        if (dragButton >= 0 || pointerOverUi || wasOverUi) {
            controller.selection().rebaseHeightPointer(normalized(y, height));
            if (pointerOverUi || wasOverUi) {
                heightCursorX = normalized(x, width) * minecraft.getWindow().getScreenWidth();
            }
            return;
        }
        controller.selection().updateHeightPointer(normalized(y, height));
        double rawX = normalized(x, width) * minecraft.getWindow().getScreenWidth();
        if (Double.isFinite(heightCursorX) && Math.abs(rawX - heightCursorX) > 0.5 && !cursorConstraintQueued) {
            cursorConstraintQueued = true;
            // tell() always queues, unlike execute(). Let MouseHandler finish
            // storing this event before GLFW can generate another mouse callback.
            minecraft.tell(() -> {
                cursorConstraintQueued = false;
                if (minecraft.screen != this || !minecraft.isWindowActive()
                        || !controller.selection().selectingHeight() || dragButton >= 0
                        || !Double.isFinite(heightCursorX)) {
                    return;
                }
                double cursorX = minecraft.mouseHandler.xpos();
                double cursorY = minecraft.mouseHandler.ypos();
                if (blocksWorldPointer(cursorX * width / minecraft.getWindow().getScreenWidth(),
                        cursorY * height / minecraft.getWindow().getScreenHeight())) {
                    return;
                }
                if (Double.isFinite(cursorX) && Double.isFinite(cursorY) && Math.abs(cursorX - heightCursorX) > 0.5) {
                    GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(), heightCursorX, cursorY);
                }
            });
        }
    }

    public boolean blocksWorldPointer(double x, double y) {
        return hud.layout() != null && hud.layout().blocksWorld(x, y, hud.helpOpen());
    }

    public boolean allowsCameraInput() {
        return !hud.helpOpen();
    }

    private void runHudAction(StrategyHudLayout.Action action) {
        switch (action) {
            case SURFACE -> {
                if (controller.selection().volumeMode()) {
                    controller.selection().toggleVolumeMode();
                }
            }
            case VOLUME -> {
                if (!controller.selection().volumeMode()) {
                    controller.selection().toggleVolumeMode();
                }
            }
            case ADDITIVE -> controller.selection().toggleAdditive();
            case CLEAR -> controller.selection().clearSelection();
            case ANGLE_LOCK -> controller.toggleAngleLock();
            case HELP -> {
                hud.toggleHelp();
                CameraKeyMappings.clearElevationKeys();
            }
            case EXIT -> minecraft.setScreen(null);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hud.render(graphics, font, controller, mouseX, mouseY, width, height);
    }

    private static double normalized(double coordinate, int size) {
        return coordinate / Math.max(1, size);
    }
}
