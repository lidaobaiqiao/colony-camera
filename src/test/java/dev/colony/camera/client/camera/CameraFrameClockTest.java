package dev.colony.camera.client.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CameraFrameClockTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void firstFrameHasNoElapsedTimeEvenWhenTheClockStartsAtZero() {
        CameraFrameClock clock = new CameraFrameClock();

        assertEquals(0, clock.step(0), EPSILON);
        assertEquals(0.016, clock.step(16_000_000), EPSILON);
    }

    @Test
    void normalFramesUseTheActualElapsedSeconds() {
        CameraFrameClock clock = new CameraFrameClock();
        clock.step(1_000_000_000);

        assertEquals(0.016, clock.step(1_016_000_000), EPSILON);
        assertEquals(0.008, clock.step(1_024_000_000), EPSILON);
    }

    @Test
    void longFramesAreClampedWithoutCarryingTheDelayIntoTheNextFrame() {
        CameraFrameClock clock = new CameraFrameClock();
        clock.step(1_000_000_000);

        assertEquals(0.05, clock.step(3_000_000_000L), EPSILON);
        assertEquals(0.01, clock.step(3_010_000_000L), EPSILON);
    }

    @Test
    void nonIncreasingTimeDoesNotAdvanceAndResetDropsTheOldTimestamp() {
        CameraFrameClock clock = new CameraFrameClock();
        clock.step(1_000_000_000);

        assertEquals(0, clock.step(1_000_000_000), EPSILON);
        assertEquals(0, clock.step(900_000_000), EPSILON);
        clock.reset();
        assertEquals(0, clock.step(5_000_000_000L), EPSILON);
        assertEquals(0.01, clock.step(5_010_000_000L), EPSILON);
    }
}
