package ch.bissbert.swissworld.gen.surface;

/** Neighbour-dependent overlay decisions: rail shapes, bridge railings and piers. Pure, see {@link ColumnGrid}. */
public final class OverlayPlan {
    public static final int RAIL_BED = 8, RACK_BED = 9, DAM_CREST = 11;
    /** A pier every this many metres along a bridge. */
    static final int PIER_SPACING = 12;
    /** How far along each axis {@link #pier} looks to find the bridge's direction. */
    private static final int RUN_PROBE = 4;

    private OverlayPlan() {}

    /** Vanilla's {@code RailShape} by the same names; kept separate so this class needs no Minecraft classes. */
    public enum Rail {
        NORTH_SOUTH, EAST_WEST, ASCENDING_EAST, ASCENDING_WEST, ASCENDING_NORTH, ASCENDING_SOUTH,
        SOUTH_EAST, SOUTH_WEST, NORTH_WEST, NORTH_EAST;

        public boolean ascending() {
            return name().startsWith("ASCENDING");
        }
    }

    /** Columns that get a rail block: class-8 rail centre line (rack railways, class 9, get a bed only). */
    public static boolean railCentre(ColumnGrid g, int x, int z) {
        return g.present(x, z) && g.overlay(x, z) == RAIL_BED && g.flag(x, z, ColumnGrid.RAIL);
    }

    /**
     * Shape from the neighbouring rail centres (north is -z): a straight pair first, then a curve, then a dead end
     * pointing at its one neighbour; an isolated rail runs north-south. A straight rail ascends towards a neighbour
     * whose deck is one block higher (more is a gap the data should not have).
     */
    public static Rail railShape(ColumnGrid g, int x, int z) {
        boolean n = railCentre(g, x, z - 1), s = railCentre(g, x, z + 1);
        boolean e = railCentre(g, x + 1, z), w = railCentre(g, x - 1, z);
        int up = g.deck(x, z) + 1;
        if ((n || s) && !(e || w) || n && s) {
            if (n && g.deck(x, z - 1) == up) return Rail.ASCENDING_NORTH;
            if (s && g.deck(x, z + 1) == up) return Rail.ASCENDING_SOUTH;
            return Rail.NORTH_SOUTH;
        }
        if ((e || w) && !(n || s) || e && w) {
            if (e && g.deck(x + 1, z) == up) return Rail.ASCENDING_EAST;
            if (w && g.deck(x - 1, z) == up) return Rail.ASCENDING_WEST;
            return Rail.EAST_WEST;
        }
        if (n) return e ? Rail.NORTH_EAST : Rail.NORTH_WEST;
        if (s) return e ? Rail.SOUTH_EAST : Rail.SOUTH_WEST;
        return Rail.NORTH_SOUTH;
    }

    public static boolean bridge(ColumnGrid g, int x, int z) {
        return g.present(x, z) && g.overlay(x, z) != 0 && g.flag(x, z, ColumnGrid.BRIDGE);
    }

    /** A bridge column on the side of the deck: a neighbour has no overlay. Bridge ends (next to the road) stay open. */
    public static boolean railing(ColumnGrid g, int x, int z) {
        if (!bridge(g, x, z)) return false;
        return g.overlay(x + 1, z) == 0 || g.overlay(x - 1, z) == 0 || g.overlay(x, z + 1) == 0 || g.overlay(x, z - 1) == 0;
    }

    /**
     * A bridge column on a pier line: every {@link #PIER_SPACING} m along the bridge, across its full width. The
     * bridge runs along whichever axis has more bridge columns nearby.
     */
    public static boolean pier(ColumnGrid g, int x, int z) {
        if (!bridge(g, x, z)) return false;
        int alongX = 0, alongZ = 0;
        for (int d = 1; d <= RUN_PROBE; d++) {
            if (bridge(g, x + d, z)) alongX++;
            if (bridge(g, x - d, z)) alongX++;
            if (bridge(g, x, z + d)) alongZ++;
            if (bridge(g, x, z - d)) alongZ++;
        }
        return alongX >= alongZ ? Math.floorMod(g.east(x), PIER_SPACING) == 0 : Math.floorMod(g.north(z), PIER_SPACING) == 0;
    }
}
