package cam72cam.immersiverailroading.gui.overlay;

import cam72cam.immersiverailroading.ConfigGraphics;
import cam72cam.immersiverailroading.entity.Locomotive;
import cam72cam.immersiverailroading.util.Speed;
import cam72cam.mod.MinecraftClient;
import cam72cam.mod.entity.Entity;
import cam72cam.mod.gui.helpers.GUIHelpers;

/**
 * One line while driving: speed, cruise target, throttle, grade, sand and slip.
 */
public final class DriverStrip {
    private DriverStrip() {
    }

    public static void render() {
        if (!ConfigGraphics.driverStrip) {
            return;
        }
        Entity riding = MinecraftClient.getPlayer().getRiding();
        if (!(riding instanceof Locomotive loco)) {
            return;
        }
        int color = 0xFFF2F2F2;
        if (loco.isCruiseEngaged()) {
            color = 0xFFB7F0C2;
        }
        if (loco.isSanding()) {
            color = 0xFFFFE08A;
        }
        if (loco.isSlipping() || loco.isDerailed()) {
            color = 0xFFFFAA33;
        }
        GUIHelpers.drawCenteredString(line(loco), GUIHelpers.getScreenWidth() / 2, GUIHelpers.getScreenHeight() - 24, color);
    }

    public static String line(Locomotive loco) {
        String unit = ConfigGraphics.speedUnit.toUnitString();
        StringBuilder text = new StringBuilder();
        text.append(display(loco.getCurrentSpeed())).append(' ').append(unit);
        if (loco.isCruiseEngaged()) {
            text.append("  cruise ").append(display(Speed.fromMetric(loco.getCruiseTarget())));
        }
        text.append(String.format("  throttle %.0f%%", loco.getThrottle() * 100));
        if (loco.getTrainBrake() > 0.02f) {
            text.append(String.format("  brake %.0f%%", loco.getTrainBrake() * 100));
        }
        double grade = Math.tan(Math.toRadians(loco.getRotationPitch())) * 100.0;
        if (Math.abs(grade) >= 0.4) {
            text.append(String.format("  grade %+.1f%%", grade));
        }
        if (loco.isSanding()) {
            text.append("  SAND");
        }
        if (loco.isSlipping()) {
            text.append("  SLIP");
        }
        if (loco.isDerailed()) {
            text.append("  OFF RAILS");
        }
        return text.toString();
    }

    private static String display(Speed speed) {
        double value = switch (ConfigGraphics.speedUnit) {
            case mph -> speed.imperial();
            case ms -> speed.metersPerSecond();
            case kmh -> speed.metric();
        };
        return String.format("%.0f", Math.abs(value));
    }
}
