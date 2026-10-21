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
 *       neighbours matter: the inner corner of a step that runs diagonally or turns. Such a corner is a
 *       {@link Fill#pour}: no source at all, falling water from its floor up to the flowing top. Falling water stands on
 *       falling water, so no block of it has a source or solid below it except the lowest, and that one lies in a pit
 *       ({@link Fill#floor}) deep enough that at most one neighbour has a source beside it. So a step is water from
 *       shore to shore, whatever its height or course, with no stone, sand or plant in the channel.</li>
 *   <li>Dry columns up to {@link #BANK_REACH} from water whose ground is below that water are raised to it (but never
 *       above a 4-neighbour's water), so a bank is a flat gravel shore instead of a ridge with a pit behind it.</li>
 * </ul>
 */
public final class WaterPlan {
    public static final int WATERFALL = 6;

    private WaterPlan() {}

    /**
     * Water of one column: sources from {@code floor} to {@code sourceTop}, falling water above, and a flowing block
     * (level 1) at {@code fallTop}, level with the neighbouring source that feeds it. {@code floor} is the ground + 1
     * except under a pour. With {@code pour}, the column has no source: falling water from {@code floor} (one or more
     * blocks below the ground where the neighbours' water reaches down to it) up to below {@code fallTop}, and the
     * flowing block at it.
     */
    public record Fill(int sourceTop, int fallTop, int floor, boolean pour) {
        /** No falling part when {@code fallTop <= sourceTop}. */
        public boolean falls() {
            return fallTop > sourceTop;
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
        int floor = g.ground(x, z) + 1;
        if (!corner(g, x, z, s, top)) return new Fill(s, top, floor, false);
        // the pour's lowest block stands on solid ground: below the second lowest water beside it, so that at most one
        // neighbour has a source at its height
        int low = Integer.MAX_VALUE, second = Integer.MAX_VALUE;
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            if (sourceLevel(g, nx, nz) == ColumnGrid.NONE) continue;
            int lo = g.ground(nx, nz) + 1;
            if (lo < low) {
                second = low;
                low = lo;
            } else if (lo < second) {
                second = lo;
            }
        }
        return new Fill(s, top, Math.min(floor, second - 1), true);
    }

    /**
     * The part of this column's ground that a neighbour's water touches from the side: from the lowest water beside it
     * up to its own ground, no higher than that water's top. A riverbed's gravel is one block thick over stone, so a
     * step in the bed, or a shore a block above the bed, shows its stone there. {lo, hi}, or null.
     */
    public static int[] bedFace(ColumnGrid g, int x, int z) {
        if (!g.present(x, z)) return null;
        int lo = Integer.MAX_VALUE, hi = ColumnGrid.NONE;
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            Fill f = fill(g, nx, nz);
            if (f == null) continue;
            lo = Math.min(lo, f.floor());
            hi = Math.max(hi, f.sourceTop());
        }
        hi = Math.min(hi, g.ground(x, z));
        return lo <= hi ? new int[] {lo, hi} : null;
    }

    /** The lowest block above the sources would have two source neighbours: an inner corner of a step. */
    private static boolean corner(ColumnGrid g, int x, int z, int s, int top) {
        return top > s && sourcesAt(g, x, z, s + 1) >= 2;
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
