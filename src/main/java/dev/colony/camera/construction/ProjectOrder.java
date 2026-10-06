package dev.colony.camera.construction;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** Priority is a strict ordering key, not a term in a distance/utility score. */
public record ProjectOrder(@Nonnull UUID id, int priority, long sequence) {
    public static final int MIN_PRIORITY = 1;
    public static final int MAX_PRIORITY = 9;
    public static final Comparator<ProjectOrder> COMPARATOR = Comparator.comparingInt((ProjectOrder checkedElement) -> Objects.requireNonNull(checkedElement).priority())
            .reversed().thenComparingLong((ProjectOrder checkedElement) -> Objects.requireNonNull(checkedElement).sequence()).thenComparing((ProjectOrder checkedElement) -> Objects.requireNonNull(checkedElement).id());
    public ProjectOrder {
        Objects.requireNonNull(id);
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY || sequence < 0) throw new IllegalArgumentException("Invalid order");
    }
}
