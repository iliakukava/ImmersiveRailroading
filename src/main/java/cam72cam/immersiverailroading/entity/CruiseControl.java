package cam72cam.immersiverailroading.entity;

/**
 * Speed hold for a locomotive. The target is signed km/h in the same direction as {@code Speed.metric()}.
 * Throttle only moves one notch at a time so the controller does not dump the wheels into a slip.
 */
public final class CruiseControl {
    public static final double ENGAGE_SPEED_KMH = 40;
    public static final double STEP_KMH = 5;
    public static final double HOLD_BAND_KMH = 1.5;
    public static final double MAX_TARGET_KMH = 400;

    private CruiseControl() {
    }

    public static double engageTarget(double speedKmh, float reverser) {
        if (Math.abs(speedKmh) >= 5) {
            return clamp(speedKmh);
        }
        return clamp((reverser < 0 ? -1 : 1) * ENGAGE_SPEED_KMH);
    }

    public static double nudge(double targetKmh, float reverser, boolean faster) {
        double sign = Math.abs(targetKmh) >= 1 ? Math.signum(targetKmh) : (reverser < 0 ? -1 : 1);
        double next = targetKmh + (faster ? sign * STEP_KMH : -sign * STEP_KMH);
        if (!faster && sign > 0 && next < 0) {
            return 0;
        }
        if (!faster && sign < 0 && next > 0) {
            return 0;
        }
        return clamp(next);
    }

    public static float nextThrottle(float throttle, float notch, double speedKmh, double targetKmh) {
        if (Math.abs(targetKmh) < 0.5) {
            return Math.max(0f, throttle - notch);
        }
        boolean wrongWay = Math.signum(speedKmh) != Math.signum(targetKmh) && Math.abs(speedKmh) > 2;
        if (wrongWay) {
            return Math.max(0f, throttle - notch);
        }
        double error = Math.abs(targetKmh) - Math.abs(speedKmh);
        if (Math.abs(error) <= HOLD_BAND_KMH) {
            return throttle;
        }
        if (error > 0) {
            return Math.min(1f, throttle + notch);
        }
        return Math.max(0f, throttle - notch);
    }

    private static double clamp(double targetKmh) {
        if (targetKmh > MAX_TARGET_KMH) {
            return MAX_TARGET_KMH;
        }
        if (targetKmh < -MAX_TARGET_KMH) {
            return -MAX_TARGET_KMH;
        }
        return targetKmh;
    }
}
