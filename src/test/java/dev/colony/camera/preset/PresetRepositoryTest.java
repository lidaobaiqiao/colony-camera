package dev.colony.camera.preset;

import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.planning.ProjectionRepository;
import org.junit.jupiter.api.Test;
import java.util.Objects;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PresetRepositoryTest {
    @TempDir Path directory;

    @Test
    void saveRenameReloadAndDeleteUseIdsRatherThanUserEnteredPaths() throws Exception {
        var repository = new PresetRepository(Objects.requireNonNull(directory));
        var preset = PresetCodecTest.sample().renamed("../我的房屋");
        repository.save(preset);
        assertTrue(Files.exists(directory.resolve(preset.id() + ".json")));
        assertEquals(preset, repository.load().presets().get(0));
        var renamed = preset.renamed("房屋第二版"); repository.save(renamed);
        assertEquals(1, repository.load().presets().size()); assertEquals(renamed, repository.load().presets().get(0));
        repository.delete(preset.id()); assertTrue(repository.load().presets().isEmpty());
    }

    @Test
    void malformedFutureAndIdMismatchedFilesAreReportedAndRetained() throws Exception {
        var repository = new PresetRepository(Objects.requireNonNull(directory)); var valid = PresetCodecTest.sample(); repository.save(valid);
        Files.writeString(Objects.requireNonNull(directory.resolve("broken.json")), "{broken");
        var future = PresetCodec.encode(PresetCodecTest.sample()); future.addProperty("schemaVersion", 100);
        PresetCodec.write(Objects.requireNonNull(directory.resolve("future.json")), future);
        PresetCodec.write(Objects.requireNonNull(directory.resolve("mismatch.json")), PresetCodec.encode(PresetCodecTest.sample()));
        var loaded = repository.load();
        assertEquals(1, loaded.presets().size()); assertEquals(3, loaded.rejected().size());
        assertTrue(Files.exists(Objects.requireNonNull(directory.resolve("future.json")))); assertTrue(Files.exists(Objects.requireNonNull(directory.resolve("broken.json"))));
    }

    @Test
    void deletingLibraryEntryDoesNotDeleteOrAlterAnExistingProjection() throws Exception {
        var presets = new PresetRepository(Objects.requireNonNull(directory.resolve("presets")));
        var projections = new ProjectionRepository(Objects.requireNonNull(directory.resolve("world")));
        var preset = PresetCodecTest.sample(); presets.save(preset);
        var placement = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, 40, 80, -20, 2, false, true);
        projections.save(placement); presets.delete(preset.id());
        assertEquals(placement, projections.load().placements().get(0));
        projections.save(placement.visibility(false)); assertFalse(projections.load().placements().get(0).visible());
        projections.delete(placement.id()); assertTrue(projections.load().placements().isEmpty());
    }

    @Test
    void separateWorldDimensionDirectoriesDoNotShareProjections() throws Exception {
        var overworld = new ProjectionRepository(Objects.requireNonNull(directory.resolve("world-a/overworld")));
        var nether = new ProjectionRepository(Objects.requireNonNull(directory.resolve("world-a/nether")));
        var other = new ProjectionRepository(Objects.requireNonNull(directory.resolve("world-b/overworld")));
        overworld.save(new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), PresetCodecTest.sample(), 0, 64, 0, 0, false, true));
        assertEquals(1, overworld.load().placements().size()); assertTrue(nether.load().placements().isEmpty()); assertTrue(other.load().placements().isEmpty());
    }
}
