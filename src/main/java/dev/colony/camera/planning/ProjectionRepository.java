package dev.colony.camera.planning;

import dev.colony.camera.preset.PresetCodec;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ParametersAreNonnullByDefault
public final class ProjectionRepository {
    public static final int MAX_PLACEMENTS = 32;
    private final Path directory;
    public ProjectionRepository(Path directory) { this.directory = directory; }

    @Nonnull
    public Loaded load() throws IOException {
        var placements = new ArrayList<ProjectionPlacement>();
        var rejected = new ArrayList<String>();
        if (Files.isDirectory(directory)) {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().limit(512).toList()) {
                    try {
                        ProjectionPlacement placement = ProjectionCodec.decode(PresetCodec.read(Objects.requireNonNull(path)));
                        if (!path.getFileName().toString().equals(placement.id() + ".json") || placements.size() == MAX_PLACEMENTS) {
                            throw new IllegalArgumentException("ID mismatch or placement limit");
                        }
                        placements.add(placement);
                    } catch (IOException | RuntimeException failure) {
                        rejected.add(path.getFileName().toString());
                    }
                }
            }
        }
        return new Loaded(Objects.requireNonNull(List.copyOf(placements)), Objects.requireNonNull(List.copyOf(rejected)));
    }

    public void save(ProjectionPlacement placement) throws IOException {
        PresetCodec.write(file(placement.id()), ProjectionCodec.encode(placement));
    }
    public void delete(UUID id) throws IOException { Files.deleteIfExists(file(id)); }
    @Nonnull
    private Path file(UUID id) { return Objects.requireNonNull(directory.resolve(id + ".json")); }
    public record Loaded(@Nonnull List<ProjectionPlacement> placements, @Nonnull List<String> rejected) {}
}
