package dev.colony.camera.client.selection;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceSelectionTest {
    private static final SelectionState.Cell ANCHOR = new SelectionState.Cell(3, 10, -4);

    @Test
    void allFacesIntersectTheirCapturedPlaneAndKeepTheAnchorLayer() {
        for (FaceSelection.Face face : FaceSelection.Face.values()) {
            double plane = coordinate(ANCHOR, face.axis()) + (face.sign() > 0 ? 1 : 0);
            FaceSelection selection = new FaceSelection(ANCHOR, face, plane);
            double[] origin = {7.75, 14.25, -9.75};
            double[] direction = {0, 0, 0};
            origin[face.axis()] = plane + 6 * face.sign();
            direction[face.axis()] = -face.sign();

            assertTrue(selection.update(point(origin), point(direction)), face.toString());
            int[] expected = {7, 14, -10};
            expected[face.axis()] = coordinate(ANCHOR, face.axis());
            assertEquals(new SelectionState.Cell(expected[0], expected[1], expected[2]), selection.endpoint());
            assertEquals(ANCHOR, selection.anchor());
            assertEquals(face, selection.face());
            assertEquals(plane, selection.planeCoordinate());
        }
    }

    @Test
    void subsequentUpdatesStayOnTheOriginalPlaneIncludingFractionalHeight() {
        FaceSelection selection = new FaceSelection(ANCHOR, FaceSelection.Face.UP, 10.5);
        assertTrue(selection.update(new CursorRay.Point(3.5, 20, -3.5), new CursorRay.Point(1, -1, 1)));
        assertEquals(new SelectionState.Cell(13, 10, 6), selection.endpoint());

        assertTrue(selection.update(new CursorRay.Point(3.5, 30, -3.5), new CursorRay.Point(1, -1, 1)));
        assertEquals(new SelectionState.Cell(23, 10, 16), selection.endpoint());
        assertEquals(10.5, selection.planeCoordinate());
    }

    @Test
    void negativeTangentialCoordinatesUseFloorRatherThanTruncation() {
        FaceSelection selection = new FaceSelection(ANCHOR, FaceSelection.Face.UP, 11);
        assertTrue(selection.update(new CursorRay.Point(-0.01, 20, -8.9), new CursorRay.Point(0, -1, 0)));
        assertEquals(new SelectionState.Cell(-1, 10, -9), selection.endpoint());
        assertEquals(new SelectionState.Cuboid(ANCHOR, selection.endpoint()), selection.bounds(1));
    }

    @Test
    void parallelAndBehindRaysRetainTheLastValidEndpoint() {
        FaceSelection selection = new FaceSelection(ANCHOR, FaceSelection.Face.UP, 11);
        assertTrue(selection.update(new CursorRay.Point(6.5, 20, -1.5), new CursorRay.Point(0, -1, 0)));
        SelectionState.Cell previous = selection.endpoint();

        assertFalse(selection.update(new CursorRay.Point(9, 20, -8), new CursorRay.Point(1, 0, 0)));
        assertFalse(selection.update(new CursorRay.Point(9, 20, -8), new CursorRay.Point(1, -1.0e-9, 0)));
        assertFalse(selection.update(new CursorRay.Point(9, 20, -8), new CursorRay.Point(0, 1, 0)));
        assertEquals(previous, selection.endpoint());
    }

    @Test
    void originOnThePlaneIsAValidZeroDistanceIntersection() {
        FaceSelection selection = new FaceSelection(ANCHOR, FaceSelection.Face.NORTH, -4);
        assertTrue(selection.update(new CursorRay.Point(-2.5, 13.1, -4), new CursorRay.Point(0, 0, 1)));
        assertEquals(new SelectionState.Cell(-3, 13, -4), selection.endpoint());
    }

    @Test
    void invalidAndOverflowingIntersectionsRetainTheLastValidEndpoint() {
        FaceSelection selection = new FaceSelection(ANCHOR, FaceSelection.Face.UP, 11);
        CursorRay.Point origin = new CursorRay.Point(3.5, 20, -3.5);
        CursorRay.Point direction = new CursorRay.Point(0, -1, 0);
        assertFalse(selection.update(null, direction));
        assertFalse(selection.update(origin, null));
        assertFalse(selection.update(new CursorRay.Point(Double.NaN, 20, 0), direction));
        assertFalse(selection.update(origin, new CursorRay.Point(0, Double.POSITIVE_INFINITY, 0)));
        assertFalse(selection.update(new CursorRay.Point((double) Integer.MAX_VALUE + 1, 20, 0), direction));
        assertFalse(selection.update(new CursorRay.Point((double) Integer.MIN_VALUE - 0.1, 20, 0), direction));
        assertFalse(selection.update(new CursorRay.Point(Double.MAX_VALUE, 20, 0),
                new CursorRay.Point(Double.MAX_VALUE, -1, 0)));
        assertEquals(ANCHOR, selection.endpoint());
    }

    @Test
    void reversedDragsProduceTheSameInclusiveRectangleBounds() {
        FaceSelection first = new FaceSelection(new SelectionState.Cell(2, 10, -3), FaceSelection.Face.UP, 11);
        FaceSelection reversed = new FaceSelection(new SelectionState.Cell(-4, 10, 5), FaceSelection.Face.UP, 11);
        assertTrue(first.update(new CursorRay.Point(-3.5, 20, 5.5), new CursorRay.Point(0, -1, 0)));
        assertTrue(reversed.update(new CursorRay.Point(2.5, 20, -2.5), new CursorRay.Point(0, -1, 0)));

        assertEquals(new HashSet<>(first.corners()), new HashSet<>(reversed.corners()));
        assertEquals(63, first.bounds(1).volume());
        assertEquals(63, reversed.bounds(1).volume());
        assertEquals(cells(first.bounds(1)), cells(reversed.bounds(1)));
    }

    @Test
    void signedLayerCountsIncludeTheBaseAndFollowEveryFaceNormal() {
        for (FaceSelection.Face face : FaceSelection.Face.values()) {
            FaceSelection selection = new FaceSelection(ANCHOR, face, coordinate(ANCHOR, face.axis()));
            for (int layers : new int[] {-3, -1, 1, 3}) {
                SelectionState.Cuboid bounds = selection.bounds(layers);
                int expectedEnd = coordinate(ANCHOR, face.axis())
                        + face.sign() * Integer.signum(layers) * (Math.abs(layers) - 1);
                assertEquals(ANCHOR, bounds.first());
                assertEquals(expectedEnd, coordinate(bounds.second(), face.axis()));
                assertEquals(Math.abs(layers), bounds.volume());
            }
        }
    }

    @Test
    void extrusionPreservesTheDraggedFootprint() {
        FaceSelection selection = new FaceSelection(ANCHOR, FaceSelection.Face.UP, 11);
        assertTrue(selection.update(new CursorRay.Point(5.5, 20, -1.5), new CursorRay.Point(0, -1, 0)));

        SelectionState.Cuboid outward = selection.bounds(3);
        assertEquals(new SelectionState.Cell(5, 12, -2), outward.second());
        assertEquals(27, outward.volume());
        SelectionState.Cuboid inward = selection.bounds(-3);
        assertEquals(new SelectionState.Cell(5, 8, -2), inward.second());
        assertEquals(27, inward.volume());
    }

    @Test
    void cornersUseFractionalHitPlaneAndWholeBlockTangentEdgesInPolygonOrder() {
        FaceSelection selection = new FaceSelection(ANCHOR, FaceSelection.Face.UP, 10.5);
        assertTrue(selection.update(new CursorRay.Point(5.5, 20, -1.5), new CursorRay.Point(0, -1, 0)));
        List<CursorRay.Point> corners = selection.corners();

        assertEquals(List.of(new CursorRay.Point(3, 10.5, -4), new CursorRay.Point(3, 10.5, -1),
                new CursorRay.Point(6, 10.5, -1), new CursorRay.Point(6, 10.5, -4)), corners);
        assertThrows(UnsupportedOperationException.class, () -> corners.add(new CursorRay.Point(0, 0, 0)));
    }

    @Test
    void everyFaceCornerPolygonHasTheRequestedOutwardWinding() {
        for (FaceSelection.Face face : FaceSelection.Face.values()) {
            FaceSelection selection = new FaceSelection(ANCHOR, face, coordinate(ANCHOR, face.axis()) + 0.5);
            List<CursorRay.Point> corners = selection.corners();
            assertEquals(4, corners.size());
            double[] firstEdge = difference(corners.get(1), corners.get(0));
            double[] secondEdge = difference(corners.get(2), corners.get(1));
            double[] normal = {
                    firstEdge[1] * secondEdge[2] - firstEdge[2] * secondEdge[1],
                    firstEdge[2] * secondEdge[0] - firstEdge[0] * secondEdge[2],
                    firstEdge[0] * secondEdge[1] - firstEdge[1] * secondEdge[0]};
            assertTrue(normal[face.axis()] * face.sign() > 0, face.toString());
            for (CursorRay.Point corner : corners) {
                assertEquals(selection.planeCoordinate(), coordinate(corner, face.axis()));
            }
        }
    }

    @Test
    void extremeCoordinatesCannotWrapExtrusionOrFaceOuterEdges() {
        FaceSelection maximum = new FaceSelection(new SelectionState.Cell(Integer.MAX_VALUE, 0, 0),
                FaceSelection.Face.EAST, (double) Integer.MAX_VALUE + 1);
        assertThrows(IllegalArgumentException.class, () -> maximum.bounds(2));
        assertEquals(Integer.MAX_VALUE - 1, maximum.bounds(-2).second().x());

        FaceSelection minimum = new FaceSelection(new SelectionState.Cell(0, Integer.MIN_VALUE, 0),
                FaceSelection.Face.DOWN, Integer.MIN_VALUE);
        assertThrows(IllegalArgumentException.class, () -> minimum.bounds(2));
        assertEquals(Integer.MIN_VALUE + 1, minimum.bounds(-2).second().y());
        assertEquals(-1, minimum.bounds(Integer.MIN_VALUE).second().y());
        assertThrows(IllegalArgumentException.class, () -> minimum.bounds(0));

        FaceSelection edge = new FaceSelection(new SelectionState.Cell(Integer.MAX_VALUE, 0, Integer.MAX_VALUE),
                FaceSelection.Face.UP, 1);
        assertTrue(edge.corners().contains(new CursorRay.Point((double) Integer.MAX_VALUE + 1, 1,
                (double) Integer.MAX_VALUE + 1)));
    }

    @Test
    void rotatedCameraProjectionStillSelectsTheSameWorldFaceRectangle() {
        CursorRay.Point target = new CursorRay.Point(4.25, 11, -5.75);
        CursorRay.Point camera = new CursorRay.Point(0, 24, 5);
        for (double yaw : new double[] {-15, 0, 15}) {
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(90), 16f / 9, 0.05f, 128);
            Matrix4f view = new Matrix4f().rotationX((float) Math.toRadians(35))
                    .rotateY((float) Math.toRadians(yaw));
            Matrix4f viewProjection = projection.mul(view);
            Vector4f clip = viewProjection.transform(new Vector4f((float) (target.x() - camera.x()),
                    (float) (target.y() - camera.y()), (float) (target.z() - camera.z()), 1));
            double mouseX = (clip.x / clip.w + 1) * 1600 / 2;
            double mouseY = (1 - clip.y / clip.w) * 900 / 2;
            CursorRay.Ray ray = CursorRay.unproject(mouseX, mouseY, 1600, 900,
                    new Matrix4f(viewProjection).invert());
            assertNotNull(ray);
            CursorRay.Point worldOrigin = new CursorRay.Point(camera.x() + ray.origin().x(),
                    camera.y() + ray.origin().y(), camera.z() + ray.origin().z());
            FaceSelection selection = new FaceSelection(ANCHOR, FaceSelection.Face.UP, 11);
            assertTrue(selection.update(worldOrigin, ray.direction()));
            assertEquals(new SelectionState.Cell(4, 10, -6), selection.endpoint());
        }
    }

    @Test
    void constructorRejectsMissingAnchorOrFaceAndNonFinitePlane() {
        assertThrows(NullPointerException.class, () -> new FaceSelection(null, FaceSelection.Face.UP, 0));
        assertThrows(NullPointerException.class, () -> new FaceSelection(ANCHOR, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FaceSelection(ANCHOR, FaceSelection.Face.UP, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new FaceSelection(ANCHOR, FaceSelection.Face.UP, Double.POSITIVE_INFINITY));
    }

    private static Set<SelectionState.Cell> cells(SelectionState.Cuboid bounds) {
        Set<SelectionState.Cell> cells = new HashSet<>();
        bounds.cells().forEach(cells::add);
        return cells;
    }

    private static CursorRay.Point point(double[] coordinates) {
        return new CursorRay.Point(coordinates[0], coordinates[1], coordinates[2]);
    }

    private static double[] difference(CursorRay.Point first, CursorRay.Point second) {
        return new double[] {first.x() - second.x(), first.y() - second.y(), first.z() - second.z()};
    }

    private static int coordinate(SelectionState.Cell cell, int axis) {
        return switch (axis) {
            case 0 -> cell.x();
            case 1 -> cell.y();
            default -> cell.z();
        };
    }

    private static double coordinate(CursorRay.Point point, int axis) {
        return switch (axis) {
            case 0 -> point.x();
            case 1 -> point.y();
            default -> point.z();
        };
    }
}
