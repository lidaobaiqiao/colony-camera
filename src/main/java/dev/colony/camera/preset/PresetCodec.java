package dev.colony.camera.preset;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Versioned palette format; invalid/unknown versions are rejected, never guessed. */
@ParametersAreNonnullByDefault
public final class PresetCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_FILE_BYTES = 4 * 1024 * 1024;

    private PresetCodec() {}

    @Nonnull
    public static JsonObject encode(BlockPreset preset) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("id", preset.id().toString());
        root.addProperty("name", preset.name());
        root.addProperty("sizeX", preset.sizeX());
        root.addProperty("sizeY", preset.sizeY());
        root.addProperty("sizeZ", preset.sizeZ());
        Map<BlockPreset.State, Integer> palette = new LinkedHashMap<>();
        JsonArray blocks = new JsonArray();
        for (BlockPreset.Cell cell : preset.blocks()) {
            int index = palette.computeIfAbsent(cell.state(), ignored -> palette.size());
            JsonArray row = new JsonArray();
            row.add(cell.x()); row.add(cell.y()); row.add(cell.z()); row.add(index);
            blocks.add(row);
        }
        JsonArray states = new JsonArray();
        for (BlockPreset.State state : palette.keySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("block", state.block());
            JsonObject properties = new JsonObject();
            state.properties().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> properties.addProperty(entry.getKey(), entry.getValue()));
            item.add("properties", properties);
            states.add(item);
        }
        root.add("palette", states);
        root.add("blocks", blocks);
        return root;
    }

    @Nonnull
    public static BlockPreset decode(JsonObject root) {
        requireVersion(root);
        JsonArray states = Objects.requireNonNull(root.getAsJsonArray("palette"));
        JsonArray positions = Objects.requireNonNull(root.getAsJsonArray("blocks"));
        if (states.isEmpty() || states.size() > BlockPreset.MAX_BLOCKS || positions.size() > BlockPreset.MAX_BLOCKS) {
            throw new IllegalArgumentException("Invalid palette or block count");
        }
        var palette = new ArrayList<BlockPreset.State>();
        for (JsonElement element : states) {
            JsonObject item = Objects.requireNonNull(element.getAsJsonObject());
            Map<String, String> properties = new LinkedHashMap<>();
            for (var entry : Objects.requireNonNull(item.getAsJsonObject("properties")).entrySet()) {
                properties.put(entry.getKey(), entry.getValue().getAsString());
            }
            palette.add(new BlockPreset.State(string(item, "block"), properties));
        }
        var cells = new ArrayList<BlockPreset.Cell>();
        for (JsonElement element : positions) {
            JsonArray row = Objects.requireNonNull(element.getAsJsonArray());
            if (row.size() != 4) throw new IllegalArgumentException("Invalid position row");
            int index = integer(Objects.requireNonNull(row.get(3)));
            if (index < 0 || index >= palette.size()) throw new IllegalArgumentException("Invalid palette index");
            cells.add(new BlockPreset.Cell(integer(Objects.requireNonNull(row.get(0))), integer(Objects.requireNonNull(row.get(1))),
                    integer(Objects.requireNonNull(row.get(2))), Objects.requireNonNull(palette.get(index))));
        }
        return new BlockPreset(Objects.requireNonNull(UUID.fromString(string(root, "id"))), string(root, "name"), number(root, "sizeX"),
                number(root, "sizeY"), number(root, "sizeZ"), cells);
    }

    @Nonnull
    public static JsonObject read(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Preset file exceeds size limit");
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Objects.requireNonNull(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (RuntimeException failure) {
            throw new IOException("Invalid preset file: " + file.getFileName(), failure);
        }
    }

    public static void write(Path file, JsonObject data) throws IOException {
        byte[] bytes = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(data).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Preset file exceeds size limit");
        Path parent = Objects.requireNonNull(file.toAbsolutePath().getParent());
        Files.createDirectories(parent);
        Path temporary = Objects.requireNonNull(Files.createTempFile(parent, ".projection-", ".tmp"));
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireVersion(JsonObject root) {
        if (number(root, "schemaVersion") != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported schemaVersion");
    }

    private static int number(JsonObject object, String key) { return integer(Objects.requireNonNull(object.get(key))); }
    private static int integer(JsonElement value) { return value.getAsBigDecimal().intValueExact(); }
    @Nonnull
    private static String string(JsonObject object, String key) { return Objects.requireNonNull(Objects.requireNonNull(object.get(key)).getAsString()); }
}
