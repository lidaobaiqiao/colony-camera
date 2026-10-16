package dev.colony.camera.client.work;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TechnologyPanelLayoutTest {
    @Test void treeAndDetailsRemainSeparateInWideAndNarrowLayouts() {
        for (int[] size : new int[][]{{320,180},{480,270},{640,360},{1280,720}}) {
            var l = new TechnologyPanelLayout(size[0], size[1]); assertTrue(l.usable);
            var a = l.tree; var b = l.detail;
            assertFalse(a.x() < b.right() && a.right() > b.x() && a.y() < b.bottom() && a.bottom() > b.y());
            for (int i = 0; i < 8; i++) {
                assertTrue(l.node(i).right() <= a.right() && l.node(i).bottom() <= a.bottom());
                for (int j = i + 1; j < 8; j++) {
                    var first = l.node(i); var second = l.node(j);
                    assertFalse(first.x() < second.right() && first.right() > second.x() && first.y() < second.bottom() && first.bottom() > second.y());
                }
            }
            if (l.showDetail) { assertTrue(l.detail.bottom() < l.station.y()); assertTrue(l.station.bottom() < l.research.y()); }
        }
    }
    @Test void compactTreeAndDetailHaveDisjointInputStatesAndReadableContent() {
        for (int[] size : new int[][]{{320,180},{400,225},{480,270}}) {
            var list = new TechnologyPanelLayout(size[0],size[1],false);
            var detail = new TechnologyPanelLayout(size[0],size[1],true);
            assertTrue(list.showTree); assertFalse(list.showDetail); assertEquals(0,list.research.width());
            assertFalse(detail.showTree); assertTrue(detail.showDetail); assertEquals(0,detail.node(0).width());
            assertTrue(detail.detail.height() >= 72); assertTrue(detail.detail.width() >= 244);
            assertTrue(detail.back.right() < detail.close.x());
            assertTrue(detail.detail.bottom() < detail.station.y()); assertTrue(detail.station.bottom() < detail.research.y());
        }
    }
    @Test void tinyViewportGatesResearchAndKeepsAllRectsInside() {
        for (int w = 1; w < 320; w += 5) for (int h : new int[]{1,7,60,120,180}) {
            var l = new TechnologyPanelLayout(w,h);
            for (var r : List.of(l.bounds,l.tree,l.detail,l.close,l.station,l.research,l.refresh))
                assertTrue(r.x() >= 0 && r.y() >= 0 && r.width() >= 0 && r.height() >= 0 && r.right() <= w && r.bottom() <= h, r.toString());
            if (!l.usable) assertEquals(0,l.research.width());
        }
    }
}
