package ch.bissbert.swissworld.gen.ground;

/**
 * Where the ground is bare rock, loose scree or soil, as smooth functions of the landform (no randomness): the texture
 * thresholds them against slow boundary noise ({@link #EDGE} wide), so material follows ridges, faces, cones and
 * hollows, and the noise only frays the edges.
 *
 * <ul>
 *   <li><b>rockiness</b> rises with the smoothed slope; convex ground (ribs, crests, breaks of slope) sheds its soil
 *       earlier, hollows keep it longer. In soft zones it is exactly 0 below {@link #SOFT_FLOOR} degrees.</li>
 *   <li><b>scree</b> gathers below cliffs (cones tapering over {@link ZoneMap#CLIFF_PROBE} m), in steep gullies, and
 *       on thin alpine soils; loose-ground zones bring their own share.</li>
 * </ul>
 */
public final class Landform {
    private Landform() {}

    /** Below this smoothed slope a soft (open or forest) zone never shows rock. */
    public static final double SOFT_FLOOR = 26;
    /** Width of the band in which the boundary noise moves an edge (in rockiness or scree units). */
    public static final double EDGE = 0.4;

    /**
     * Convexity normalised to -1 (gully, hollow) .. +1 (rib, crest) from the 6 m convexity and the 50 m TPI.
     */
    public static double convex(double convexity, double tpi) {
        return clamp((convexity + 0.12 * tpi) / 1.2, -1, 1);
    }

    /**
     * Rockiness 0..1 of open or forest ground: 0 up to ~32 degrees (37 under trees), all rock by ~52; a crest takes 9
     * degrees off the onset, a hollow adds 6; alpine and nival ground 3 off. Never above 0 below {@link #SOFT_FLOOR}.
     * Non-decreasing in slope and in convexity.
     */
    public static double softRock(double slope, double convex, boolean forest, Belt belt) {
        double from = forest ? 37 : 32;
        if (belt == Belt.ALPINE || belt == Belt.NIVAL) from -= 3;
        from -= 9 * Math.max(0, convex);
        from += 6 * Math.max(0, -convex);
        from = Math.max(SOFT_FLOOR, from);
        return smooth((slope - from) / 20);
    }

    /**
     * Rock share of ground the photo calls soil: nothing up to 46 degrees (44 alpine), all rock by 56. Grass holds on
     * steep meadow, but a 2 m pixel of green on a near-vertical face is a tuft, not turf.
     */
    public static double faceRock(double slope, Belt belt) {
        double from = belt == Belt.ALPINE || belt == Belt.NIVAL ? 44 : 46;
        return smooth((slope - from) / 12);
    }

    /**
     * Rockiness of cliff zones: rock from ~30 degrees, but benches and gullies of a face keep turf or scree; ribs are
     * bare earlier.
     */
    public static double cliffRock(double slope, double convex) {
        double from = 30 - 10 * Math.max(0, convex) + 8 * Math.max(0, -convex);
        return smooth((Math.max(0, slope) - Math.max(16, from)) / 18);
    }

    /**
     * Scree share 0..1: the cone below a cliff ({@code cliffDist} metres up the fall line), steep concave gullies, thin
     * alpine soil, plus the zone's own share {@code prior}. Forest holds most of it.
     */
    public static double scree(double slope, double convex, int cliffDist, Belt belt, double prior, boolean forest) {
        double s = prior;
        if (cliffDist < ZoneMap.NO_CLIFF) {
            // the cone: densest at the cliff foot, thinning down over CLIFF_PROBE m, runs out on the flat
            s = Math.max(s, (1 - cliffDist / (double) (ZoneMap.CLIFF_PROBE + 4)) * (0.55 + 0.45 * smooth((slope - 8) / 14)));
        }
        boolean high = belt == Belt.SUBALPINE || belt == Belt.ALPINE || belt == Belt.NIVAL;
        double gully = smooth((-convex - 0.3) / 0.5) * smooth((slope - 24) / 12);
        s = Math.max(s, (high ? 0.85 : 0.45) * gully);
        if (belt == Belt.ALPINE || belt == Belt.NIVAL) s = Math.max(s, (belt == Belt.NIVAL ? 0.6 : 0.35) * smooth((slope - 22) / 14));
        return forest ? s * 0.4 : s;
    }

    /** True where {@code field} wins against the boundary noise {@code edge} (0..1): field 0.5 +- EDGE/2 is the fringe. */
    public static boolean over(double field, double edge) {
        return field + EDGE * (edge - 0.5) > 0.5;
    }

    static double smooth(double t) {
        return ClimateLines.smooth(t);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
