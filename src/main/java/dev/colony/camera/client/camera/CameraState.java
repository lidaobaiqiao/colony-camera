package dev.colony.camera.client.camera;

/** Camera geometry, independent of Minecraft and the render thread. */
public final class CameraState {
    private static final double DEFAULT_YAW = 180;
    private static final double DEFAULT_PITCH = 60;
    private static final double MIN_PITCH = 15;
    private static final double MAX_PITCH = 85;
    private static final double DEFAULT_DISTANCE = 32;
    private static final double MIN_DISTANCE = 8;
    private static final double MAX_DISTANCE = 96;

    private Position focus = new Position(0, 0, 0);
    private double distance = DEFAULT_DISTANCE;
    private double yaw = DEFAULT_YAW;
    private double pitch = DEFAULT_PITCH;
    private boolean angleLocked = true;

    public void reset(double playerX, double playerY, double playerZ) {
        focus = new Position(playerX, playerY + 1, playerZ);
        distance = DEFAULT_DISTANCE;
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        angleLocked = true;
    }

    public void pan(double horizontal, double vertical, boolean fast,
                    double playerX, double playerZ, double radiusLimit) {
        move(horizontal, vertical, 0, fast, 0.05, playerX, playerZ, radiusLimit);
    }

    public void move(double horizontal, double vertical, double elevation, boolean fast,
                     double elapsedSeconds, double playerX, double playerZ, double radiusLimit) {
        double length = Math.sqrt(horizontal * horizontal + vertical * vertical + elevation * elevation);
        if (length == 0 || elapsedSeconds <= 0) {
            return;
        }
        // Same units per second as the original 20 Hz controls, independent of FPS.
        double speed = distance / 3 * elapsedSeconds * (fast ? 2 : 1);
        moveInViewPlane(horizontal / length * speed, -vertical / length * speed,
                playerX, playerZ, radiusLimit);
        if (elevation != 0) {
            focus = new Position(focus.x(), focus.y() + elevation / length * speed, focus.z());
        }
    }

    public void drag(double deltaX, double deltaY, double worldUnitsPerPixel,
                     double playerX, double playerZ, double radiusLimit) {
        if (deltaX == 0 && deltaY == 0) {
            return;
        }
        // Grab the ground plane: the scene follows the pointer, focus moves oppositely.
        double right = -deltaX * worldUnitsPerPixel;
        double forward = deltaY * worldUnitsPerPixel / Math.sin(Math.toRadians(pitch));
        moveInViewPlane(right, forward, playerX, playerZ, radiusLimit);
    }

    private void moveInViewPlane(double right, double forward,
                                 double playerX, double playerZ, double radiusLimit) {
        double yawRadians = Math.toRadians(yaw);
        double x = focus.x() - Math.cos(yawRadians) * right - Math.sin(yawRadians) * forward;
        double z = focus.z() - Math.sin(yawRadians) * right + Math.cos(yawRadians) * forward;
        double offsetX = x - playerX;
        double offsetZ = z - playerZ;
        double radius = Math.hypot(offsetX, offsetZ);
        if (radius > radiusLimit) {
            x = playerX + offsetX * radiusLimit / radius;
            z = playerZ + offsetZ * radiusLimit / radius;
        }
        focus = new Position(x, focus.y(), z);
    }

    public void zoom(double scrollDelta) {
        distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE,
                distance * Math.pow(0.9, scrollDelta)));
    }

    public Position cameraPosition() {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontalDistance = distance * Math.cos(pitchRadians);
        return new Position(focus.x() + horizontalDistance * Math.sin(yawRadians),
                focus.y() + distance * Math.sin(pitchRadians),
                focus.z() - horizontalDistance * Math.cos(yawRadians));
    }

    public void toggleAngleLock() {
        angleLocked = !angleLocked;
    }

    public void rotate(double yawDelta, double pitchDelta) {
        if (angleLocked) {
            return;
        }
        yaw = ((yaw + yawDelta) % 360 + 360) % 360;
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + pitchDelta));
    }

    public boolean angleLocked() {
        return angleLocked;
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    public Position focus() {
        return focus;
    }

    public double distance() {
        return distance;
    }

    public record Position(double x, double y, double z) {
    }
}
