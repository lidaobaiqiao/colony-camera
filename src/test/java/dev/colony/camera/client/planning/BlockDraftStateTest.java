package dev.colony.camera.client.planning;

import dev.colony.camera.planning.ProjectionPlacement;
import dev.colony.camera.preset.BlockPreset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockDraftStateTest {
    @Test void acceptedReplyOnlyAdvancesTheMatchingWindowsDraft() {
        var preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "stone", 1, 1, 1,
                Objects.requireNonNull(List.of(new BlockPreset.Cell(0, 0, 0, new BlockPreset.State("minecraft:stone", Objects.requireNonNull(Map.of()))))));
        var first = new BlockDraftState(new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, 3, 64, 8, 2, true, true), "minecraft:stone", false, true, 4);
        var second = new BlockDraftState(new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, 7, 70, 12, 1, false, true), "minecraft:stone", true, false, -2);
        var next = first.accepted(first.placement().id());
        assertSame(second, second.accepted(first.placement().id()));
        assertNotEquals(first.placement().id(), next.placement().id());
        assertEquals(first.placement().x(), next.placement().x()); assertEquals(64, next.placement().y());
        assertEquals(2, next.placement().quarterTurns()); assertTrue(next.placement().mirrored()); assertEquals(4, next.heightOffset());
        assertTrue(next.following()); assertFalse(next.cursorValid());
        assertSame(next, next.accepted(first.placement().id()), "Duplicate replies must not change the next placement ID");
    }
}
