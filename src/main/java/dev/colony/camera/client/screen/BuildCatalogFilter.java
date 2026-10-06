package dev.colony.camera.client.screen;

import javax.annotation.Nonnull;
import java.util.*;

/** Text and preferences are supplied by the UI; this model does not depend on Minecraft. */
public final class BuildCatalogFilter {
    public record Row(@Nonnull String id, @Nonnull String name, @Nonnull String category, int status) {}
    private BuildCatalogFilter() {}
    @Nonnull public static List<Row> filter(List<Row> rows, String query, String category, boolean availableOnly, Set<String> favorites) {
        var tokens = query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        return Objects.requireNonNull(rows.stream().filter(row -> !availableOnly || row.status() == 1 || row.status() == 2)
                .filter(row -> category.equals("all") || category.equals("favorites") ? category.equals("all") || favorites.contains(row.id()) : category.equals(row.category()))
                .filter(row -> Arrays.stream(tokens).allMatch(token -> (row.name() + " " + row.id()).toLowerCase(Locale.ROOT).contains(token)))
                .sorted(Comparator.<Row, Boolean>comparing(row -> !favorites.contains(row.id())).thenComparing(row -> Objects.requireNonNull(row).name()).thenComparing(row -> Objects.requireNonNull(row).id())).toList());
    }
}
