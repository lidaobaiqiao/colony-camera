package dev.colony.camera.client.screen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionPanelLayoutTest {
    @Test
    void commonGuiScalesKeepDraftButtonsInsideTheirPanelWithoutOverlap() {
        for (int[] size : new int[][] {{320, 180}, {400, 225}, {480, 270}, {640, 360}, {854, 480}}) {
            var hud = new StrategyHudLayout(size[0], size[1]);
            var layout = new ProjectionPanelLayout(size[0], size[1], hud.hint().y());
            assertTrue(layout.draftControls().size() >= 6);
            for (var control : layout.draftControls()) {
                var bounds = control.bounds(); var panel = layout.draft();
                assertTrue(bounds.x() >= panel.x() && bounds.right() <= panel.right());
                assertTrue(bounds.y() >= panel.y() + 13 && bounds.bottom() <= panel.bottom());
                assertTrue(panel.contains(bounds.x() + bounds.width() / 2.0, bounds.y() + bounds.height() / 2.0));
            }
            var controls = layout.draftControls();
            for (int a = 0; a < controls.size(); a++) for (int b = a + 1; b < controls.size(); b++) {
                var left = controls.get(a).bounds(); var right = controls.get(b).bounds();
                assertFalse(left.x() < right.right() && left.right() > right.x() && left.y() < right.bottom() && left.bottom() > right.y());
            }
        }
    }

    @Test
    void tinyTransientViewportsDoNotProduceOutOfBoundsPanelsOrControls() {
        for (int width = 1; width < 101; width++) for (int height : new int[] {1, 7, 16, 32, 90}) {
            var layout = new ProjectionPanelLayout(width, height, new StrategyHudLayout(width, height).hint().y());
            for (var panel : new StrategyHudLayout.Rect[] {layout.modal(), layout.draft()}) {
                assertTrue(panel.x() >= 0 && panel.y() >= 0 && panel.width() >= 0 && panel.height() >= 0);
                assertTrue(panel.right() <= width && panel.bottom() <= height);
            }
            assertTrue(layout.draftControls().isEmpty());
        }
    }

    @Test
    void invalidViewportIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectionPanelLayout(0, 180, 100));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionPanelLayout(320, -1, 100));
    }
}
