package ch.bissbert.swissworld.gen.ground;

/** Altitude belt (zone taxonomy §B). Bounds come from {@link ClimateLines#treeline}, which already varies by region. */
public enum Belt {
    UNKNOWN, COLLINE, MONTANE, SUBALPINE, ALPINE, NIVAL;

    private static final Belt[] ALL = values();

    public static Belt byId(int id) {
        return id >= 0 && id < ALL.length ? ALL[id] : UNKNOWN;
    }

    /**
     * Belt of a column at {@code h} metres whose treeline is {@code tree} and regional snow line {@code snow}; a
     * south-facing slope ({@code south} 0..1) lifts every bound by up to 100 m.
     */
    public static Belt of(double h, double tree, double snow, double south) {
        double lift = 100 * south;
        if (h < tree - 1000 + lift) return COLLINE;
        if (h < tree - 450 + lift) return MONTANE;
        if (h < tree + lift) return SUBALPINE;
        if (h < snow + lift) return ALPINE;
        return NIVAL;
    }
}
