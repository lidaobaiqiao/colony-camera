package dev.colony.camera.client.selection;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

/** An empty filter allows every type; matching ignores block state properties. */
@ParametersAreNonnullByDefault
public final class TypeSelection {
    public static final int MAX_TYPES = 64;
    @Nonnull private Set<String> types = Objects.requireNonNull(Set.of());
    public void set(Collection<String> values) {
        if (values.size() > MAX_TYPES || values.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 256)) throw new IllegalArgumentException("limit");
        types = Objects.requireNonNull(Set.copyOf(values));
    }
    public boolean matches(String type) { return types.isEmpty() || types.contains(type); }
    @Nonnull public Set<String> snapshot() { return types; }
}
