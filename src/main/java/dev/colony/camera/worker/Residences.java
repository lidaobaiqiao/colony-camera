package dev.colony.camera.worker;

import dev.colony.camera.content.ColonyContent;
import dev.colony.camera.content.MachineBlockEntity;
import dev.colony.camera.resources.GroundResources;
import dev.colony.camera.work.WorkStations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

/** Rooms are discovered from player-built walls, floor, roof and a bed; markers never create housing alone. */
@ParametersAreNonnullByDefault
public final class Residences {
    public record Room(@Nonnull BlockPos marker, @Nonnull BlockPos bed, @Nonnull Set<BlockPos> floor) {}
    public record Details(@Nullable Room room, @Nonnull String reason, int area) {}
    private Residences() {}
    @Nullable public static Room inspect(ServerLevel level, BlockPos marker) {
        return details(level, marker).room();
    }
    @Nonnull public static Details details(ServerLevel level, BlockPos marker) {
        if (!GroundResources.loaded(level, marker) || !(level.getBlockEntity(marker) instanceof MachineBlockEntity machine)
                || machine.kind() != ColonyContent.Kind.RESIDENCE) return new Details(null, "unloaded", 0);
        if (machine.facilityReadOnly()) return new Details(null, "readonly", 0);
        var queue = new ArrayDeque<BlockPos>(); var cells = new HashSet<BlockPos>();
        for (Direction direction : Direction.Plane.HORIZONTAL) queue.add(Objects.requireNonNull(marker.relative(Objects.requireNonNull(direction))));
        BlockPos bed = null;
        while (!queue.isEmpty()) {
            BlockPos pos = Objects.requireNonNull(queue.removeFirst());
            if (!GroundResources.loaded(level, pos) || !GroundResources.loaded(level, Objects.requireNonNull(pos.above(5)))) return new Details(null, "unloaded", cells.size());
            BlockPos above = Objects.requireNonNull(pos.above()), below = Objects.requireNonNull(pos.below());
            var bottom = level.getBlockState(pos); var top = level.getBlockState(above);
            boolean isBed = bottom.getBlock() instanceof BedBlock;
            if (bottom.getBlock() instanceof DoorBlock || !isBed && !bottom.getCollisionShape(level, pos).isEmpty()
                    || !top.getCollisionShape(level, above).isEmpty()) continue;
            if (!cells.add(pos)) continue;
            if (cells.size() > 128) return new Details(null, "too_large", cells.size());
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return new Details(null, "floor", cells.size());
            boolean roof = false;
            for (int height = 2; height <= 5; height++) {
                BlockPos roofPos = Objects.requireNonNull(pos.above(height));
                if (!level.getBlockState(roofPos).getCollisionShape(level, roofPos).isEmpty()) { roof = true; break; }
            }
            if (!roof) return new Details(null, "roof", cells.size());
            if (isBed && bottom.getValue(Objects.requireNonNull(BedBlock.PART)) == BedPart.HEAD) {
                if (bed != null && !bed.equals(pos)) return new Details(null, "beds", cells.size());
                Direction facing = Objects.requireNonNull(bottom.getValue(Objects.requireNonNull(BedBlock.FACING)));
                BlockPos foot = Objects.requireNonNull(pos.relative(Objects.requireNonNull(facing.getOpposite())));
                if (!GroundResources.loaded(level, foot)) return new Details(null, "unloaded", cells.size());
                var other = level.getBlockState(foot);
                if (other.getBlock() != bottom.getBlock() || other.getValue(Objects.requireNonNull(BedBlock.PART)) != BedPart.FOOT
                        || other.getValue(Objects.requireNonNull(BedBlock.FACING)) != facing) return new Details(null, "bed_incomplete", cells.size());
                bed = pos;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) queue.add(Objects.requireNonNull(pos.relative(Objects.requireNonNull(direction))));
        }
        if (cells.size() < 4) return new Details(null, "too_small", cells.size());
        return bed == null ? new Details(null, "bed_missing", cells.size()) : new Details(new Room(marker, bed, Objects.requireNonNull(Set.copyOf(cells))), "valid", cells.size());
    }
    @Nonnull public static List<Room> rooms(ServerLevel level, UUID owner) {
        var result = new java.util.ArrayList<Room>(); var beds = new HashSet<BlockPos>();
        for (BlockPos pos : WorkStations.owned(level, owner)) {
            Room room = inspect(level, Objects.requireNonNull(pos));
            if (room != null && beds.add(room.bed())) result.add(room);
        }
        return Objects.requireNonNull(List.copyOf(result));
    }
    @Nullable public static Room home(ServerLevel level, WorkerEntity worker) {
        BlockPos pos = worker.home(); UUID owner = worker.owner();
        if (pos == null || owner == null || !GroundResources.loaded(level, pos) || !(level.getBlockEntity(pos) instanceof MachineBlockEntity machine)
                || !machine.permits(owner) || !worker.getUUID().equals(machine.resident())) return null;
        return inspect(level, pos);
    }
    @Nullable public static Room available(ServerLevel level, UUID owner) {
        for (Room room : rooms(level, owner)) {
            if (!(level.getBlockEntity(room.marker()) instanceof MachineBlockEntity markerMachine) || !markerMachine.automaticHousing()) continue;
            boolean occupied = false;
            // A second marker cannot assign the same room twice, even when its resident is unloaded.
            for (BlockPos marker : WorkStations.owned(level, owner)) {
                Room other = inspect(level, Objects.requireNonNull(marker));
                if (other != null && other.bed().equals(room.bed()) && level.getBlockEntity(marker) instanceof MachineBlockEntity machine && machine.resident() != null) { occupied = true; break; }
            }
            if (!occupied) return room;
        }
        return null;
    }
    public static boolean assign(ServerLevel level, WorkerEntity worker) {
        UUID owner = worker.owner();
        if (owner == null || worker.stateReadOnly()) return false;
        if (home(level, worker) != null) return true;
        Room room = available(level, owner);
        return room != null && assign(level, worker, room.marker());
    }
    public static boolean assign(ServerLevel level, WorkerEntity worker, BlockPos marker) {
        UUID owner = worker.owner(); Room room = inspect(level, marker);
        if (owner == null || worker.stateReadOnly() || room == null || !(level.getBlockEntity(marker) instanceof MachineBlockEntity machine) || !machine.permits(owner)) return false;
        for (BlockPos otherMarker : WorkStations.owned(level, owner)) {
            Room other = inspect(level, Objects.requireNonNull(otherMarker));
            if (other != null && other.bed().equals(room.bed()) && level.getBlockEntity(otherMarker) instanceof MachineBlockEntity otherMachine
                    && otherMachine.resident() != null && !worker.getUUID().equals(otherMachine.resident())) return false;
        }
        BlockPos old = worker.home();
        if (old != null && !old.equals(marker) && !GroundResources.loaded(level, old)) return false;
        if (old != null && level.getBlockEntity(old) instanceof MachineBlockEntity previous && worker.getUUID().equals(previous.resident())) previous.resident(null);
        machine.resident(Objects.requireNonNull(worker.getUUID())); worker.home(room.marker()); return true;
    }
    public static void release(ServerLevel level, WorkerEntity worker) {
        BlockPos pos = worker.home();
        if (pos != null && GroundResources.loaded(level, pos) && level.getBlockEntity(pos) instanceof MachineBlockEntity machine && worker.getUUID().equals(machine.resident())) machine.resident(null);
    }
}
