package dev.colony.camera.client.camera;

import com.mojang.blaze3d.platform.InputConstants;
import dev.colony.camera.client.input.CameraKeyMappings;
import dev.colony.camera.client.selection.BlockSelectionController;
import dev.colony.camera.client.screen.StrategyScreen;
import dev.colony.camera.client.planning.ProjectionController;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nonnull;

/** Owns the camera session and restores client state on every exit path. */
public final class StrategyCameraController {
    @Nonnull
    private static final StrategyCameraController INSTANCE = new StrategyCameraController();
    private static final double ROTATION_SENSITIVITY = 0.2;

    private final CameraState state = new CameraState();
    private final CameraFrameClock frameClock = new CameraFrameClock();
    @Nonnull
    private final BlockSelectionController selection = new BlockSelectionController();
    private boolean active;
    private boolean previousBob;

    private StrategyCameraController() {
    }

    @Nonnull
    public static StrategyCameraController instance() {
        return INSTANCE;
    }

    private void enter(Minecraft mc, LocalPlayer player) {
        state.reset(player.getX(), player.getY(), player.getZ());
        frameClock.reset();
        selection.clear();
        previousBob = mc.options.bobView().get();
        mc.options.bobView().set(false);
        // Keep the camera attached so vanilla skips rendering the local player's body.
        // The viewport event supplies the independent SLG position and angles.
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        KeyMapping.releaseAll();
        active = true;
        mc.setScreen(new StrategyScreen(this));
    }

    public void exit(Minecraft mc) {
        if (!active) {
            return;
        }
        active = false;
        ProjectionController.instance().cancel();
        frameClock.reset();
        selection.clear();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.options.bobView().set(previousBob);
        KeyMapping.releaseAll();
    }

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            exit(mc);
            return;
        }
        if (!active && mc.screen == null && CameraKeyMappings.consumeToggle()) {
            enter(mc, player);
        }
        if (!active) {
            return;
        }
        if (!(mc.screen instanceof StrategyScreen screen)) {
            exit(mc);
            return;
        }
        if (!mc.isWindowActive()) {
            screen.clearInputState();
            frameClock.reset();
            return;
        }
    }

    public void renderFrame(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || !acceptsInput(mc)) {
            frameClock.reset();
            if (mc.screen instanceof StrategyScreen screen && !mc.isWindowActive()) {
                screen.clearInputState();
            }
            return;
        }
        double elapsedSeconds = frameClock.step(System.nanoTime());
        double dx = (down(mc, GLFW.GLFW_KEY_D) ? 1 : 0) - (down(mc, GLFW.GLFW_KEY_A) ? 1 : 0);
        double dz = (down(mc, GLFW.GLFW_KEY_S) ? 1 : 0) - (down(mc, GLFW.GLFW_KEY_W) ? 1 : 0);
        double dy = (CameraKeyMappings.isMoveUpHeld(mc.getWindow().getWindow()) ? 1 : 0)
                - (CameraKeyMappings.isMoveDownHeld(mc.getWindow().getWindow()) ? 1 : 0);
        // A client-only camera cannot request remote chunks; stay in the loaded area.
        double limit = Math.max(16, (mc.options.renderDistance().get() - 2) * 16);
        state.move(dx, dz, dy, down(mc, GLFW.GLFW_KEY_LEFT_SHIFT), elapsedSeconds,
                player.getX(), player.getZ(), limit);
    }

    public void apply(ViewportEvent.ComputeCameraAngles event) {
        if (!active) {
            return;
        }
        event.setYaw((float) state.yaw());
        event.setPitch((float) state.pitch());
        event.setRoll(0);
        CameraState.Position position = state.cameraPosition();
        event.getCamera().setPosition(new Vec3(position.x(), position.y(), position.z()));
    }

    public void zoom(double scrollDelta) {
        if (acceptsInput(Minecraft.getInstance())) {
            state.zoom(scrollDelta);
        }
    }

    public void toggleAngleLock() {
        if (acceptsInput(Minecraft.getInstance())) {
            state.toggleAngleLock();
        }
    }

    public boolean angleLocked() {
        return state.angleLocked();
    }

    public boolean isActive() {
        return active;
    }

    @Nonnull
    public BlockSelectionController selection() {
        return selection;
    }

    public void selectAt(double x, double y, int screenWidth, int screenHeight) {
        if (acceptsInput(Minecraft.getInstance()) && screenWidth > 0 && screenHeight > 0) {
            selection.requestSelection(x / screenWidth, y / screenHeight);
        }
    }

    public void rotate(double deltaX, double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        if (acceptsInput(mc)) {
            double sensitivity = ROTATION_SENSITIVITY * mc.getWindow().getGuiScale();
            state.rotate(deltaX * sensitivity, deltaY * sensitivity);
        }
    }

    public void drag(double deltaX, double deltaY) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !acceptsInput(mc)) {
            return;
        }
        // Screen drag deltas use GUI pixels; using GUI height keeps GUI scales consistent.
        double worldUnitsPerPixel = 2 * state.distance() * Math.tan(Math.toRadians(mc.options.fov().get()) / 2)
                / Math.max(1, mc.getWindow().getGuiScaledHeight());
        double limit = Math.max(16, (mc.options.renderDistance().get() - 2) * 16);
        state.drag(deltaX, deltaY, worldUnitsPerPixel, player.getX(), player.getZ(), limit);
    }

    private boolean acceptsInput(Minecraft mc) {
        return active && mc.player != null && mc.level != null
                && mc.screen instanceof StrategyScreen screen && screen.allowsCameraInput() && mc.isWindowActive();
    }

    private static boolean down(Minecraft mc, int key) {
        return InputConstants.isKeyDown(mc.getWindow().getWindow(), key);
    }
}
