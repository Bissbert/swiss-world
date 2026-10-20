package ch.bissbert.swissworld.gen.ground;

/**
 * Regional climate lines as smooth functions of LV95 E/N (metres), so they never step at a region border.
 * Both rise from the northern edge of the Alps towards the inner Alps (Valais, Engadin), where it is drier and more
 * continental; the east adds a little (Engadin, Grisons).
 */
public final class ClimateLines {
    private ClimateLines() {}

    /** Regional (flat-ground) snow line in metres: ~2,700 m on the northern edge, ~3,000 m in Valais/Engadin. */
    public static double snowLine(long e, long n) {
        return 2700 + 300 * smooth((1_195_000 - n) / 80_000.0) + 80 * smooth((e - 2_700_000) / 100_000.0)
                - 60 * smooth((n - 1_200_000) / 60_000.0);
    }

    /** Treeline in metres: ~1,800 m on the northern flank, ~2,300 m in Valais/Engadin. */
    public static double treeline(long e, long n) {
        return 1800 + 450 * smooth((1_180_000 - n) / 50_000.0) + 60 * smooth((e - 2_740_000) / 60_000.0);
    }

    static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
