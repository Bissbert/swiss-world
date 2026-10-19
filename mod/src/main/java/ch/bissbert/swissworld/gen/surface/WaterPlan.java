package ch.bissbert.swissworld.gen.surface;

/**
 * Where water and banks go, chosen so that vanilla's fluid rules would leave it exactly as placed: nothing flows when
 * a block update finally reaches it. Pure, see {@link ColumnGrid}.
 * <ul>
 *   <li>Sources fill a column from the ground up to its source level. Every source has water or solid on its four
 *       sides at its own level: a dry neighbour that is lower gets a bank up to that level ({@link #bankTop}).</li>
 *   <li>A drop (river step, waterfall): the lower column gets falling water up to just below the upper source level
 *       and one flowing block (level 1) at it. Non-source water above water never spreads sideways.</li>
 *   <li>A waterfall column (WKND 6) is a one-block pool on its ground; it falls only below a higher neighbouring
 *       source. Falling water with no source beside its top would drain on the first update, so WLVL alone never
 *       raises it.</li>
 *   <li>Vanilla turns water with two source neighbours at its level, standing on a source or solid, into a source.
 *       Only the lowest block above a lower column's sources can stand on a source, so only there two upper source
 *       neighbours matter: the inner corner of a step that runs diagonally or turns. A one-block step corner is a
 *       {@link Fill#reed}: sand up to below the lower level, sugar cane at it (no source, holds no water, not solid) and
 *       the flowing top above; its lower neighbours without a top of their own get the level-2 water that the flowing
 *       top spreads to them ({@link Fill#lip}). Where the reed's conditions fail, or on higher steps, the corner is rock
 *       from its source level up ({@link Fill#rock}). Rock and cane are no source, so neither spreads.</li>
 *   <li>Dry columns up to {@link #BANK_REACH} from water whose ground is below that water are raised to it (but never
 *       above a 4-neighbour's water), so a bank is a flat gravel shore instead of a ridge with a pit behind it.</li>
 * </ul>
 */
public final class WaterPlan {
    public static final int WATERFALL = 6;

    private WaterPlan() {}

    /**
     * Water of one column: sources from {@code ground + 1} to {@code sourceTop}, falling water above, and a flowing
     * block (level 1) at {@code fallTop}, level with the neighbouring source that feeds it. With {@code rock}, the
     * part above {@code sourceTop} is solid instead.
     */
    public record Fill(int sourceTop, int fallTop, boolean rock, boolean reed, boolean lip) {
        /** No falling part when {@code fallTop <= sourceTop}. */
        public boolean falls() {
            return fallTop > sourceTop && !rock;
        }
    }

    /** How far from water a dry column lower than that water is raised to it. */
    static final int BANK_REACH = 2;

    /** The top source block, or {@link ColumnGrid#NONE}. A waterfall's only source is its pool. */
    public static int sourceLevel(ColumnGrid g, int x, int z) {
        int s = g.source(x, z);
        if (s == ColumnGrid.NONE || !g.present(x, z)) return ColumnGrid.NONE;
        return g.waterKind(x, z) == WATERFALL ? g.ground(x, z) + 1 : s;
    }

    /** Highest source level among the four neighbours, or {@link ColumnGrid#NONE}. */
    static int neighbourSource(ColumnGrid g, int x, int z) {
        int u = sourceLevel(g, x + 1, z);
        u = Math.max(u, sourceLevel(g, x - 1, z));
        u = Math.max(u, sourceLevel(g, x, z + 1));
        return Math.max(u, sourceLevel(g, x, z - 1));
    }

    /**
     * A column without water of its own is raised to the highest neighbouring source above it, or {@link ColumnGrid#NONE}.
     * A dry column without overlay or building is also raised to water up to {@link #BANK_REACH} away, but no higher
     * than the lowest water beside it. Needs {@link #sourceLevel} up to {@link #BANK_REACH} columns out.
     */
    public static int bankTop(ColumnGrid g, int x, int z) {
        if (sourceLevel(g, x, z) != ColumnGrid.NONE) return ColumnGrid.NONE;
        int ground = g.ground(x, z);
        int u = neighbourSource(g, x, z);
        if (g.present(x, z) && g.overlay(x, z) == 0 && g.flags(x, z) == 0 && !g.hasBuilding(x, z)) {
            int cap = Integer.MAX_VALUE;
            for (int[] d : SIDES) {
                int s = sourceLevel(g, x + d[0], z + d[1]);
                if (s != ColumnGrid.NONE) cap = Math.min(cap, s);
            }
            int far = ColumnGrid.NONE;
            for (int i = -BANK_REACH; i <= BANK_REACH; i++) {
                for (int j = -BANK_REACH; j <= BANK_REACH; j++) far = Math.max(far, sourceLevel(g, x + i, z + j));
            }
            u = Math.max(u, Math.min(far, cap));
        }
        return u > ground ? u : ColumnGrid.NONE;
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** This column's water, or null if it has none. */
    public static Fill fill(ColumnGrid g, int x, int z) {
        int s = sourceLevel(g, x, z);
        if (s == ColumnGrid.NONE) return null;
        int top = Math.max(s, neighbourSource(g, x, z));
        if (corner(g, x, z, s, top)) {
            boolean reed = reed(g, x, z, s, top);
            return new Fill(s, top, !reed, reed, false);
        }
        boolean lip = false;
        if (top == s) {
            // the reed's flowing top spreads onto the columns at the reed's own level, not onto the upper ones
            for (int[] d : SIDES) lip |= sourceLevel(g, x + d[0], z + d[1]) == s && reedAt(g, x + d[0], z + d[1]);
        }
        return new Fill(s, top, false, false, lip);
    }

    /** The lowest block above the sources would have two source neighbours: an inner corner of a step. */
    private static boolean corner(ColumnGrid g, int x, int z, int s, int top) {
        return top > s && sourcesAt(g, x, z, s + 1) >= 2;
    }

    /** Column (x, z) is a {@link Fill#reed} corner. */
    static boolean reedAt(ColumnGrid g, int x, int z) {
        int s = sourceLevel(g, x, z);
        if (s == ColumnGrid.NONE) return false;
        int top = Math.max(s, neighbourSource(g, x, z));
        return corner(g, x, z, s, top) && reed(g, x, z, s, top);
    }

    /**
     * A one-block step corner can hold a reed: sugar cane needs water beside the sand under it, every lower neighbour
     * is exactly one below (so the flowing top spreads onto water only), and every dry neighbour reaches the top.
     */
    private static boolean reed(ColumnGrid g, int x, int z, int s, int top) {
        if (top != s + 1 || g.waterKind(x, z) == WATERFALL) return false;
        boolean wetBelow = false;
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            int ns = sourceLevel(g, nx, nz);
            if (ns == ColumnGrid.NONE) {
                if (g.top(nx, nz) < top) return false;
                continue;
            }
            if (ns < s) return false;
            if (ns == s && g.waterKind(nx, nz) == WATERFALL) return false;
            if (g.ground(nx, nz) < s - 1) wetBelow = true;
        }
        return wetBelow;
    }

    /** Neighbours that have a source block at height y. */
    static int sourcesAt(ColumnGrid g, int x, int z, int y) {
        return sourceAt(g, x + 1, z, y) + sourceAt(g, x - 1, z, y) + sourceAt(g, x, z + 1, y) + sourceAt(g, x, z - 1, y);
    }

    private static int sourceAt(ColumnGrid g, int x, int z, int y) {
        int s = sourceLevel(g, x, z);
        return s != ColumnGrid.NONE && g.ground(x, z) < y && y <= s ? 1 : 0;
    }
}
