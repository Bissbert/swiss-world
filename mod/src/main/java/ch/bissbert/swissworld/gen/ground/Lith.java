package ch.bissbert.swissworld.gen.ground;

/**
 * Lithology (zone taxonomy §D), 3 bits. Until the LITH layer exists, {@link #guess} gives a coarse geological guess by
 * position and {@link #ofCliff} completes it from the cliff zone; what stays UNKNOWN gets generic grey rock.
 */
public enum Lith {
    UNKNOWN, LIMESTONE, CRYSTALLINE, SCHIST, MOLASSE, QUATERNARY;

    private static final Lith[] ALL = values();

    public static Lith byId(int id) {
        return id >= 0 && id < ALL.length ? ALL[id] : UNKNOWN;
    }

    /**
     * Coarse guess without a LITH layer: the Aar and Gotthard massifs (a band rising north-east from the Lotschental to
     * the Todi: Grimsel, Aletsch, Oberaar) and the high central and southern Alps are crystalline, the Jura limestone,
     * the Mittelland molasse. Elsewhere (the limestone and flysch nappes of the north flank) unknown.
     */
    public static Lith guess(long e, long n, double h, Region r) {
        double ekm = e / 1000.0, nkm = n / 1000.0;
        double axis = 1150 + (ekm - 2630) * 0.45;
        if (ekm > 2618 && ekm < 2715 && Math.abs(nkm - axis) < 11 && h > 1200) return CRYSTALLINE;
        return switch (r) {
            case WEST_CENTRAL, EAST_CENTRAL -> h > 1500 ? CRYSTALLINE : UNKNOWN;
            case SOUTH_FLANK -> h > 600 ? CRYSTALLINE : UNKNOWN;
            case JURA -> LIMESTONE;
            case MITTELLAND -> MOLASSE;
            default -> UNKNOWN;
        };
    }

    /** The lithology a cliff zone stands for (UNKNOWN for others). */
    public static Lith ofCliff(Zone z) {
        return switch (z) {
            case LIMESTONE_CLIFF, KARST -> LIMESTONE;
            case CRYSTALLINE_CLIFF -> CRYSTALLINE;
            case SCHIST_SLOPE -> SCHIST;
            default -> UNKNOWN;
        };
    }
}
