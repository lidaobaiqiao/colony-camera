package dev.colony.camera.work;

import dev.colony.camera.ColonyCamera;
import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

@Mod.EventBusSubscriber(modid = ColonyCamera.ID)
@ParametersAreNonnullByDefault
public final class WorkStations {
    private static final Map<ServerLevel, Set<BlockPos>> LOADED = new WeakHashMap<>();
    private WorkStations() {}
    public static void add(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof MachineBlockEntity machine && (machine.kind() == ColonyContent.Kind.COLONY_WORKBENCH || machine.kind() == ColonyContent.Kind.RESEARCH_DESK
                || machine.kind() == ColonyContent.Kind.RESIDENCE || machine.kind() == ColonyContent.Kind.ACADEMY
                || machine.kind() == ColonyContent.Kind.WATER_PUMP || machine.kind() == ColonyContent.Kind.MEETING_POINT))
            LOADED.computeIfAbsent(level, ignored -> new HashSet<>()).add(pos.immutable());
    }
    public static void remove(ServerLevel level, BlockPos pos) { var entries = LOADED.get(level); if (entries != null) entries.remove(pos); }
    public static List<BlockPos> owned(ServerLevel level, UUID owner) {
        return LOADED.getOrDefault(level, Set.of()).stream().filter(pos -> GroundResources.loaded(level, Objects.requireNonNull(pos))
                && level.getBlockEntity(Objects.requireNonNull(pos)) instanceof MachineBlockEntity machine && machine.permits(owner)).sorted().toList();
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) { if (event.getLevel() instanceof ServerLevel level) LOADED.remove(level); }
}
