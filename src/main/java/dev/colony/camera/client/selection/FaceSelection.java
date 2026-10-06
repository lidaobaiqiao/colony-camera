package dev.colony.camera.client.selection;

import java.util.List;
import java.util.Objects;

/** A block-aligned rectangle anchored to the actual face hit when the drag began. */
public final class FaceSelection {
    private static final double PARALLEL_EPSILON = 1.0e-8;

    private final SelectionState.Cell anchor;
    private final Face face;
    private final double planeCoordinate;
    private SelectionState.Cell endpoint;

    public FaceSelection(SelectionState.Cell anchor, Face face, double planeCoordinate) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.face = Objects.requireNonNull(face, "face");
        if (!Double.isFinite(planeCoordinate)) {
            throw new IllegalArgumentException("Face coordinate must be finite");
        }
        this.planeCoordinate = planeCoordinate;
        endpoint = anchor;
    }

    public SelectionState.Cell anchor() {
        return anchor;
    }

    public Face face() {
        return face;
    }

    public double planeCoordinate() {
        return planeCoordinate;
    }

    public SelectionState.Cell endpoint() {
        return endpoint;
    }

    /** The captured face remains fixed even when the current ray crosses other blocks. */
    public boolean update(CursorRay.Point worldRayOrigin, CursorRay.Point worldRayDirection) {
        if (!finite(worldRayOrigin) || !finite(worldRayDirection)) {
            return false;
        }
        int normalAxis = face.axis();
        double normalDirection = coordinate(worldRayDirection, normalAxis);
        if (Math.abs(normalDirection) < PARALLEL_EPSILON) {
            return false;
        }
        double distance = (planeCoordinate - coordinate(worldRayOrigin, normalAxis)) / normalDirection;
        if (!Double.isFinite(distance) || distance < 0) {
            return false;
        }

        int[] blockCoordinates = {anchor.x(), anchor.y(), anchor.z()};
        for (int axis = 0; axis < 3; axis++) {
            double intersection = coordinate(worldRayOrigin, axis)
                    + distance * coordinate(worldRayDirection, axis);
            if (!Double.isFinite(intersection)) {
                return false;
            }
            if (axis != normalAxis) {
                double floor = Math.floor(intersection);
                if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
                    return false;
                }
                blockCoordinates[axis] = (int) floor;
            }
        }
        endpoint = new SelectionState.Cell(blockCoordinates[0], blockCoordinates[1], blockCoordinates[2]);
        return true;
    }

    /** Positive layers extend along the clicked face normal, negative layers extend inward. */
    public SelectionState.Cuboid bounds(int signedLayers) {
        if (signedLayers == 0) {
            throw new IllegalArgumentException("A selection must contain at least its base layer");
        }
        long additionalLayers = Math.abs((long) signedLayers) - 1;
        long direction = (long) face.sign() * (signedLayers > 0 ? 1 : -1);
        int normalAxis = face.axis();
        long normalEndpoint = coordinate(anchor, normalAxis) + direction * additionalLayers;
        if (normalEndpoint < Integer.MIN_VALUE || normalEndpoint > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Selection depth exceeds block coordinate range");
        }
        int[] coordinates = {endpoint.x(), endpoint.y(), endpoint.z()};
        coordinates[normalAxis] = (int) normalEndpoint;
        return new SelectionState.Cuboid(anchor,
                new SelectionState.Cell(coordinates[0], coordinates[1], coordinates[2]));
    }

    /** Polygon-order corners on the hit face, including fractional slab or stair faces. */
    public List<CursorRay.Point> corners() {
        int firstAxis = (face.axis() + 1) % 3;
        int secondAxis = (face.axis() + 2) % 3;
        double firstMinimum = Math.min(coordinate(anchor, firstAxis), coordinate(endpoint, firstAxis));
        double firstMaximum = (long) Math.max(coordinate(anchor, firstAxis), coordinate(endpoint, firstAxis)) + 1;
        double secondMinimum = Math.min(coordinate(anchor, secondAxis), coordinate(endpoint, secondAxis));
        double secondMaximum = (long) Math.max(coordinate(anchor, secondAxis), coordinate(endpoint, secondAxis)) + 1;
        CursorRay.Point first = corner(firstAxis, secondAxis, firstMinimum, secondMinimum);
        CursorRay.Point second = corner(firstAxis, secondAxis, firstMaximum, secondMinimum);
        CursorRay.Point third = corner(firstAxis, secondAxis, firstMaximum, secondMaximum);
        CursorRay.Point fourth = corner(firstAxis, secondAxis, firstMinimum, secondMaximum);
        return face.sign() > 0 ? List.of(first, second, third, fourth) : List.of(first, fourth, third, second);
    }

    private CursorRay.Point corner(int firstAxis, int secondAxis, double first, double second) {
        double[] coordinates = new double[3];
        coordinates[face.axis()] = planeCoordinate;
        coordinates[firstAxis] = first;
        coordinates[secondAxis] = second;
        return new CursorRay.Point(coordinates[0], coordinates[1], coordinates[2]);
    }

    private static boolean finite(CursorRay.Point point) {
        return point != null && Double.isFinite(point.x()) && Double.isFinite(point.y())
                && Double.isFinite(point.z());
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

    public enum Face {
        DOWN(1, -1), UP(1, 1), NORTH(2, -1), SOUTH(2, 1), WEST(0, -1), EAST(0, 1);

        private final int axis;
        private final int sign;

        Face(int axis, int sign) {
            this.axis = axis;
            this.sign = sign;
        }

        public int axis() {
            return axis;
        }

        public int sign() {
            return sign;
        }
    }
}
