package ch.bissbert.swissworld.gen.surface;

/** Neighbour-dependent overlay decisions: rail shapes, bridge railings and piers. Pure, see {@link ColumnGrid}. */
public final class OverlayPlan {
    public static final int RAIL_BED = 8, RACK_BED = 9, DAM_CREST = 11;
    /** A pier every this many metres along a bridge. */
    static final int PIER_SPACING = 12;
    /** How far along each axis {@link #pier} looks to find the bridge's direction. */
    private static final int RUN_PROBE = 4;
    /** Columns kept free of piers on each side of a rail under a road bridge. */
    static final int TRACK_CLEARANCE = 2;
    /** No pier within this many columns of a way on the ground beside the bridge: that way passes under the deck. */
    static final int WAY_CLEARANCE = 3;

    private OverlayPlan() {}

    /** Vanilla's {@code RailShape} by the same names; kept separate so this class needs no Minecraft classes. */
    public enum Rail {
        NORTH_SOUTH, EAST_WEST, ASCENDING_EAST, ASCENDING_WEST, ASCENDING_NORTH, ASCENDING_SOUTH,
        SOUTH_EAST, SOUTH_WEST, NORTH_WEST, NORTH_EAST;

        public boolean ascending() {
            return name().startsWith("ASCENDING");
        }
    }

    /** Columns that get a rail block: class-8 rail centre line, or a rail under a road bridge (rack, class 9, gets a bed only). */
    public static boolean railCentre(ColumnGrid g, int x, int z) {
        if (!g.present(x, z) || !g.flag(x, z, ColumnGrid.RAIL)) return false;
        return g.overlay(x, z) == RAIL_BED || railUnderBridge(g, x, z);
    }

    /** swd-format §5: bridge bit + road class 1-7 + rail bit: the road deck is at OELV, the rail on the terrain below. */
    public static boolean railUnderBridge(ColumnGrid g, int x, int z) {
        int cls = g.overlay(x, z);
        return g.present(x, z) && cls >= 1 && cls <= 7 && g.flag(x, z, ColumnGrid.BRIDGE) && g.flag(x, z, ColumnGrid.RAIL);
    }

    /** The block the rail rests on: the deck, or the terrain top under a road bridge. */
    public static int railBed(ColumnGrid g, int x, int z) {
        return railUnderBridge(g, x, z) ? g.top(x, z) : g.deck(x, z);
    }

    /**
     * Shape from the neighbouring rail centres (north is -z): a straight pair first, then a curve, then a dead end
     * pointing at its one neighbour; an isolated rail runs north-south. A straight rail ascends towards a neighbour
     * whose {@link #railBed} is one block higher (more is a gap the data should not have).
     */
    public static Rail railShape(ColumnGrid g, int x, int z) {
        boolean n = railCentre(g, x, z - 1), s = railCentre(g, x, z + 1);
        boolean e = railCentre(g, x + 1, z), w = railCentre(g, x - 1, z);
        int up = railBed(g, x, z) + 1;
        if ((n || s) && !(e || w) || n && s) {
            if (n && railBed(g, x, z - 1) == up) return Rail.ASCENDING_NORTH;
            if (s && railBed(g, x, z + 1) == up) return Rail.ASCENDING_SOUTH;
            return Rail.NORTH_SOUTH;
        }
        if ((e || w) && !(n || s) || e && w) {
            if (e && railBed(g, x + 1, z) == up) return Rail.ASCENDING_EAST;
            if (w && railBed(g, x - 1, z) == up) return Rail.ASCENDING_WEST;
            return Rail.EAST_WEST;
        }
        if (n) return e ? Rail.NORTH_EAST : Rail.NORTH_WEST;
        if (s) return e ? Rail.SOUTH_EAST : Rail.SOUTH_WEST;
        return Rail.NORTH_SOUTH;
    }

    public static boolean bridge(ColumnGrid g, int x, int z) {
        return g.present(x, z) && g.overlay(x, z) != 0 && g.flag(x, z, ColumnGrid.BRIDGE);
    }

    /**
     * A bridge column on the side of the deck: a neighbour has no overlay and the deck goes on behind it and along it,
     * so a walkable path stays free. A 2-wide deck gets a railing only on its east or south side, a 1-wide one none,
     * and the stepped edge of a diagonal deck none. Bridge ends (next to the road) stay open.
     */
    public static boolean railing(ColumnGrid g, int x, int z) {
        if (!bridge(g, x, z)) return false;
        // a rail bridge has no parapet: the wall posts of its short spans read as battlements (playtest 15.00.54)
        if (g.overlay(x, z) == RAIL_BED || g.overlay(x, z) == RACK_BED) return false;
        return side(g, x, z, 1, 0) || side(g, x, z, -1, 0) || side(g, x, z, 0, 1) || side(g, x, z, 0, -1);
    }

    /**
     * Open towards (dx, dz), with the deck behind (two more bridge columns, or one when this is the east/south side)
     * and running on beside this column and the one behind it.
     */
    private static boolean side(ColumnGrid g, int x, int z, int dx, int dz) {
        if (g.overlay(x + dx, z + dz) != 0 || !bridge(g, x - dx, z - dz)) return false;
        if (dx + dz < 0 && !bridge(g, x - 2 * dx, z - 2 * dz)) return false;
        return alongside(g, x, z, dx, dz, dz, dx) || alongside(g, x, z, dx, dz, -dz, -dx);
    }

    private static boolean alongside(ColumnGrid g, int x, int z, int dx, int dz, int px, int pz) {
        return bridge(g, x + px, z + pz) && bridge(g, x - dx + px, z - dz + pz);
    }

    /**
     * A bridge column on a pier line: every {@link #PIER_SPACING} m along the bridge, across its full width. The
     * bridge runs along whichever axis has more bridge columns nearby. No pier within {@link #TRACK_CLEARANCE} of a
     * rail under the bridge, nor within {@link #WAY_CLEARANCE} of a way that is no bridge (the sidecar burns a bridge
     * over the way under it, so the pier row would stand across that road as a wall with a gap in it).
     */
    public static boolean pier(ColumnGrid g, int x, int z) {
        if (!bridge(g, x, z)) return false;
        for (int i = -TRACK_CLEARANCE; i <= TRACK_CLEARANCE; i++) {
            for (int j = -TRACK_CLEARANCE; j <= TRACK_CLEARANCE; j++) {
                if (railUnderBridge(g, x + i, z + j)) return false;
            }
        }
        for (int i = -WAY_CLEARANCE; i <= WAY_CLEARANCE; i++) {
            for (int j = -WAY_CLEARANCE; j <= WAY_CLEARANCE; j++) {
                if (g.present(x + i, z + j) && g.overlay(x + i, z + j) != 0 && !g.flag(x + i, z + j, ColumnGrid.BRIDGE)) return false;
            }
        }
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
