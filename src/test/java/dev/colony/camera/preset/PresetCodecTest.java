package dev.colony.camera.preset;

import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.planning.ProjectionCodec;
import org.junit.jupiter.api.Test;
import javax.annotation.Nonnull;
import java.util.Objects;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PresetCodecTest {
    @TempDir Path directory;

    @Test
    void paletteRoundTripPreservesPropertiesSparseCellsAndUnicodeNames() {
        var preset = sample(); var json = PresetCodec.encode(preset);
        assertEquals(2, json.getAsJsonArray("palette").size());
        assertEquals(3, json.getAsJsonArray("blocks").size());
        assertEquals(preset, PresetCodec.decode(json));
    }

    @Test
    void unsupportedVersionsAndMissingVersionsAreRejected() {
        var json = PresetCodec.encode(sample()); json.addProperty("schemaVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> PresetCodec.decode(json));
        json.remove("schemaVersion"); assertThrows(RuntimeException.class, () -> PresetCodec.decode(json));
    }

    @Test
    void fractionalPositionsAndInvalidPaletteIndicesCannotBeTruncatedOrGuessed() {
        var json = PresetCodec.encode(sample());
        json.getAsJsonArray("blocks").get(0).getAsJsonArray().set(0, new com.google.gson.JsonPrimitive(0.5));
        assertThrows(RuntimeException.class, () -> PresetCodec.decode(json));
        var broken = PresetCodec.encode(sample());
        broken.getAsJsonArray("blocks").get(0).getAsJsonArray().set(3, new com.google.gson.JsonPrimitive(999));
        assertThrows(IllegalArgumentException.class, () -> PresetCodec.decode(broken));
    }

    @Test
    void duplicateCellsInFilesAreRejected() {
        var json = PresetCodec.encode(sample());
        json.getAsJsonArray("blocks").add(json.getAsJsonArray("blocks").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> PresetCodec.decode(json));
    }

    @Test
    void projectionRoundTripKeepsItsOwnDesignSnapshot() {
        var placement = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), sample(), -25, 70, 49, 3, true, false);
        assertEquals(placement, ProjectionCodec.decode(ProjectionCodec.encode(placement)));
        assertNotEquals(placement.preset(), placement.preset().renamed("新版设计"));
        assertEquals("转角住宅", placement.preset().name());
    }

    @Test
    void failedOversizedWritePreservesThePreviousFileAndLeavesNoTemporaryFiles() throws Exception {
        Path path = Objects.requireNonNull(directory.resolve("preset.json")); var json = PresetCodec.encode(sample());
        PresetCodec.write(path, json); String before = Files.readString(path);
        json.addProperty("oversized", "a".repeat((int) PresetCodec.MAX_FILE_BYTES + 1));
        assertThrows(java.io.IOException.class, () -> PresetCodec.write(path, json));
        assertEquals(before, Files.readString(path));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }

    @Nonnull static BlockPreset sample() {
        var stone = new BlockPreset.State("minecraft:stone", Objects.requireNonNull(Map.of()));
        var stairs = new BlockPreset.State("minecraft:oak_stairs", Objects.requireNonNull(Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false")));
        return new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "转角住宅", 3, 2, 2, Objects.requireNonNull(List.of(new BlockPreset.Cell(0, 0, 0, stone), new BlockPreset.Cell(2, 0, 0, stone), new BlockPreset.Cell(1, 1, 1, stairs))));
    }
}
