package ch.bissbert.swissworld.gen.ground;

/**
 * Snow depth of one column, in eighths of a block (one snow layer = 1, a full snow block = 8), at most {@link #MAX}.
 * Pure and monotonic: higher ground, a more northern aspect or a deeper hollow never gets less snow.
 *
 * <p>The local snow line is the regional one ({@link ClimateLines#snowLine}) shifted by aspect (north-facing slopes up to
 * {@link #ASPECT} m lower, south-facing as much higher), by hollows and lee (up to {@link #HOLLOW_MAX} m lower) and by
 * {@code jitter} (noise, metres). Depth starts in a {@link #BAND} m transition band centred on that line and grows
 * with height above it. Slopes steeper than {@link #SHED_FROM} degrees shed snow, none above {@link #SHED_TO}.
 */
public final class Snow {
    private Snow() {}

    public static final int MAX = 24;
    public static final double ASPECT = 200, HOLLOW_MAX = 160, BAND = 160, SHED_FROM = 38, SHED_TO = 50;

    /** The local snow line in metres. */
    public static double line(double regional, double slopeDeg, double northness, double hollow, double jitter) {
        double steep = Math.min(1, slopeDeg / 20);
        return regional - ASPECT * northness * steep - Math.min(HOLLOW_MAX, Math.max(0, hollow) * 30) + jitter;
    }

    /**
     * Continuous depth (eighths) at height h for local line {@code line}: 2 eighths at the line, a full block 150 m
     * above it, {@link #MAX} far above; zero half a band below. Shedding scales it down on steep ground.
     */
    public static double depth(double h, double line, double slopeDeg, double hollow) {
        double u = (h - line) / BAND + 0.5;
        if (u <= 0) return 0;
        double d = u < 1 ? 4 * u : 4 + 6 * (u - 1);
        d += Math.min(4, Math.max(0, hollow) * 0.8);
        double shed = Math.max(0, Math.min(1, (SHED_TO - slopeDeg) / (SHED_TO - SHED_FROM)));
        return Math.min(MAX, d * shed);
    }

    /** Whole eighths, dithered by {@code r} in [0, 1) so the band edge frays instead of stepping. */
    public static int eighths(double depth, double r) {
        if (depth <= 0) return 0;
        return Math.min(MAX, (int) Math.floor(depth + r));
    }
}
