package dev.colony.camera.worker;

import dev.colony.camera.ColonyCamera;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import javax.annotation.Nonnull;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = ColonyCamera.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ColonyEntities {
    @Nonnull public static final DeferredRegister<EntityType<?>> TYPES = Objects.requireNonNull(DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ColonyCamera.ID));
    @Nonnull public static final RegistryObject<EntityType<WorkerEntity>> WORKER = Objects.requireNonNull(TYPES.register("worker", () ->
            EntityType.Builder.of(WorkerEntity::new, MobCategory.CREATURE).sized(0.6F, 1.8F).clientTrackingRange(10).build(ColonyCamera.ID + ":worker")));
    private ColonyEntities() {}
    @SubscribeEvent public static void attributes(EntityAttributeCreationEvent event) { event.put(WORKER.get(), WorkerEntity.attributes().build()); }
}
