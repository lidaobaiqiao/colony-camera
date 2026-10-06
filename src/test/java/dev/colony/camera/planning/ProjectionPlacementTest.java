package dev.colony.camera.planning;

import dev.colony.camera.preset.BlockPreset;
import org.junit.jupiter.api.Test;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionPlacementTest {
    private static final BlockPreset.State STONE = new BlockPreset.State("minecraft:stone", Objects.requireNonNull(Map.of()));

    @Test
    void allRotationsAndMirrorsKeepNonSquareDesignInsideItsBoundsWithoutCollisions() {
        var cells = new ArrayList<BlockPreset.Cell>();
        for (int x = 0; x < 5; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 2; z++) cells.add(new BlockPreset.Cell(x, y, z, Objects.requireNonNull(STONE)));
        BlockPreset preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "非正方形建筑", 5, 3, 2, cells);
        for (int turn = 0; turn < 4; turn++) for (boolean mirror : new boolean[] {false, true}) {
            var placement = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, -120, 64, 30, turn, mirror, true);
            var positions = new HashSet<ProjectionPlacement.Position>();
            for (var cell : cells) {
                var pos = placement.transform(Objects.requireNonNull(cell));
                assertTrue(pos.x() >= -120 && pos.x() < -120 + placement.width());
                assertTrue(pos.y() >= 64 && pos.y() < 67);
                assertTrue(pos.z() >= 30 && pos.z() < 30 + placement.depth());
                assertTrue(positions.add(pos));
            }
            assertEquals(cells.size(), positions.size());
        }
    }

    @Test
    void fourRotationsAndTwoMirrorsRestoreTheExactPlacement() {
        var placement = placement();
        assertEquals(placement, placement.rotated().rotated().rotated().rotated());
        assertEquals(placement, placement.mirror().mirror());
        assertEquals(placement.preset(), placement.moved(1, 2, -3).preset());
    }

    @Test
    void clockwiseRotationAndMirroringUseTheSameOrientationAsBlockStates() {
        var preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "角点", 3, 1, 2,
                Objects.requireNonNull(List.of(new BlockPreset.Cell(0, 0, 0, Objects.requireNonNull(STONE)),
                        new BlockPreset.Cell(2, 0, 1, Objects.requireNonNull(STONE)))));
        var placement = new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, 10, 20, 30, 1, false, true);
        assertEquals(new ProjectionPlacement.Position(11, 20, 30), placement.transform(Objects.requireNonNull(preset.blocks().get(0))));
        assertEquals(new ProjectionPlacement.Position(10, 20, 32), placement.transform(Objects.requireNonNull(preset.blocks().get(1))));
        assertEquals(new ProjectionPlacement.Position(11, 20, 32), placement.mirror().transform(Objects.requireNonNull(preset.blocks().get(0))));
    }

    @Test
    void movementVisibilityAndRotationDoNotMutateOriginalDesign() {
        var initial = placement();
        var result = initial.moved(5, -2, -4).rotated().mirror().visibility(false);
        assertEquals(15, result.x()); assertEquals(62, result.y()); assertEquals(16, result.z());
        assertFalse(result.visible()); assertTrue(initial.visible());
        assertEquals(10, initial.x()); assertSame(initial.preset(), result.preset()); assertEquals(initial.id(), result.id());
    }

    @Test
    void extremeCoordinatesAndArithmeticOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> placement().at(Integer.MIN_VALUE, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> placement().at(0, 4097, 0));
        assertThrows(ArithmeticException.class, () -> placement().moved(Integer.MAX_VALUE, 0, 0));
    }

    private static ProjectionPlacement placement() {
        var preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "住宅", 1, 1, 1,
                Objects.requireNonNull(List.of(new BlockPreset.Cell(0, 0, 0, Objects.requireNonNull(STONE)))));
        return new ProjectionPlacement(Objects.requireNonNull(UUID.randomUUID()), preset, 10, 64, 20, 0, false, true);
    }
}
