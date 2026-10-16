package dev.colony.camera.client.screen;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InspectorLayoutTest {
    @Test void inspectorsLeaveTheCenterAndBottomDocksAvailable() {
        for (int[] size : new int[][]{{320,180},{400,225},{480,270},{640,360},{960,540}}) {
            var l = new InspectorLayout(size[0],size[1],false,100);
            assertTrue(l.usable); assertTrue(l.rows() >= 3);
            assertFalse(l.bounds.contains(size[0] / 2.0,size[1] / 2.0));
            assertTrue(l.bounds.bottom() <= new StrategyHudLayout(size[0],size[1]).rightDock().y() - 4);
            assertTrue(l.bounds.width() * l.bounds.height() < size[0] * size[1] * 0.35);
        }
    }
    @Test void onlyRenderedTabsAndRowsOwnTheirPixels() {
        for (int[] size : new int[][]{{320,180},{640,360}}) {
            var l = new InspectorLayout(size[0],size[1],false,60);
            List<StrategyHudLayout.Rect> controls = new ArrayList<>(); controls.add(l.close);
            if (l.compact) { controls.add(l.previous); controls.add(l.tab(0)); controls.add(l.next); }
            else for (int i=0;i<6;i++) controls.add(l.tab(i));
            for (int i=0;i<l.rows();i++) { controls.add(l.row(i)); assertEquals(i,l.rowAt(l.row(i).x()+1,l.row(i).y()+1)); }
            for (int i=0;i<controls.size();i++) for (int j=i+1;j<controls.size();j++) {
                var a=controls.get(i);var b=controls.get(j);
                assertFalse(a.x()<b.right()&&a.right()>b.x()&&a.y()<b.bottom()&&a.bottom()>b.y());
            }
            assertEquals(-1,l.rowAt(Double.NaN,l.content.y()));
            assertEquals(-1,l.rowAt(l.content.right(),l.content.y()));
        }
    }
    @Test void tinyResizesKeepCloseReachableAndPrintingHasNoInspectorTabs() {
        for (int[] size : new int[][]{{1,1},{16,7},{100,80},{320,180},{640,360}}) for (boolean modal : new boolean[]{false,true}) {
            var l=new InspectorLayout(size[0],size[1],modal,50);
            for (var r:List.of(l.bounds,l.close,l.tabs,l.content,l.notice)) assertTrue(r.x()>=0&&r.y()>=0&&r.right()<=size[0]&&r.bottom()<=size[1],r.toString());
            assertTrue(l.close.width()>0&&l.close.height()>0);
            if(modal) assertEquals(0,l.tabs.height());
        }
    }
    @Test void shortContentDoesNotReserveAFullHeightPanel() {
        var shortPanel=new InspectorLayout(640,360,false,3);
        var longPanel=new InspectorLayout(640,360,false,100);
        assertTrue(shortPanel.bounds.height()<longPanel.bounds.height());
        assertThrows(IllegalArgumentException.class,()->new InspectorLayout(0,180,false,3));
    }
}
