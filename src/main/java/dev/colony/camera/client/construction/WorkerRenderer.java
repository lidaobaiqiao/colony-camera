package dev.colony.camera.client.construction;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.worker.ColonyEntities;
import dev.colony.camera.worker.WorkerEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = ColonyCamera.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
@ParametersAreNonnullByDefault
public final class WorkerRenderer extends HumanoidMobRenderer<WorkerEntity, PlayerModel<WorkerEntity>> {
    @Nonnull private static final ResourceLocation TEXTURE = java.util.Objects.requireNonNull(ResourceLocation.tryParse("minecraft:textures/entity/player/wide/steve.png"));
    public WorkerRenderer(EntityRendererProvider.Context context) { super(context, new PlayerModel<>(Objects.requireNonNull(context.bakeLayer(Objects.requireNonNull(ModelLayers.PLAYER))), false), 0.5F); }
    @Override @Nonnull public ResourceLocation getTextureLocation(WorkerEntity worker) { return TEXTURE; }
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) { event.registerEntityRenderer(Objects.requireNonNull(ColonyEntities.WORKER.get()), WorkerRenderer::new); }
}
