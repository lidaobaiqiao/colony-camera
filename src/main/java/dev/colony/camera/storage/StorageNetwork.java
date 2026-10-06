package dev.colony.camera.storage;

import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import javax.annotation.Nullable;
import java.util.*;

/** Loaded, face-connected nodes only. Multiple ports resolve the same controller identity. */
@javax.annotation.ParametersAreNonnullByDefault
public final class StorageNetwork {
    public static final int MAX_NODES = 256;
    public record Component(String status, @Nullable BlockPos controller, List<MachineBlockEntity> warehouses, boolean connected) {
        public boolean valid() { return status.equals("connected"); }
    }
    private record Key(BlockPos pos, UUID owner) {}
    private static final Map<ServerLevel, Map<Key, Component>> CACHE = new WeakHashMap<>();
    private static final Map<ServerLevel, Long> TIMES = new WeakHashMap<>();
    private StorageNetwork() {}
    public static void invalidate(ServerLevel level) { CACHE.remove(level); }
    public static Component find(ServerLevel level, BlockPos origin, UUID owner) {
        if (!Objects.equals(TIMES.get(level), level.getGameTime())) { CACHE.remove(level); TIMES.put(level, level.getGameTime()); }
        Map<Key, Component> cache = CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        Key key = new Key(origin, owner); Component known = cache.get(key); if (known != null) return known;
        Set<BlockPos> visited = new HashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(origin);
        List<MachineBlockEntity> boxes = new ArrayList<>(); List<BlockPos> controllers = new ArrayList<>();
        while (!queue.isEmpty()) {
            BlockPos pos = queue.remove(); if (visited.contains(pos) || !GroundResources.loaded(level, Objects.requireNonNull(pos))) continue;
            if (!(level.getBlockEntity(Objects.requireNonNull(pos)) instanceof MachineBlockEntity node) || !node.permits(owner)
                    || node.kind() == ColonyContent.Kind.PRINTING_CORE || node.kind() == ColonyContent.Kind.COLONY_WORKBENCH || node.kind() == ColonyContent.Kind.RESEARCH_DESK) continue;
            visited.add(pos);
            if (visited.size() > MAX_NODES) return new Component("too_large", null, List.of(), true);
            if (node.kind() == ColonyContent.Kind.NETWORK_CONTROLLER) controllers.add(pos);
            if (node.kind() == ColonyContent.Kind.WAREHOUSE && !node.inventory.readOnly()) boxes.add(node);
            for (Direction direction : Direction.values()) queue.add(pos.relative(Objects.requireNonNull(direction)));
        }
        boxes.sort(Comparator.comparingLong(box -> box.getBlockPos().asLong()));
        Component result = new Component(controllers.size() == 1 ? "connected" : controllers.isEmpty() ? "no_controller" : "multiple_controllers",
                controllers.size() == 1 ? controllers.get(0) : null, List.copyOf(boxes), visited.size() > 1);
        for (BlockPos pos : visited) cache.put(new Key(Objects.requireNonNull(pos), owner), result);
        return result;
    }
}
