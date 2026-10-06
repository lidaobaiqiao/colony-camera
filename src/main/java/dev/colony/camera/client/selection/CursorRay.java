package dev.colony.camera.client.selection;

import org.joml.Matrix4f;

import javax.annotation.Nullable;

/** Unprojects screen coordinates into a ray relative to the camera's world position. */
public final class CursorRay {
    private CursorRay() {
    }

    @Nullable
    public static Ray unproject(double x, double y, double width, double height,
                                @Nullable Matrix4f inverseViewProjection) {
        if (inverseViewProjection == null || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)
                || width <= 0 || height <= 0 || x < 0 || x > width || y < 0 || y > height) {
            return null;
        }

        float determinant = inverseViewProjection.determinant();
        if (!Float.isFinite(determinant) || determinant == 0) {
            return null;
        }

        double ndcX = 2 * (x / width) - 1;
        double ndcY = 1 - 2 * (y / height);
        Point near = transform(ndcX, ndcY, -1, inverseViewProjection);
        Point far = transform(ndcX, ndcY, 1, inverseViewProjection);
        if (near == null || far == null) {
            return null;
        }

        double dx = far.x() - near.x();
        double dy = far.y() - near.y();
        double dz = far.z() - near.z();
        double length = Math.hypot(Math.hypot(dx, dy), dz);
        if (!Double.isFinite(length) || length == 0) {
            return null;
        }
        return new Ray(near, new Point(dx / length, dy / length, dz / length));
    }

    @Nullable
    private static Point transform(double x, double y, double z, Matrix4f matrix) {
        double transformedX = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
        double transformedY = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
        double transformedZ = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
        double w = matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33();
        if (!Double.isFinite(w) || w == 0) {
            return null;
        }
        transformedX /= w;
        transformedY /= w;
        transformedZ /= w;
        if (!Double.isFinite(transformedX) || !Double.isFinite(transformedY)
                || !Double.isFinite(transformedZ)) {
            return null;
        }
        return new Point(transformedX, transformedY, transformedZ);
    }

    public record Point(double x, double y, double z) {
    }

    public record Ray(Point origin, Point direction) {
    }
}
