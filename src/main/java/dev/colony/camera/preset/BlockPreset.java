package dev.colony.camera.preset;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A reusable design, independent of any world, renderer or running construction. */
@ParametersAreNonnullByDefault
public record BlockPreset(@Nonnull UUID id, @Nonnull String name, int sizeX, int sizeY, int sizeZ,
                          @Nonnull List<Cell> blocks) {
    public static final int MAX_BLOCKS = 8192;
    public static final int MAX_SIZE = 256;

    public BlockPreset {
        Objects.requireNonNull(id);
        name = Objects.requireNonNull(Objects.requireNonNull(name).strip());
        if (name.isEmpty() || name.length() > 48 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid preset name");
        }
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1 || sizeX > MAX_SIZE || sizeY > MAX_SIZE || sizeZ > MAX_SIZE) {
            throw new IllegalArgumentException("Invalid preset dimensions");
        }
        blocks = Objects.requireNonNull(List.copyOf(blocks));
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("Invalid preset block count");
        }
        var occupied = new HashSet<String>();
        for (Cell cell : blocks) {
            if (cell.x() < 0 || cell.x() >= sizeX || cell.y() < 0 || cell.y() >= sizeY
                    || cell.z() < 0 || cell.z() >= sizeZ
                    || !occupied.add(cell.x() + "," + cell.y() + "," + cell.z())) {
                throw new IllegalArgumentException("Invalid or duplicate preset position");
            }
        }
    }

    @Nonnull
    public BlockPreset renamed(String value) {
        return new BlockPreset(id, value, sizeX, sizeY, sizeZ, blocks);
    }

    public record State(@Nonnull String block, @Nonnull Map<String, String> properties) {
        public State {
            if (!Objects.requireNonNull(block).matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || block.length() > 256
                    || block.equals("minecraft:air") || block.equals("minecraft:cave_air") || block.equals("minecraft:void_air")) {
                throw new IllegalArgumentException("Invalid preset block identifier");
            }
            properties = Objects.requireNonNull(Map.copyOf(properties));
            if (properties.size() > 32 || properties.entrySet().stream().anyMatch(entry ->
                    !entry.getKey().matches("[a-z0-9_]+") || entry.getKey().length() > 64
                            || entry.getValue().isEmpty() || entry.getValue().length() > 128)) {
                throw new IllegalArgumentException("Invalid block properties");
            }
        }
    }

    public record Cell(int x, int y, int z, @Nonnull State state) {
        public Cell { Objects.requireNonNull(state); }
    }
}
