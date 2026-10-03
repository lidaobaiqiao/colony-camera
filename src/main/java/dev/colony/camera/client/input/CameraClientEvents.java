package dev.colony.camera.client.input;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.client.camera.StrategyCameraController;
import dev.colony.camera.client.screen.StrategyScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderBlockScreenEffectEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.EnumSet;

/** Forge event adapter; gameplay and state belong to feature controllers. */
@Mod.EventBusSubscriber(modid = ColonyCamera.ID, value = Dist.CLIENT)
public final class CameraClientEvents {
    private static final EnumSet<VanillaGuiOverlay> HIDDEN_OVERLAYS = EnumSet.of(
            VanillaGuiOverlay.CROSSHAIR, VanillaGuiOverlay.HELMET, VanillaGuiOverlay.SPYGLASS,
            VanillaGuiOverlay.FROSTBITE, VanillaGuiOverlay.HOTBAR, VanillaGuiOverlay.ITEM_NAME,
            VanillaGuiOverlay.PLAYER_HEALTH, VanillaGuiOverlay.ARMOR_LEVEL, VanillaGuiOverlay.FOOD_LEVEL,
            VanillaGuiOverlay.AIR_LEVEL, VanillaGuiOverlay.MOUNT_HEALTH, VanillaGuiOverlay.JUMP_BAR,
            VanillaGuiOverlay.EXPERIENCE_BAR, VanillaGuiOverlay.POTION_ICONS);

    private CameraClientEvents() {
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            StrategyCameraController.instance().tick(Minecraft.getInstance());
        }
    }

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            StrategyCameraController.instance().renderFrame(Minecraft.getInstance());
        }
    }

    @SubscribeEvent
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        StrategyCameraController.instance().apply(event);
    }

    @SubscribeEvent
    public static void selection(RenderLevelStageEvent event) {
        StrategyCameraController controller = StrategyCameraController.instance();
        if (controller.isActive() && event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            Minecraft mc = Minecraft.getInstance();
            double x = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
            double y = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
            boolean blocked = mc.screen instanceof StrategyScreen screen && screen.blocksWorldPointer(x, y);
            controller.selection().render(mc, event, blocked);
        }
    }

    @SubscribeEvent
    public static void vanillaHighlight(RenderHighlightEvent.Block event) {
        if (StrategyCameraController.instance().isActive()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void player(RenderPlayerEvent.Pre event) {
        if (StrategyCameraController.instance().isActive() && event.getEntity() == Minecraft.getInstance().player) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void hand(RenderHandEvent event) {
        if (StrategyCameraController.instance().isActive()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void playerOverlay(RenderBlockScreenEffectEvent event) {
        if (StrategyCameraController.instance().isActive() && event.getPlayer() == Minecraft.getInstance().player) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void cameraOverlay(RenderGuiOverlayEvent.Pre event) {
        if (StrategyCameraController.instance().isActive()
                && HIDDEN_OVERLAYS.stream().anyMatch(overlay -> event.getOverlay().id().equals(overlay.id()))) {
            event.setCanceled(true);
        }
    }
}
