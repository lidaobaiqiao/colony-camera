package dev.colony.camera.construction;

import dev.colony.camera.ColonyCamera;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ColonyCamera.ID)
public final class ConstructionEvents {
    private ConstructionEvents() {}
    @SubscribeEvent public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) ConstructionManager.get(level).tick(level);
    }
}
