package dev.colony.camera.client.selection;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorRayTest {
    private static final double EPSILON = 1.0e-5;
    private static final float NEAR = 0.05f;
    private static final float FAR = 256;

    @Test
    void centerAndFourCornersFollowPerspectiveWithTopLeftMouseOrigin() {
        double aspect = 16.0 / 9;
        double tangent = Math.tan(Math.toRadians(70) / 2);
        Matrix4f inverse = projection(70, aspect).invert();

        CursorRay.Ray center = CursorRay.unproject(800, 450, 1600, 900, inverse);
        assertNotNull(center);
        assertPoint(center.origin(), 0, 0, -NEAR);
        assertDirection(center, 0, 0, -1);

        for (int horizontal : new int[] {-1, 1}) {
            for (int vertical : new int[] {-1, 1}) {
                CursorRay.Ray corner = CursorRay.unproject(horizontal < 0 ? 0 : 1600,
                        vertical > 0 ? 0 : 900, 1600, 900, inverse);
                assertNotNull(corner);
                assertPoint(corner.origin(), horizontal * aspect * tangent * NEAR,
                        vertical * tangent * NEAR, -NEAR);
                assertDirection(corner, horizontal * aspect * tangent, vertical * tangent, -1);
            }
        }
    }

    @Test
    void viewYawAndPitchRotateTheWorldRay() {
        double pitch = Math.toRadians(60);
        for (double yaw : new double[] {0, 90, 180, 270}) {
            Matrix4f view = new Matrix4f().rotationX((float) pitch).rotateY((float) Math.toRadians(yaw));
            Matrix4f inverse = projection(70, 16.0 / 9).mul(view).invert();
            CursorRay.Ray ray = CursorRay.unproject(800, 450, 1600, 900, inverse);
            assertNotNull(ray);
            assertDirection(ray, Math.sin(Math.toRadians(yaw)) * Math.cos(pitch),
                    -Math.sin(pitch), -Math.cos(Math.toRadians(yaw)) * Math.cos(pitch));
        }
    }

    @Test
    void proportionalGuiCoordinatesProduceTheSameRayAtEveryScale() {
        Matrix4f inverse = projection(70, 16.0 / 9).invert();
        CursorRay.Ray fullSize = CursorRay.unproject(1200, 300, 1600, 900, inverse);
        CursorRay.Ray guiSize = CursorRay.unproject(300, 75, 400, 225, inverse);
        CursorRay.Ray fractionalSize = CursorRay.unproject(150, 37.5, 200, 112.5, inverse);

        assertNotNull(fullSize);
        assertEquals(fullSize, guiSize);
        assertEquals(fullSize, fractionalSize);
    }

    @Test
    void widerAspectRatioWidensHorizontalRayWithoutChangingVerticalFieldOfView() {
        CursorRay.Ray square = CursorRay.unproject(100, 0, 100, 100, projection(70, 1).invert());
        CursorRay.Ray wide = CursorRay.unproject(200, 0, 200, 100, projection(70, 2).invert());
        assertNotNull(square);
        assertNotNull(wide);

        assertEquals(2 * square.direction().x() / -square.direction().z(),
                wide.direction().x() / -wide.direction().z(), EPSILON);
        assertEquals(square.direction().y() / -square.direction().z(),
                wide.direction().y() / -wide.direction().z(), EPSILON);
    }

    @Test
    void changingFieldOfViewChangesOffCenterRaysAndPreservesTheCenter() {
        Matrix4f narrow = projection(40, 16.0 / 9).invert();
        Matrix4f wide = projection(100, 16.0 / 9).invert();
        CursorRay.Ray narrowCorner = CursorRay.unproject(1600, 0, 1600, 900, narrow);
        CursorRay.Ray wideCorner = CursorRay.unproject(1600, 0, 1600, 900, wide);
        assertNotNull(narrowCorner);
        assertNotNull(wideCorner);
        assertTrue(wideCorner.direction().x() / -wideCorner.direction().z()
                > narrowCorner.direction().x() / -narrowCorner.direction().z());
        assertDirection(CursorRay.unproject(800, 450, 1600, 900, narrow), 0, 0, -1);
        assertDirection(CursorRay.unproject(800, 450, 1600, 900, wide), 0, 0, -1);
    }

    @Test
    void extraProjectionRotationIsIncludedInTheCursorRay() {
        Matrix4f projectionWithEffect = projection(70, 1).rotateZ((float) (Math.PI / 2));
        CursorRay.Ray right = CursorRay.unproject(100, 50, 100, 100, projectionWithEffect.invert());
        assertNotNull(right);
        assertDirection(right, 0, -Math.tan(Math.toRadians(70) / 2), -1);
    }

    @Test
    void projectingThenUnprojectingHitsTheOriginalWorldPoint() {
        Matrix4f viewProjection = projection(70, 16.0 / 9).mul(new Matrix4f()
                .rotationX((float) Math.toRadians(12)).rotateY((float) Math.toRadians(20)));
        Vector4f world = new Vector4f(3, -5, -20, 1);
        Vector4f clip = viewProjection.transform(new Vector4f(world));
        double mouseX = (clip.x / clip.w + 1) * 1600 / 2;
        double mouseY = (1 - clip.y / clip.w) * 900 / 2;
        CursorRay.Ray ray = CursorRay.unproject(mouseX, mouseY, 1600, 900,
                new Matrix4f(viewProjection).invert());
        assertNotNull(ray);

        double dx = world.x - ray.origin().x();
        double dy = world.y - ray.origin().y();
        double dz = world.z - ray.origin().z();
        double distance = dx * ray.direction().x() + dy * ray.direction().y() + dz * ray.direction().z();
        assertTrue(distance > 0);
        assertEquals(world.x, ray.origin().x() + distance * ray.direction().x(), 3.0e-4);
        assertEquals(world.y, ray.origin().y() + distance * ray.direction().y(), 3.0e-4);
        assertEquals(world.z, ray.origin().z() + distance * ray.direction().z(), 3.0e-4);
    }

    @Test
    void invalidCoordinatesAndMatricesReturnNoRay() {
        Matrix4f inverse = projection(70, 1).invert();
        assertNull(CursorRay.unproject(50, 50, 100, 100, null));
        assertNull(CursorRay.unproject(Double.NaN, 50, 100, 100, inverse));
        assertNull(CursorRay.unproject(50, Double.POSITIVE_INFINITY, 100, 100, inverse));
        assertNull(CursorRay.unproject(50, 50, Double.NaN, 100, inverse));
        assertNull(CursorRay.unproject(50, 50, 100, Double.POSITIVE_INFINITY, inverse));
        assertNull(CursorRay.unproject(0, 0, 0, 100, inverse));
        assertNull(CursorRay.unproject(0, 0, 100, -1, inverse));
        assertNull(CursorRay.unproject(-1, 50, 100, 100, inverse));
        assertNull(CursorRay.unproject(101, 50, 100, 100, inverse));
        assertNull(CursorRay.unproject(50, -1, 100, 100, inverse));
        assertNull(CursorRay.unproject(50, 101, 100, 100, inverse));
        assertNull(CursorRay.unproject(50, 50, 100, 100, new Matrix4f().zero()));
        assertNull(CursorRay.unproject(50, 50, 100, 100, new Matrix4f().m22(0)));
        assertNull(CursorRay.unproject(50, 50, 100, 100, new Matrix4f().m00(Float.NaN)));
        assertNull(CursorRay.unproject(50, 50, 100, 100, new Matrix4f().m11(Float.POSITIVE_INFINITY)));
        // An invertible matrix may still place a clip endpoint at infinity.
        assertNull(CursorRay.unproject(50, 50, 100, 100, new Matrix4f().m23(1)));
    }

    private static Matrix4f projection(double fov, double aspect) {
        return new Matrix4f().perspective((float) Math.toRadians(fov), (float) aspect, NEAR, FAR);
    }

    private static void assertDirection(CursorRay.Ray ray, double x, double y, double z) {
        assertNotNull(ray);
        double length = Math.sqrt(x * x + y * y + z * z);
        assertPoint(ray.direction(), x / length, y / length, z / length);
        assertEquals(1, Math.sqrt(ray.direction().x() * ray.direction().x()
                + ray.direction().y() * ray.direction().y()
                + ray.direction().z() * ray.direction().z()), EPSILON);
    }

    private static void assertPoint(CursorRay.Point point, double x, double y, double z) {
        assertEquals(x, point.x(), EPSILON);
        assertEquals(y, point.y(), EPSILON);
        assertEquals(z, point.z(), EPSILON);
    }
}
