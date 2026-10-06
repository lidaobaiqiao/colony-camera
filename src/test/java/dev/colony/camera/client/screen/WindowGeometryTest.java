package dev.colony.camera.client.screen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowGeometryTest {
    @Test void draggingAndResizingRemainAboveTheDockAfterViewportChanges() {
        for (int w : new int[]{1, 100, 320, 960}) for (int h : new int[]{1, 90, 140, 504}) {
            var r = WindowGeometry.clamp(new StrategyHudLayout.Rect(-30, 900, 600, 500), w, h);
            assertTrue(r.x() >= 0 && r.y() >= 0 && r.right() <= w && r.bottom() <= h);
            assertTrue(r.width() >= Math.min(120, w) && r.height() >= Math.min(110, h));
        }
    }
    @Test void normalizedPreferencesRetainPositionAndSizeAcrossGuiScales() {
        var r = new StrategyHudLayout.Rect(160, 80, 240, 160);
        var encoded = WindowGeometry.encode(r, 640, 320);
        assertEquals(new StrategyHudLayout.Rect(320, 160, 480, 320), WindowGeometry.decode(encoded, 1280, 640));
        assertEquals(r, WindowGeometry.decode(encoded, 640, 320));
    }
    @Test void brokenPreferencesAreRejectedAndValidOversizedPreferencesClamp() {
        assertThrows(IllegalArgumentException.class, () -> WindowGeometry.decode("10001,0,10000,10000", 320, 140));
        assertThrows(IllegalArgumentException.class, () -> WindowGeometry.decode("a,b,c,d", 320, 140));
        var r = WindowGeometry.decode("9000,9000,10000,10000", 320, 140);
        assertEquals(new StrategyHudLayout.Rect(0, 0, 320, 140), r);
    }
    @Test void aWiderWindowUsesMeasuredCategoryTabsWithoutMovingTheGrid() {
        var wide = new BuildPanelLayout(960, 540, 504, new StrategyHudLayout.Rect(80, 30, 450, 300), index -> 25);
        assertTrue(wide.horizontalCategories);
        var narrow = new BuildPanelLayout(960, 540, 504, new StrategyHudLayout.Rect(80, 30, 180, 300), index -> 25);
        assertFalse(narrow.horizontalCategories);
        assertEquals(wide.grid.y(), narrow.grid.y());
        for (int i = 0; i < 7; i++) assertTrue(wide.category(i, 7).right() <= wide.available.x());
    }
}
