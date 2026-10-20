package ch.bissbert.swissworld.gen.ground;

/**
 * Biogeographic regions of Switzerland (BAFU), ids 1..6 as in the zone taxonomy; 0 = unknown/outside.
 * Until the REGN layer exists, {@link #of(long, long)} is a coarse E/N lookup by three straight lines.
 */
public enum Region {
    UNKNOWN, JURA, MITTELLAND, NORTH_FLANK, WEST_CENTRAL, EAST_CENTRAL, SOUTH_FLANK;

    private static final Region[] ALL = values();

    public static Region byId(int id) {
        return id >= 0 && id < ALL.length ? ALL[id] : UNKNOWN;
    }

    /** Coarse fallback from LV95 E/N (metres). */
    public static Region of(long e, long n) {
        double ekm = e / 1000.0, nkm = n / 1000.0;
        // Ticino, Misox, Bergell, Poschiavo
        if (nkm < 1140 && ekm > 2665 || nkm < 1135 && ekm > 2720) return SOUTH_FLANK;
        // Valais: south of the Bernese Alps' main ridge, which rises from Les Diablerets to the Grimsel
        double ridge = 1130 + (ekm - 2560) * 0.25;
        if (ekm > 2540 && ekm < 2680 && nkm < ridge) return WEST_CENTRAL;
        if (ekm >= 2680 && nkm < 1200 + (ekm - 2680) * 0.2 && ekm > 2700) return EAST_CENTRAL;
        // northern foot of the Alps: Vevey - Thun - Luzern - St. Gallen
        double alpsEdge = 1145 + (ekm - 2555) * 0.59;
        double jura = 1165 + (ekm - 2500) * 0.55;
        if (nkm > jura && ekm < 2680) return JURA;
        if (nkm > alpsEdge) return MITTELLAND;
        return NORTH_FLANK;
    }
}
