package dev.colony.camera.client.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraStateTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void newSessionReturnsToPlayerAndDefaultZoom() {
        CameraState state = new CameraState();
        state.reset(10, 64, -20);
        state.zoom(5);
        state.pan(1, 1, false, 10, -20, 100);
        state.reset(-3, 80, 7);

        assertEquals(new CameraState.Position(-3, 81, 7), state.focus());
        assertEquals(32, state.distance(), EPSILON);
    }

    @Test
    void diagonalPanningHasTheSameSpeedAndHeightAsStraightPanning() {
        CameraState straight = new CameraState();
        CameraState diagonal = new CameraState();
        straight.reset(0, 64, 0);
        diagonal.reset(0, 64, 0);
        straight.pan(1, 0, false, 0, 0, 100);
        diagonal.pan(1, 1, false, 0, 0, 100);

        assertEquals(straight.focus().x(), Math.hypot(diagonal.focus().x(), diagonal.focus().z()), EPSILON);
        assertEquals(65, diagonal.focus().y(), EPSILON);
    }

    @Test
    void fastPanningDoublesSpeedAndOpposingKeysDoNotMove() {
        CameraState state = new CameraState();
        state.reset(0, 64, 0);
        state.pan(1, 0, true, 0, 0, 100);
        assertEquals(32.0 / 30, state.focus().x(), EPSILON);
        CameraState.Position before = state.focus();
        state.pan(0, 0, true, 0, 0, 100);
        assertEquals(before, state.focus());
    }

    @Test
    void zoomStaysWithinBoundsAndSupportsFractionalWheelInput() {
        CameraState state = new CameraState();
        state.zoom(0.5);
        assertTrue(state.distance() < 32 && state.distance() > 28.8);
        state.zoom(10000);
        assertEquals(8, state.distance(), EPSILON);
        state.zoom(-10000);
        assertEquals(96, state.distance(), EPSILON);
    }

    @Test
    void panningStaysInsideThePlayersLoadedRadius() {
        CameraState state = new CameraState();
        state.reset(10, 64, -20);
        for (int i = 0; i < 100; i++) {
            state.pan(1, -1, true, 10, -20, 16);
        }
        assertEquals(16, Math.hypot(state.focus().x() - 10, state.focus().z() + 20), EPSILON);
        // The player may be pushed while the strategy screen is open.
        state.pan(1, 0, false, -100, 50, 16);
        assertEquals(16, Math.hypot(state.focus().x() + 100, state.focus().z() - 50), EPSILON);
    }

    @Test
    void cameraLooksAtTheFocusFromAboveAtSixtyDegrees() {
        CameraState state = new CameraState();
        state.reset(10, 64, -20);
        CameraState.Position position = state.cameraPosition();
        assertEquals(10, position.x(), EPSILON);
        assertEquals(65 + 32 * Math.sqrt(3) / 2, position.y(), EPSILON);
        assertEquals(-4, position.z(), EPSILON);
    }

    @Test
    void lockedAngleStillAllowsPanningZoomingAndCanvasDragging() {
        CameraState state = new CameraState();
        state.reset(0, 64, 0);
        state.rotate(30, -20);

        assertTrue(state.angleLocked());
        assertEquals(180, state.yaw(), EPSILON);
        assertEquals(60, state.pitch(), EPSILON);
        state.pan(1, 0, false, 0, 0, 100);
        assertTrue(state.focus().x() > 0);
        state.zoom(1);
        assertEquals(28.8, state.distance(), EPSILON);
        CameraState.Position beforeDrag = state.focus();
        state.drag(4, 5, 0.5, 0, 0, 100);
        assertEquals(beforeDrag.x() - 2, state.focus().x(), EPSILON);
        assertEquals(beforeDrag.z() - 2.5 / Math.sin(Math.toRadians(60)),
                state.focus().z(), EPSILON);
        assertEquals(beforeDrag.y(), state.focus().y(), EPSILON);
    }

    @Test
    void unlockingAllowsOrbitingAroundTheFocus() {
        CameraState state = new CameraState();
        state.reset(10, 64, -20);
        CameraState.Position focus = state.focus();
        state.toggleAngleLock();
        state.rotate(30, -20);

        assertFalse(state.angleLocked());
        assertEquals(210, state.yaw(), EPSILON);
        assertEquals(40, state.pitch(), EPSILON);
        assertEquals(focus, state.focus());
        assertEquals(32, state.distance(), EPSILON);
    }

    @Test
    void relockingPreservesTheChosenAngleAndIgnoresFurtherRotation() {
        CameraState state = new CameraState();
        state.toggleAngleLock();
        state.rotate(-45, 10);
        state.toggleAngleLock();
        state.rotate(90, -30);

        assertTrue(state.angleLocked());
        assertEquals(135, state.yaw(), EPSILON);
        assertEquals(70, state.pitch(), EPSILON);
    }

    @Test
    void newSessionRestoresTheDefaultLockedAngle() {
        CameraState state = new CameraState();
        state.toggleAngleLock();
        state.rotate(45, -30);
        state.reset(-3, 80, 7);

        assertTrue(state.angleLocked());
        assertEquals(180, state.yaw(), EPSILON);
        assertEquals(60, state.pitch(), EPSILON);
    }

    @Test
    void extremeRotationWrapsYawAndClampsPitch() {
        CameraState state = new CameraState();
        state.toggleAngleLock();
        state.rotate(1080 + 275, 1000);
        assertEquals(95, state.yaw(), EPSILON);
        assertEquals(85, state.pitch(), EPSILON);
        state.rotate(-1080 - 120, -1000);
        assertEquals(335, state.yaw(), EPSILON);
        assertEquals(15, state.pitch(), EPSILON);
    }

    @Test
    void rotatedCameraPansAlongItsScreenAxes() {
        CameraState state = new CameraState();
        state.reset(0, 64, 0);
        state.toggleAngleLock();
        state.rotate(-90, 0);
        state.pan(1, 0, false, 0, 0, 100);
        assertEquals(0, state.focus().x(), EPSILON);
        assertEquals(-32.0 / 60, state.focus().z(), EPSILON);
        state.pan(0, -1, false, 0, 0, 100);
        assertEquals(-32.0 / 60, state.focus().x(), EPSILON);
        assertEquals(-32.0 / 60, state.focus().z(), EPSILON);
        assertEquals(65, state.focus().y(), EPSILON);
    }

    @Test
    void rotatedCanvasDraggingUsesTheCurrentPitchAndScreenAxes() {
        CameraState state = new CameraState();
        state.reset(0, 64, 0);
        state.toggleAngleLock();
        state.rotate(-90, -30);
        state.drag(10, 6, 0.2, 0, 0, 100);

        assertEquals(-2.4, state.focus().x(), EPSILON);
        assertEquals(2, state.focus().z(), EPSILON);
        assertEquals(65, state.focus().y(), EPSILON);
    }

    @Test
    void draggingStaysInsideThePlayersLoadedRadius() {
        CameraState state = new CameraState();
        state.reset(10, 64, -20);
        state.drag(1000, 1000, 1, 10, -20, 16);
        assertEquals(16, Math.hypot(state.focus().x() - 10, state.focus().z() + 20), EPSILON);
        state.drag(1, 0, 1, -100, 50, 16);
        assertEquals(16, Math.hypot(state.focus().x() + 100, state.focus().z() - 50), EPSILON);
    }

    @Test
    void orbitPositionTracksYawPitchAndDistanceFromTheFocus() {
        CameraState state = new CameraState();
        state.reset(10, 64, -20);
        state.toggleAngleLock();
        state.rotate(-90, -30);
        CameraState.Position position = state.cameraPosition();
        assertEquals(10 + 16 * Math.sqrt(3), position.x(), EPSILON);
        assertEquals(81, position.y(), EPSILON);
        assertEquals(-20, position.z(), EPSILON);
        assertEquals(state.distance(), Math.sqrt(
                Math.pow(position.x() - state.focus().x(), 2)
                        + Math.pow(position.y() - state.focus().y(), 2)
                        + Math.pow(position.z() - state.focus().z(), 2)), EPSILON);

        state.rotate(-90, 55);
        position = state.cameraPosition();
        assertEquals(10, position.x(), EPSILON);
        assertEquals(65 + 32 * Math.sin(Math.toRadians(85)), position.y(), EPSILON);
        assertEquals(-20 - 32 * Math.cos(Math.toRadians(85)), position.z(), EPSILON);
    }

    @Test
    void movementForOneSecondIsTheSameAtSixtyAndOneHundredFortyFourFrames() {
        CameraState sixtyFrames = new CameraState();
        CameraState highRefreshRate = new CameraState();
        sixtyFrames.reset(0, 64, 0);
        highRefreshRate.reset(0, 64, 0);

        for (int i = 0; i < 60; i++) {
            sixtyFrames.move(1, -1, 1, false, 1.0 / 60, 0, 0, 100);
        }
        for (int i = 0; i < 144; i++) {
            highRefreshRate.move(1, -1, 1, false, 1.0 / 144, 0, 0, 100);
        }

        assertEquals(sixtyFrames.focus().x(), highRefreshRate.focus().x(), EPSILON);
        assertEquals(sixtyFrames.focus().y(), highRefreshRate.focus().y(), EPSILON);
        assertEquals(sixtyFrames.focus().z(), highRefreshRate.focus().z(), EPSILON);
        double displacementPerAxis = 32.0 / (3 * Math.sqrt(3));
        assertEquals(displacementPerAxis, sixtyFrames.focus().x(), EPSILON);
        assertEquals(65 + displacementPerAxis, sixtyFrames.focus().y(), EPSILON);
        assertEquals(-displacementPerAxis, sixtyFrames.focus().z(), EPSILON);
    }

    @Test
    void oneNominalFramePreservesTheLegacyPanningSpeed() {
        CameraState legacy = new CameraState();
        CameraState frameBased = new CameraState();
        legacy.reset(0, 64, 0);
        frameBased.reset(0, 64, 0);

        legacy.pan(1, -1, false, 0, 0, 100);
        frameBased.move(1, -1, 0, false, 0.05, 0, 0, 100);

        assertEquals(legacy.focus().x(), frameBased.focus().x(), EPSILON);
        assertEquals(legacy.focus().y(), frameBased.focus().y(), EPSILON);
        assertEquals(legacy.focus().z(), frameBased.focus().z(), EPSILON);
        assertEquals(32.0 / 60, Math.hypot(frameBased.focus().x(), frameBased.focus().z()), EPSILON);
    }

    @Test
    void elevationMovesOnWorldYWithoutChangingTheOrbit() {
        CameraState state = new CameraState();
        state.reset(10, 64, -20);
        state.toggleAngleLock();
        state.rotate(30, -20);
        CameraState.Position before = state.focus();
        CameraState.Position cameraBefore = state.cameraPosition();

        state.move(0, 0, 1, false, 0.5, 10, -20, 100);
        state.move(0, 0, -1, false, 0.25, 10, -20, 100);

        assertEquals(before.x(), state.focus().x(), EPSILON);
        assertEquals(before.z(), state.focus().z(), EPSILON);
        assertEquals(before.y() + 32.0 / 12, state.focus().y(), EPSILON);
        assertEquals(cameraBefore.x(), state.cameraPosition().x(), EPSILON);
        assertEquals(cameraBefore.z(), state.cameraPosition().z(), EPSILON);
        assertEquals(cameraBefore.y() + 32.0 / 12, state.cameraPosition().y(), EPSILON);
        assertEquals(210, state.yaw(), EPSILON);
        assertEquals(40, state.pitch(), EPSILON);
        assertEquals(32, state.distance(), EPSILON);
    }

    @Test
    void lockingTheAngleStillAllowsUpwardAndDownwardMovement() {
        CameraState state = new CameraState();
        state.reset(0, 64, 0);

        state.move(0, 0, 1, false, 0.05, 0, 0, 100);
        assertEquals(65 + 32.0 / 60, state.focus().y(), EPSILON);
        state.move(0, 0, -1, false, 0.05, 0, 0, 100);

        assertEquals(65, state.focus().y(), EPSILON);
        assertTrue(state.angleLocked());
        assertEquals(180, state.yaw(), EPSILON);
        assertEquals(60, state.pitch(), EPSILON);
    }

    @Test
    void threeAxisMovementIsNormalizedAndShiftDoublesItsSpeed() {
        CameraState normal = new CameraState();
        CameraState fast = new CameraState();
        normal.reset(0, 64, 0);
        fast.reset(0, 64, 0);

        normal.move(1, -1, 1, false, 0.25, 0, 0, 100);
        fast.move(1, -1, 1, true, 0.25, 0, 0, 100);

        double displacementPerAxis = 32.0 / (12 * Math.sqrt(3));
        assertEquals(displacementPerAxis, normal.focus().x(), EPSILON);
        assertEquals(displacementPerAxis, normal.focus().y() - 65, EPSILON);
        assertEquals(-displacementPerAxis, normal.focus().z(), EPSILON);
        assertEquals(2 * normal.focus().x(), fast.focus().x(), EPSILON);
        assertEquals(2 * (normal.focus().y() - 65), fast.focus().y() - 65, EPSILON);
        assertEquals(2 * normal.focus().z(), fast.focus().z(), EPSILON);
    }

    @Test
    void zeroAndNegativeElapsedTimeDoNotMoveTheCamera() {
        CameraState state = new CameraState();
        state.reset(10, 64, -20);
        CameraState.Position before = state.focus();

        state.move(1, -1, 1, true, 0, 10, -20, 100);
        assertEquals(before, state.focus());
        state.move(1, -1, 1, true, -0.05, 10, -20, 100);
        assertEquals(before, state.focus());
    }

    @Test
    void elevationPreservesTheHorizontalLoadedRadiusLimit() {
        CameraState state = new CameraState();
        state.reset(10, 64, -20);
        for (int i = 0; i < 100; i++) {
            state.move(1, -1, 1, true, 0.05, 10, -20, 16);
        }

        assertEquals(16, Math.hypot(state.focus().x() - 10, state.focus().z() + 20), EPSILON);
        CameraState.Position before = state.focus();
        state.move(0, 0, 1, false, 0.05, 10, -20, 16);
        assertEquals(before.x(), state.focus().x(), EPSILON);
        assertEquals(before.z(), state.focus().z(), EPSILON);
        assertEquals(before.y() + 32.0 / 60, state.focus().y(), EPSILON);

        state.move(1, -1, 1, false, 0.05, -100, 50, 16);
        assertEquals(16, Math.hypot(state.focus().x() + 100, state.focus().z() - 50), EPSILON);
    }
}
