package ch.bissbert.swissworld.command;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Pure rules for {@code /swiss}, {@code /swiss goto}: how long to wait for the target terrain, what the action bar says
 * meanwhile, and where exactly to land (street, not roof). Owner: mod-core.
 */
final class Arrival {
    /** Give up after this long without any sign of progress (cells arriving, chunks generated, sidecar downloading). */
    static final int NO_PROGRESS_TICKS = 60 * 20;
    /** ... or after this long in total, even while the sidecar still reports work in progress. */
    static final int HARD_CAP_TICKS = 10 * 60 * 20;
    static final int BAR_TICKS = 20;
    /** How far from the target column to look for open ground when the target is a building. */
    static final int LANDING_RADIUS = 32;
    /** Ground may sit this many blocks above the DEM top (road surfaces, kerbs) and still count as open. */
    static final int GROUND_SLACK = 2;

    /** (dx, dz) within {@link #LANDING_RADIUS}, nearest first; ties in a fixed order. */
    private static final int[][] OFFSETS = offsets(LANDING_RADIUS);

    private Arrival() {}

    interface Open {
        boolean test(int x, int z);
    }

    /**
     * Why the wait ends now ("no progress for 60 s", "still not ready after 10 min"), or null to keep waiting. The cap
     * exists because the progress signals are global: other players' chunks and prefetches move them too.
     */
    static String givesUp(int now, int start, int lastProgress, boolean sidecarBusy) {
        if (now - start > HARD_CAP_TICKS) return "still not ready after " + HARD_CAP_TICKS / 1200 + " min";
        return now - lastProgress > NO_PROGRESS_TICKS && !sidecarBusy ? "no progress for " + NO_PROGRESS_TICKS / 20 + " s" : null;
    }

    /** "Building terrain… 1/4 cells", then "Generating terrain… 6/9 chunks". */
    static String bar(int cellsReady, int cellsTotal, int chunksReady, int chunksTotal, boolean sidecarDown) {
        String s = cellsReady < cellsTotal
                ? "Building terrain… " + cellsReady + "/" + cellsTotal + " cells"
                : "Generating terrain… " + chunksReady + "/" + chunksTotal + " chunks";
        return s + (sidecarDown ? " (sidecar not reachable)" : "") + " · /swiss back cancels";
    }

    /**
     * A column a player can land on: not water, no building span, and the topmost solid block is at most
     * {@link #GROUND_SLACK} above the terrain (so not a roof, eave, bridge deck or tree trunk).
     */
    static boolean openColumn(int topY, boolean fluid, boolean building, double groundY) {
        return !fluid && !building && !Double.isNaN(groundY) && topY <= Math.floor(groundY) + GROUND_SLACK;
    }

    /** The open column nearest to (x, z), (x, z) itself included, within {@code radius}; null if there is none. */
    static int[] nearestOpen(int x, int z, int radius, Open open) {
        int r2 = radius * radius;
        for (int[] o : OFFSETS) {
            if (o[0] * o[0] + o[1] * o[1] > r2) break;
            if (open.test(x + o[0], z + o[1])) return new int[] {x + o[0], z + o[1]};
        }
        return null;
    }

    private static int[][] offsets(int r) {
        int[][] all = new int[(2 * r + 1) * (2 * r + 1)][];
        int i = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) all[i++] = new int[] {dx, dz};
        }
        Arrays.sort(all, Comparator.<int[]>comparingInt(o -> o[0] * o[0] + o[1] * o[1])
                .thenComparingInt(o -> o[1]).thenComparingInt(o -> o[0]));
        return all;
    }
}
