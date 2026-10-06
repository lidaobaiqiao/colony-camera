package dev.colony.camera.preset;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ParametersAreNonnullByDefault
public final class PresetRepository {
    private final Path directory;
    public PresetRepository(Path directory) { this.directory = directory; }

    @Nonnull
    public Loaded load() throws IOException {
        var presets = new ArrayList<BlockPreset>();
        var rejected = new ArrayList<String>();
        if (Files.isDirectory(directory)) {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().limit(512).toList()) {
                    try {
                        BlockPreset preset = PresetCodec.decode(PresetCodec.read(Objects.requireNonNull(path)));
                        if (!path.getFileName().toString().equals(preset.id() + ".json")) throw new IllegalArgumentException("ID mismatch");
                        presets.add(preset);
                    } catch (IOException | RuntimeException failure) {
                        rejected.add(path.getFileName().toString());
                    }
                }
            }
        }
        presets.sort(Comparator.comparing((BlockPreset checkedElement) -> Objects.requireNonNull(checkedElement).name()).thenComparing((BlockPreset checkedElement) -> Objects.requireNonNull(checkedElement).id()));
        return new Loaded(Objects.requireNonNull(List.copyOf(presets)), Objects.requireNonNull(List.copyOf(rejected)));
    }

    public void save(BlockPreset preset) throws IOException { PresetCodec.write(file(preset.id()), PresetCodec.encode(preset)); }
    public void delete(UUID id) throws IOException { Files.deleteIfExists(file(id)); }
    @Nonnull
    private Path file(UUID id) { return Objects.requireNonNull(directory.resolve(id + ".json")); }
    public record Loaded(@Nonnull List<BlockPreset> presets, @Nonnull List<String> rejected) {}
}
