package dev.colony.camera.content;

import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.ColonyCamera;
import dev.colony.camera.storage.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

@Mod.EventBusSubscriber(modid = ColonyCamera.ID)
@javax.annotation.ParametersAreNonnullByDefault
public final class PrintingCores {
    private static final Map<ServerLevel, Set<BlockPos>> LOADED = new WeakHashMap<>();
    private PrintingCores() {}
    public static void add(ServerLevel level, BlockPos pos) { LOADED.computeIfAbsent(level, ignored -> new HashSet<>()).add(pos.immutable()); }
    public static void remove(ServerLevel level, BlockPos pos) { Set<BlockPos> positions = LOADED.get(level); if (positions != null) positions.remove(pos); }
    public static List<BlockPos> owned(ServerLevel level, UUID owner) {
        return LOADED.getOrDefault(level, Set.of()).stream().filter(pos -> GroundResources.loaded(level, Objects.requireNonNull(pos))
                && level.getBlockEntity(Objects.requireNonNull(pos)) instanceof MachineBlockEntity machine && machine.kind() == ColonyContent.Kind.PRINTING_CORE && machine.permits(owner))
                .sorted(Comparator.comparingLong((BlockPos checkedElement) -> Objects.requireNonNull(checkedElement).asLong())).toList();
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) { if (event.getLevel() instanceof ServerLevel level) { LOADED.remove(level); StorageNetwork.invalidate(level); } }
}
