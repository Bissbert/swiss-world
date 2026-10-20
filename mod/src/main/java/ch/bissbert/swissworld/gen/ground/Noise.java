package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.gen.surface.ColumnHash;

/**
 * Smooth value noise keyed by LV95 position (never by chunk or RNG state), so every chunk agrees on every column.
 * {@link #patch} is equalised to a uniform distribution, so palette weights become area shares directly.
 */
public final class Noise {
    private Noise() {}

    private static final int EQ_BINS = 256;
    /** Inverse-free equaliser: the CDF of the raw patch value at EQ_BINS points, measured once from the noise itself. */
    private static final float[] CDF = new float[EQ_BINS + 1];

    static {
        int[] hist = new int[EQ_BINS];
        int n = 0;
        for (long e = 0; e < 1200; e += 1) {
            for (long nn = 0; nn < 1200; nn += 7) {
                hist[Math.min(EQ_BINS - 1, (int) (rawPatch(e * 3 + 17, nn * 5 + 11, 1) * EQ_BINS))]++;
                n++;
            }
        }
        int acc = 0;
        for (int i = 0; i < EQ_BINS; i++) {
            CDF[i] = (float) acc / n;
            acc += hist[i];
        }
        CDF[EQ_BINS] = 1;
    }

    /** Value noise in [0, 1) with lattice spacing {@code scale} metres, smoothstep interpolation. */
    public static double value(long e, long n, int scale, int salt) {
        long ie = Math.floorDiv(e, scale), in = Math.floorDiv(n, scale);
        double fe = (double) (e - ie * scale) / scale, fn = (double) (n - in * scale) / scale;
        double se = fe * fe * (3 - 2 * fe), sn = fn * fn * (3 - 2 * fn);
        double a = ColumnHash.unit(ie, in, salt), b = ColumnHash.unit(ie + 1, in, salt);
        double c = ColumnHash.unit(ie, in + 1, salt), d = ColumnHash.unit(ie + 1, in + 1, salt);
        double ab = a + (b - a) * se, cd = c + (d - c) * se;
        return ab + (cd - ab) * sn;
    }

    private static double rawPatch(long e, long n, int salt) {
        return 0.75 * value(e, n, 7, salt) + 0.25 * value(e, n, 2, salt + 7919);
    }

    /** Patchy noise for block mixes: patches of ~3-7 m with ragged edges, uniform in [0, 1). */
    public static double patch(long e, long n, int salt) {
        double r = rawPatch(e, n, salt) * EQ_BINS;
        int i = Math.min(EQ_BINS - 1, (int) r);
        double f = r - i;
        return Math.min(0.999999, CDF[i] + (CDF[i + 1] - CDF[i]) * f);
    }
}
