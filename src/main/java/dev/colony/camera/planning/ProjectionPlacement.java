package dev.colony.camera.planning;

import dev.colony.camera.preset.BlockPreset;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.UUID;

/** The anchor is the lower corner of the transformed bounding box. */
@ParametersAreNonnullByDefault
public record ProjectionPlacement(@Nonnull UUID id, @Nonnull BlockPreset preset, int x, int y, int z,
                                  int quarterTurns, boolean mirrored, boolean visible) {
    public ProjectionPlacement {
        Objects.requireNonNull(id);
        Objects.requireNonNull(preset);
        quarterTurns = Math.floorMod(quarterTurns, 4);
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || Math.abs((long) y) > 4096) {
            throw new IllegalArgumentException("Invalid projection anchor");
        }
    }

    public int width() { return quarterTurns % 2 == 0 ? preset.sizeX() : preset.sizeZ(); }
    public int depth() { return quarterTurns % 2 == 0 ? preset.sizeZ() : preset.sizeX(); }

    @Nonnull
    public Position transform(BlockPreset.Cell cell) {
        int localX = mirrored ? preset.sizeX() - 1 - cell.x() : cell.x();
        int localZ = cell.z();
        return switch (quarterTurns) {
            case 1 -> new Position(x + preset.sizeZ() - 1 - localZ, y + cell.y(), z + localX);
            case 2 -> new Position(x + preset.sizeX() - 1 - localX, y + cell.y(), z + preset.sizeZ() - 1 - localZ);
            case 3 -> new Position(x + localZ, y + cell.y(), z + preset.sizeX() - 1 - localX);
            default -> new Position(x + localX, y + cell.y(), z + localZ);
        };
    }

    @Nonnull
    public ProjectionPlacement moved(int dx, int dy, int dz) {
        return new ProjectionPlacement(id, preset, Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz), quarterTurns, mirrored, visible);
    }

    @Nonnull
    public ProjectionPlacement at(int px, int py, int pz) {
        return new ProjectionPlacement(id, preset, px, py, pz, quarterTurns, mirrored, visible);
    }

    @Nonnull
    public ProjectionPlacement rotated() {
        return new ProjectionPlacement(id, preset, x, y, z, quarterTurns + 1, mirrored, visible);
    }

    @Nonnull
    public ProjectionPlacement mirror() {
        return new ProjectionPlacement(id, preset, x, y, z, quarterTurns, !mirrored, visible);
    }

    @Nonnull
    public ProjectionPlacement visibility(boolean value) {
        return new ProjectionPlacement(id, preset, x, y, z, quarterTurns, mirrored, value);
    }

    public record Position(int x, int y, int z) {}
}
