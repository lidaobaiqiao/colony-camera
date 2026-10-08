package dev.colony.camera.client.work;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftingPanelLayoutTest {
    @Test void recipeCellsAndFooterStaySeparateOnShortAndNormalScreens() {
        for (int[] size : new int[][]{{320, 180}, {400, 225}, {640, 360}, {1920, 1080}}) {
            var layout = new CraftingPanelLayout(size[0], size[1]);
            assertTrue(layout.usable);
            assertTrue(layout.pageSize() >= 5 && layout.pageSize() <= 20);
            assertTrue(layout.bounds.x() >= 0 && layout.bounds.right() <= size[0]);
            assertTrue(layout.bounds.y() >= 0 && layout.bounds.bottom() <= size[1]);
            for (int index = 0; index < layout.pageSize(); index++) {
                var cell = layout.cell(index);
                assertTrue(cell.bottom() < layout.previous.y());
                assertEquals(index, layout.cellAt(cell.x() + 1, cell.y() + 1));
                assertEquals(-1, layout.cellAt(cell.right() + 1, cell.y() + 1));
            }
        }
    }
    @Test void tinyScreenStillOffersACloseTargetWithoutInvalidCells() {
        for (int[] size : new int[][]{{1, 1}, {120, 80}, {280, 160}}) {
            var layout = new CraftingPanelLayout(size[0], size[1]);
            assertFalse(layout.usable); assertEquals(0, layout.pageSize());
            assertTrue(layout.close.width() > 0 && layout.close.height() > 0);
            assertEquals(-1, layout.cellAt(layout.close.x(), layout.close.y()));
        }
    }
}
