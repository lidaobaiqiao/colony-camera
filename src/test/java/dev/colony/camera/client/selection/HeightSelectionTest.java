package dev.colony.camera.client.selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeightSelectionTest {
    @Test
    void movingUpExtendsOutwardAndMovingDownExtendsInwardIncludingTheBase() {
        HeightSelection height = new HeightSelection();
        height.begin(200);
        assertEquals(1, height.signedLayers());
        assertTrue(height.move(140, 20));
        assertEquals(4, height.signedLayers());
        assertTrue(height.move(200, 20));
        assertEquals(1, height.signedLayers());
        assertTrue(height.move(240, 20));
        assertEquals(-3, height.signedLayers());
    }

    @Test
    void smallMouseEventsAccumulateAndReturningToTheStartRestoresOneLayer() {
        HeightSelection height = new HeightSelection();
        height.begin(100);
        assertFalse(height.move(96, 20));
        assertFalse(height.move(92, 20));
        assertTrue(height.move(88, 20));
        assertEquals(2, height.signedLayers());
        height.move(100, 20);
        assertEquals(1, height.signedLayers());
    }

    @Test
    void halfBlockSnappingIsSymmetricInBothDirections() {
        HeightSelection height = new HeightSelection();
        height.begin(100);
        height.move(90, 20);
        assertEquals(2, height.signedLayers());
        height.begin(100);
        height.move(110, 20);
        assertEquals(-2, height.signedLayers());
    }

    @Test
    void changingZoomScaleWithoutPointerMotionKeepsTheChosenHeight() {
        HeightSelection height = new HeightSelection();
        height.begin(100);
        height.move(60, 20);
        assertEquals(3, height.signedLayers());
        assertFalse(height.move(60, 80));
        assertFalse(height.move(60, 5));
        assertEquals(3, height.signedLayers());
        height.move(20, 40);
        assertEquals(4, height.signedLayers());
    }

    @Test
    void cameraDraggingRebasesThePointerWithoutChangingDepthOrFractionalMotion() {
        HeightSelection height = new HeightSelection();
        height.begin(100);
        height.move(92, 20);
        height.rebase(350);
        height.rebase(400);
        assertEquals(1, height.signedLayers());
        assertTrue(height.move(396, 20));
        assertEquals(2, height.signedLayers());
    }

    @Test
    void nearlyEndOnAxisProjectionHasBoundedSensitivity() {
        HeightSelection height = new HeightSelection();
        height.begin(100);
        height.move(96, 0.00001);
        assertEquals(2, height.signedLayers());
    }

    @Test
    void invalidEventsDoNotProduceDepthOrCarryMotionAcrossAnInvalidScale() {
        HeightSelection height = new HeightSelection();
        height.begin(100);
        assertFalse(height.move(Double.NaN, 20));
        assertFalse(height.move(Double.POSITIVE_INFINITY, 20));
        assertFalse(height.move(80, Double.NaN));
        assertFalse(height.move(60, Double.POSITIVE_INFINITY));
        assertFalse(height.move(40, 0));
        assertFalse(height.move(20, -10));
        assertEquals(1, height.signedLayers());
        height.rebase(Double.NaN);
        height.move(0, 20);
        assertEquals(2, height.signedLayers());
        assertThrows(IllegalArgumentException.class, () -> height.begin(Double.NaN));
        assertEquals(2, height.signedLayers());
    }

    @Test
    void extremeMotionClampsWithoutOverflowAndCanReturnFromTheLimit() {
        HeightSelection height = new HeightSelection();
        height.begin(Double.MAX_VALUE);
        height.move(-Double.MAX_VALUE, 20);
        assertEquals(SelectionState.MAX_BLOCKS, height.signedLayers());
        height.rebase(100);
        height.move(120, 20);
        assertEquals(SelectionState.MAX_BLOCKS - 1, height.signedLayers());
        height.move(Double.MAX_VALUE, 20);
        assertEquals(-SelectionState.MAX_BLOCKS, height.signedLayers());
    }

    @Test
    void resetAndNewSelectionDiscardOldHeightAndPointerState() {
        HeightSelection height = new HeightSelection();
        assertFalse(height.move(100, 20));
        height.begin(100);
        height.move(40, 20);
        height.reset();
        assertFalse(height.move(-1000, 20));
        assertEquals(1, height.signedLayers());
        height.begin(900);
        assertFalse(height.move(900, 20));
        assertEquals(1, height.signedLayers());
    }

    @Test
    void signedDepthExtrudesEveryFaceWithoutChangingTheFootprint() {
        HeightSelection height = new HeightSelection();
        SelectionState.Cell anchor = new SelectionState.Cell(-4, 8, -2);
        for (FaceSelection.Face face : FaceSelection.Face.values()) {
            FaceSelection selection = new FaceSelection(anchor, face, 0);
            selection.followSurface(new SelectionState.Cell(-2, 10, 1));
            long area = selection.bounds(1).volume();
            height.begin(100);
            height.move(60, 20);
            assertEquals(area * 3, selection.bounds(height.signedLayers()).volume());
            height.move(140, 20);
            assertEquals(area * 3, selection.bounds(height.signedLayers()).volume());
            assertEquals(selection.bounds(1).first(), selection.bounds(height.signedLayers()).first());
        }
    }
}
