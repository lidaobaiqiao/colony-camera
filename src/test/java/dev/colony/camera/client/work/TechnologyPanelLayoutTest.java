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
            for (int i = 0; i < 4; i++) assertTrue(l.node(i).right() <= a.right() && l.node(i).bottom() <= a.bottom());
            assertTrue(l.detail.bottom() < l.station.y()); assertTrue(l.station.bottom() < l.research.y());
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
