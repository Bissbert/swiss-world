package ch.bissbert.swissworld.cell;

/**
 * Block <-> LV95 mapping (docs/swd-format.md §1) and real height <-> y (PLAN.md vertical mapping).
 * Block (x, z) has its centre at E = e0 + x + 0.5, N = n0 - z - 0.5.
 */
public record SwissCoords(long e0, long n0) {
    public static final int Y_OFFSET = 2000;
    public static final double KNEE_M = 3000.0;
    public static final double ABOVE_KNEE_SCALE = 0.6;

    public long east(int x) {
        return e0 + x;
    }

    public long north(int z) {
        return n0 - z - 1;
    }

    public static int cellKm(long metres) {
        return (int) Math.floorDiv(metres, 1000L);
    }

    public static int dx(long east) {
        return (int) Math.floorMod(east, 1000L);
    }

    public static int dz(long north) {
        return 999 - (int) Math.floorMod(north, 1000L);
    }

    /** Real height in metres to (fractional) y. */
    public static double toY(double h) {
        return h <= KNEE_M ? h - Y_OFFSET : (KNEE_M - Y_OFFSET) + (h - KNEE_M) * ABOVE_KNEE_SCALE;
    }

    public static double toMetres(double y) {
        double knee = KNEE_M - Y_OFFSET;
        return y <= knee ? y + Y_OFFSET : KNEE_M + (y - knee) / ABOVE_KNEE_SCALE;
    }
}
