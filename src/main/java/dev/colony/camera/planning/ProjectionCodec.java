package dev.colony.camera.planning;

import com.google.gson.JsonObject;
import dev.colony.camera.preset.PresetCodec;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.UUID;

@ParametersAreNonnullByDefault
public final class ProjectionCodec {
    private ProjectionCodec() {}

    @Nonnull
    public static JsonObject encode(ProjectionPlacement placement) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", PresetCodec.SCHEMA_VERSION);
        root.addProperty("id", placement.id().toString());
        root.addProperty("x", placement.x()); root.addProperty("y", placement.y()); root.addProperty("z", placement.z());
        root.addProperty("quarterTurns", placement.quarterTurns());
        root.addProperty("mirrored", placement.mirrored()); root.addProperty("visible", placement.visible());
        // A snapshot keeps existing projections intact when the library entry is renamed or deleted.
        root.add("preset", PresetCodec.encode(placement.preset()));
        return root;
    }

    @Nonnull
    public static ProjectionPlacement decode(JsonObject root) {
        if (number(root, "schemaVersion") != PresetCodec.SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported schemaVersion");
        return new ProjectionPlacement(Objects.requireNonNull(UUID.fromString(string(root, "id"))), PresetCodec.decode(Objects.requireNonNull(root.getAsJsonObject("preset"))),
                number(root, "x"), number(root, "y"), number(root, "z"), number(root, "quarterTurns"),
                Objects.requireNonNull(root.get("mirrored")).getAsBoolean(), Objects.requireNonNull(root.get("visible")).getAsBoolean());
    }

    private static int number(JsonObject root, String key) { return Objects.requireNonNull(root.get(key)).getAsBigDecimal().intValueExact(); }
    @Nonnull
    private static String string(JsonObject root, String key) { return Objects.requireNonNull(Objects.requireNonNull(root.get(key)).getAsString()); }
}
