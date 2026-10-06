package dev.colony.camera.client.planning;

import dev.colony.camera.planning.ProjectionPlacement;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Immutable window-owned draft; accepted replies can arrive while another window is active. */
public record BlockDraftState(@Nonnull ProjectionPlacement placement, @Nonnull String item,
                              boolean following, boolean cursorValid, int heightOffset) {
    public BlockDraftState { Objects.requireNonNull(placement); Objects.requireNonNull(item); }
    @Nonnull public BlockDraftState accepted(@Nonnull UUID id) {
        if (!placement.id().equals(id)) return this;
        var next = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), placement.preset(), placement.x(), placement.y(), placement.z(), placement.quarterTurns(), placement.mirrored(), true);
        return new BlockDraftState(next, item, true, false, heightOffset);
    }
}
