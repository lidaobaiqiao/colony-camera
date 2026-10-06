package dev.colony.camera.preset;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BlockPresetTest {
    @Test
    void designDefensivelyCopiesPositionsAndProperties() {
        var properties = new HashMap<String, String>(); properties.put("facing", "north");
        var state = new BlockPreset.State("minecraft:oak_stairs", properties);
        var cells = new ArrayList<BlockPreset.Cell>(); cells.add(new BlockPreset.Cell(0, 0, 0, state));
        var preset = new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), "  楼梯  ", 1, 1, 1, cells);
        properties.put("facing", "south"); cells.clear();
        assertEquals("楼梯", preset.name()); assertEquals(1, preset.blocks().size());
        assertEquals("north", preset.blocks().get(0).state().properties().get("facing"));
        assertThrows(UnsupportedOperationException.class, () -> preset.blocks().clear());
    }

    @Test
    void emptyNamesControlCharactersAndOversizedNamesAreRejected() {
        for (String name : List.of("", "  ", "a\nb", "a".repeat(49))) assertThrows(IllegalArgumentException.class, () -> create(name, 1, cells()));
        assertDoesNotThrow(() -> create("道路 / 转角", 1, cells()));
    }

    @Test
    void duplicateOutOfBoundsAndEmptyDesignsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> create("test", 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> create("test", 1, List.of(cells().get(0), cells().get(0))));
        assertThrows(IllegalArgumentException.class, () -> create("test", 1, List.of(new BlockPreset.Cell(1, 0, 0, cells().get(0).state()))));
        assertThrows(IllegalArgumentException.class, () -> create("test", 257, cells()));
    }

    @Test
    void airAndMalformedIdentifiersAreRejectedInsteadOfBecomingDestructiveEmptyTargets() {
        for (String id : List.of("minecraft:air", "minecraft:cave_air", "../stone", "minecraft:Stone", "stone")) {
            assertThrows(IllegalArgumentException.class, () -> new BlockPreset.State(Objects.requireNonNull(id), Objects.requireNonNull(Map.of())));
        }
    }

    private static List<BlockPreset.Cell> cells() { return List.of(new BlockPreset.Cell(0, 0, 0, new BlockPreset.State("minecraft:stone", Objects.requireNonNull(Map.of())))); }
    private static BlockPreset create(String name, int size, List<BlockPreset.Cell> cells) { return new BlockPreset(Objects.requireNonNull(UUID.randomUUID()), Objects.requireNonNull(name), size, 1, 1, Objects.requireNonNull(cells)); }
}
