package dev.colony.camera;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.network.ConstructionNetwork;
import dev.colony.camera.content.ColonyContent;

@Mod(ColonyCamera.ID)
public final class ColonyCamera {
    public static final String ID = "colonycamera";

    @SuppressWarnings("removal")
    public ColonyCamera() {
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(net.minecraftforge.fml.config.ModConfig.Type.CLIENT,
                dev.colony.camera.config.SelectionConfig.SPEC);
        ColonyEntities.TYPES.register(FMLJavaModLoadingContext.get().getModEventBus());
        ColonyContent.register(java.util.Objects.requireNonNull(FMLJavaModLoadingContext.get().getModEventBus()));
        ConstructionNetwork.register();
        dev.colony.camera.work.WorkNetwork.register();
    }
}
