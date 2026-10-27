package cam72cam.immersiverailroading.render;

/**
 * Picks a GIF frame from per-frame delays measured in game ticks.
 */
public final class GifTimeline {
    private GifTimeline() {
    }

    public static int frameAt(int tick, int[] delays) {
        if (delays == null || delays.length == 0) {
            return 0;
        }
        int total = 0;
        for (int delay : delays) {
            total += Math.max(delay, 1);
        }
        if (total <= 0) {
            return 0;
        }
        int cursor = Math.floorMod(tick, total);
        int covered = 0;
        for (int i = 0; i < delays.length; i++) {
            covered += Math.max(delays[i], 1);
            if (cursor < covered) {
                return i;
            }
        }
        return 0;
    }
}
