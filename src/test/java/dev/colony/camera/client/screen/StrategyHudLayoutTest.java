package dev.colony.camera.client.screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class StrategyHudLayoutTest {
    @Test
    void commonGuiScalesKeepAllControlsInsideTheViewport() {
        for (int[] size : new int[][] {{320, 180}, {400, 225}, {480, 270}, {640, 360}, {854, 480}, {1920, 1080}}) {
            assertInside(new StrategyHudLayout(size[0], size[1]), size[0], size[1]);
        }
    }

    @Test
    void transientTinyResizeDimensionsDoNotCreateNegativeRectangles() {
        for (int width = 1; width <= 100; width++) {
            for (int height : new int[] {1, 7, 16, 32, 90}) {
                assertInside(new StrategyHudLayout(width, height), width, height);
            }
        }
    }

    @Test
    void everyButtonCenterResolvesToItsOwnActionAtDifferentScales() {
        for (int[] size : new int[][] {{320, 180}, {480, 270}, {640, 360}}) {
            StrategyHudLayout layout = new StrategyHudLayout(size[0], size[1]);
            for (StrategyHudLayout.Control control : layout.controls()) {
                StrategyHudLayout.Rect rect = control.bounds();
                assertEquals(control.action(), layout.actionAt(rect.x() + rect.width() / 2.0, rect.y() + rect.height() / 2.0));
                assertTrue(layout.blocksWorld(rect.x() + 1, rect.y() + 1, false));
            }
        }
    }

    @Test
    void adjoiningToolsHaveUnambiguousEdges() {
        StrategyHudLayout layout = new StrategyHudLayout(320, 180);
        for (int i = 0; i < 3; i++) {
            StrategyHudLayout.Control current = layout.controls().get(i);
            StrategyHudLayout.Control next = layout.controls().get(i + 1);
            assertEquals(current.bounds().right(), next.bounds().x());
            assertEquals(current.action(), layout.actionAt(current.bounds().right() - 0.01, current.bounds().y() + 5));
            assertEquals(next.action(), layout.actionAt(current.bounds().right(), current.bounds().y() + 5));
        }
    }

    @Test
    void panelPaddingBlocksWorldClicksWithoutActivatingAButton() {
        StrategyHudLayout layout = new StrategyHudLayout(640, 360);
        assertNull(layout.actionAt(layout.dock().x() + 1, layout.dock().y() + 1));
        assertTrue(layout.blocksWorld(layout.dock().x() + 1, layout.dock().y() + 1, false));
        for (StrategyHudLayout.Rect panel : new StrategyHudLayout.Rect[] {layout.title(), layout.selection(), layout.hint()}) {
            assertTrue(layout.blocksWorld(panel.x() + 2, panel.y() + 2, false));
            assertNull(layout.actionAt(panel.x() + 2, panel.y() + 2));
        }
    }

    @Test
    void sceneCenterRemainsSelectableEvenAtMinimumMinecraftGuiSize() {
        for (int[] size : new int[][] {{320, 180}, {480, 270}, {640, 360}}) {
            assertFalse(new StrategyHudLayout(size[0], size[1]).blocksWorld(size[0] / 2.0, size[1] / 2.0, false));
        }
    }

    @Test
    void helpOwnsTheWholeViewportUntilClosed() {
        StrategyHudLayout layout = new StrategyHudLayout(640, 360);
        assertTrue(layout.blocksWorld(320, 180, true));
        assertTrue(layout.blocksWorld(2, 2, true));
        assertFalse(layout.blocksWorld(320, 180, false));
        assertFalse(layout.blocksWorld(2, 2, false));
    }

    @Test
    void nonFinitePointerCoordinatesNeverActivateControls() {
        StrategyHudLayout layout = new StrategyHudLayout(640, 360);
        assertNull(layout.actionAt(Double.NaN, 12));
        assertNull(layout.actionAt(12, Double.POSITIVE_INFINITY));
        assertFalse(layout.blocksWorld(Double.NaN, 12, false));
    }

    @Test
    void invalidViewportSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StrategyHudLayout(0, 180));
        assertThrows(IllegalArgumentException.class, () -> new StrategyHudLayout(320, -1));
    }

    private static void assertInside(StrategyHudLayout layout, int width, int height) {
        var rectangles = new ArrayList<>(layout.controls().stream().map(StrategyHudLayout.Control::bounds).toList());
        rectangles.addAll(java.util.List.of(layout.title(), layout.selection(), layout.dock(), layout.hint(), layout.help()));
        for (StrategyHudLayout.Rect rect : rectangles) {
            assertTrue(rect.x() >= 0 && rect.y() >= 0 && rect.width() >= 0 && rect.height() >= 0, rect.toString());
            assertTrue(rect.right() <= width && rect.bottom() <= height, rect.toString());
        }
    }
}
