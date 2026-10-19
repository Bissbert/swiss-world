package ch.bissbert.swissworld.cell;

/** u16 decimetre height encoding (docs/swd-format.md §1). */
public final class Heights {
    public static final int NONE = 0xFFFF;

    private Heights() {}

    public static double toMetres(int raw) {
        return raw == NONE ? Double.NaN : raw / 10.0 - 200.0;
    }

    public static int encode(double metres) {
        if (Double.isNaN(metres)) return NONE;
        long r = Math.round((metres + 200.0) * 10.0);
        return (int) Math.max(0, Math.min(NONE - 1, r));
    }
}
