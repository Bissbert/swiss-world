package ch.bissbert.swissworld.gen.surface;

/**
 * Deterministic per-column randomness keyed by LV95 position and a salt, so a column decides the same in every chunk
 * that looks at it and on every server, independent of generation order.
 */
public final class ColumnHash {
    private ColumnHash() {}

    /** SplitMix64 finaliser over (east, north, salt). */
    public static long of(long east, long north, int salt) {
        long h = east * 0x9E3779B97F4A7C15L + north * 0xC2B2AE3D27D4EB4FL + salt * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** Uniform in [0, 1). */
    public static double unit(long east, long north, int salt) {
        return (of(east, north, salt) >>> 11) * 0x1.0p-53;
    }

    /** Uniform in [0, bound). */
    public static int below(long east, long north, int salt, int bound) {
        return (int) Math.floorMod(of(east, north, salt), (long) bound);
    }
}
