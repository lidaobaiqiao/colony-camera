package dev.colony.camera.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.colony.camera.ColonyCamera;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = ColonyCamera.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CameraKeyMappings {
    private static final KeyMapping TOGGLE = new KeyMapping("key.colonycamera.toggle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "key.categories.colonycamera");
    private static final KeyMapping ANGLE_LOCK = new KeyMapping("key.colonycamera.angle_lock",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.colonycamera");
    private static final KeyMapping MOVE_UP = new KeyMapping("key.colonycamera.move_up",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SPACE, "key.categories.colonycamera");
    private static final KeyMapping MOVE_DOWN = new KeyMapping("key.colonycamera.move_down",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, "key.categories.colonycamera");
    private static final KeyMapping ADD_SELECTION = new KeyMapping("key.colonycamera.add_selection",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.colonycamera");
    private static final KeyMapping VOLUME_SELECTION = new KeyMapping("key.colonycamera.volume_selection",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.colonycamera");
    private static final KeyMapping CLEAR_SELECTION = new KeyMapping("key.colonycamera.clear_selection",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, "key.categories.colonycamera");

    private CameraKeyMappings() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(ANGLE_LOCK);
        event.register(MOVE_UP);
        event.register(MOVE_DOWN);
        event.register(ADD_SELECTION);
        event.register(VOLUME_SELECTION);
        event.register(CLEAR_SELECTION);
    }

    public static boolean consumeToggle() {
        return TOGGLE.consumeClick();
    }

    public static boolean matchesToggle(int key, int scan) {
        return TOGGLE.matches(key, scan);
    }

    public static Component toggleKeyName() {
        return TOGGLE.getTranslatedKeyMessage();
    }

    public static boolean matchesAngleLock(int key, int scan) {
        return ANGLE_LOCK.matches(key, scan);
    }

    public static Component angleLockKeyName() {
        return ANGLE_LOCK.getTranslatedKeyMessage();
    }

    public static boolean matchesAddSelection(int key, int scan) {
        return ADD_SELECTION.matches(key, scan);
    }

    public static boolean matchesVolumeSelection(int key, int scan) {
        return VOLUME_SELECTION.matches(key, scan);
    }

    public static boolean matchesClearSelection(int key, int scan) {
        return CLEAR_SELECTION.matches(key, scan);
    }

    public static boolean matchesAddSelectionMouse(int button) {
        return ADD_SELECTION.matchesMouse(button);
    }

    public static boolean matchesVolumeSelectionMouse(int button) {
        return VOLUME_SELECTION.matchesMouse(button);
    }

    public static boolean matchesClearSelectionMouse(int button) {
        return CLEAR_SELECTION.matchesMouse(button);
    }

    public static Component addSelectionKeyName() {
        return ADD_SELECTION.getTranslatedKeyMessage();
    }

    public static Component volumeSelectionKeyName() {
        return VOLUME_SELECTION.getTranslatedKeyMessage();
    }

    public static Component clearSelectionKeyName() {
        return CLEAR_SELECTION.getTranslatedKeyMessage();
    }

    public static boolean isMoveUpHeld(long window) {
        return isPhysicallyDown(MOVE_UP, window);
    }

    public static boolean isMoveDownHeld(long window) {
        return isPhysicallyDown(MOVE_DOWN, window);
    }

    public static Component moveUpKeyName() {
        return MOVE_UP.getTranslatedKeyMessage();
    }

    public static Component moveDownKeyName() {
        return MOVE_DOWN.getTranslatedKeyMessage();
    }

    public static void recordElevationKey(int key, int scan, boolean pressed) {
        // Unknown key symbols use scan codes; keep only our mappings' held state.
        if (MOVE_UP.getKey().getType() == InputConstants.Type.SCANCODE && MOVE_UP.matches(key, scan)) {
            MOVE_UP.setDown(pressed);
        }
        if (MOVE_DOWN.getKey().getType() == InputConstants.Type.SCANCODE && MOVE_DOWN.matches(key, scan)) {
            MOVE_DOWN.setDown(pressed);
        }
    }

    public static void clearElevationKeys() {
        MOVE_UP.setDown(false);
        MOVE_DOWN.setDown(false);
    }

    private static boolean isPhysicallyDown(KeyMapping mapping, long window) {
        // StrategyScreen consumes input, so vanilla KeyMapping.isDown() is not updated.
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM && key.getValue() != GLFW.GLFW_KEY_UNKNOWN) {
            return InputConstants.isKeyDown(window, key.getValue());
        }
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == InputConstants.Type.SCANCODE) {
            return mapping.isDown();
        }
        return false;
    }
}
