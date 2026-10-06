package dev.colony.camera.client.input;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PointerGestureTest {
    @Test void usesPhysicalPixelsAtDifferentGuiScales() {
        var gesture = new PointerGesture(); gesture.begin(10, 20);
        assertFalse(gesture.move(11, 20, 3)); assertTrue(gesture.release(11, 20, 3));
        gesture.begin(10, 20); assertTrue(gesture.move(12, 20, 2)); assertFalse(gesture.release(12, 20, 2));
    }
    @Test void returningToTheOriginNeverTurnsADragIntoAClick() {
        var gesture = new PointerGesture(); gesture.begin(5, 5); gesture.move(15, 5, 1);
        assertFalse(gesture.release(5, 5, 1));
    }
    @Test void releaseAlsoChecksDistanceWithoutAnIntermediateDragCallback() {
        var gesture = new PointerGesture(); gesture.begin(5, 5); assertFalse(gesture.release(5, 10, 1));
    }
    @Test void cancellationAndDuplicateReleasesCannotCycleSelection() {
        var gesture = new PointerGesture(); gesture.begin(5, 5); gesture.cancel(); assertFalse(gesture.release(5, 5, 1));
        gesture.begin(5, 5); assertTrue(gesture.release(5, 5, 1)); assertFalse(gesture.release(5, 5, 1));
    }
}
