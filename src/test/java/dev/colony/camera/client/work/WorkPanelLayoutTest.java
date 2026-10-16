package dev.colony.camera.client.work;

import dev.colony.camera.client.screen.StrategyHudLayout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkPanelLayoutTest {
    @Test void compactListsDisplayAtLeastThreeRowsAndDetailCannotSelectHiddenRows() {
        var list=new WorkPanelLayout(320,180,250,false);var detail=new WorkPanelLayout(320,180,250,true);
        assertTrue(list.rows()>=3);assertTrue(list.showList());assertFalse(list.showDetail());
        assertTrue(detail.showDetail());assertFalse(detail.showList());assertEquals(0,detail.rows());
        assertEquals(-1,detail.rowAt(list.row(0).x()+1,list.row(0).y()+1));
        assertTrue(detail.detail().width()>280);assertTrue(detail.back().right()<detail.close().x());
    }
    @Test void normalScalesKeepInteractiveRegionsInsideAndSeparate() {
        for (int[] viewport : new int[][] {{320, 180}, {480, 270}, {640, 360}, {1920, 1080}}) {
            var layout = new WorkPanelLayout(viewport[0], viewport[1]); assertTrue(layout.usable()); assertTrue(layout.rows() > 0);
            List<StrategyHudLayout.Rect> controls = new ArrayList<>(); controls.add(layout.close());
            for (int i = 0; i < 5; i++) controls.add(layout.tab(i));
            for (int i = 0; i < 2; i++) controls.add(layout.creation(i));
            for (int i = 0; i < 5; i++) controls.add(layout.action(i, 5));
            for (int i = 0; i < layout.rows(); i++) controls.add(layout.row(i));
            for (var rect : controls) assertInside(rect, viewport[0], viewport[1]);
            for (int a = 0; a < controls.size(); a++) for (int b = a + 1; b < controls.size(); b++) {
                var first = controls.get(a); var second = controls.get(b);
                assertFalse(first.x() < second.right() && first.right() > second.x() && first.y() < second.bottom() && first.bottom() > second.y(), first + " overlaps " + second);
            }
        }
    }
    @Test void tinyResizesGateActionsAndRetainSafeBounds() {
        for (int width = 1; width <= 320; width += 3) for (int height : new int[] {1, 7, 20, 60, 120, 180}) {
            var layout = new WorkPanelLayout(width, height);
            assertInside(layout.bounds(), width, height); assertInside(layout.close(), width, height);
            assertInside(layout.list(), width, height); assertInside(layout.detail(), width, height);
            if (!layout.usable()) { assertEquals(0, layout.rows()); assertEquals(0, layout.action(0, 5).width()); }
        }
    }
    @Test void listRowsUseExclusiveHitEdgesAndRespectBottomActions() {
        var layout = new WorkPanelLayout(320, 180); var first = layout.row(0);
        assertTrue(first.contains(first.x(), first.y())); assertFalse(first.contains(first.right(), first.y()));
        assertFalse(first.contains(first.x(), first.bottom())); assertFalse(first.contains(Double.NaN, first.y()));
        assertEquals(0, layout.rowAt(first.x(), first.y()));
        assertEquals(-1, layout.rowAt(first.right(), first.y()));
        assertEquals(-1, layout.rowAt(first.x(), first.bottom()));
        assertEquals(-1, layout.rowAt(first.x(), layout.list().bottom() - 1));
        assertEquals(-1, layout.rowAt(Double.NaN, first.y()));
        assertTrue(layout.row(layout.rows() - 1).bottom() < layout.action(0, 5).y());
        assertThrows(IllegalArgumentException.class, () -> layout.row(layout.rows()));
    }
    @Test void invalidDimensionsAndControlIndicesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorkPanelLayout(0, 180));
        var layout = new WorkPanelLayout(320, 180);
        assertThrows(IllegalArgumentException.class, () -> layout.tab(5));
        assertThrows(IllegalArgumentException.class, () -> layout.action(0, 0));
    }
    private static void assertInside(StrategyHudLayout.Rect rect, int width, int height) {
        assertTrue(rect.x() >= 0 && rect.y() >= 0 && rect.width() >= 0 && rect.height() >= 0, rect.toString());
        assertTrue(rect.right() <= width && rect.bottom() <= height, rect.toString());
    }
}
