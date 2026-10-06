package dev.colony.camera.config;

import net.minecraftforge.common.ForgeConfigSpec;
import javax.annotation.Nonnull;
import java.util.Objects;

/** Client-owned preferences; selecting blocks never changes server authority. */
public final class SelectionConfig {
    public static final int DEFAULT_SIMILAR_LIMIT = 64;
    public static final int MAX_SIMILAR_LIMIT = 8192;
    @Nonnull public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.IntValue SIMILAR_LIMIT;
    private static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> BUILD_FAVORITES;

    static {
        var builder = new ForgeConfigSpec.Builder();
        SIMILAR_LIMIT = builder.comment("Maximum connected blocks selected by one similar-selection click.")
                .defineInRange("similarSelectionLimit", DEFAULT_SIMILAR_LIMIT, 1, MAX_SIMILAR_LIMIT);
        BUILD_FAVORITES = builder.comment("Favorite block item IDs in the construction palette.")
                .defineListAllowEmpty("buildFavorites", java.util.List.of(), value -> value instanceof String id && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && id.length() <= 256);
        SPEC = Objects.requireNonNull(builder.build());
    }

    private SelectionConfig() {}

    public static int similarLimit() { return Objects.requireNonNull(SIMILAR_LIMIT.get()); }
    public static java.util.Set<String> buildFavorites() { return new java.util.LinkedHashSet<>(BUILD_FAVORITES.get()); }
    public static void favorite(String id) {
        var previous = BUILD_FAVORITES.get(); var values = buildFavorites();
        if (!values.remove(id) && values.size() < 256) values.add(id);
        BUILD_FAVORITES.set(java.util.List.copyOf(values));
        try { SPEC.save(); } catch (RuntimeException failure) { BUILD_FAVORITES.set(previous); throw failure; }
    }

    public static void similarLimit(int value) {
        if (value < 1 || value > MAX_SIMILAR_LIMIT) throw new IllegalArgumentException("Invalid similar selection limit");
        int previous = similarLimit();
        SIMILAR_LIMIT.set(value);
        try { SPEC.save(); }
        catch (RuntimeException failure) { SIMILAR_LIMIT.set(previous); throw failure; }
    }
}
