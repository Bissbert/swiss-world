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
 *       Where a drop would put such a block (a step that runs diagonally or turns a corner), the lower column is
 *       rock from its source level up instead ({@link Fill#rock}). Rock is no source, so this never spreads.</li>
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
    public record Fill(int sourceTop, int fallTop, boolean rock) {
        /** No falling part when {@code fallTop <= sourceTop}. */
        public boolean falls() {
            return fallTop > sourceTop && !rock;
        }
    }

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

    /** A column without water of its own is raised to the highest neighbouring source above it, or {@link ColumnGrid#NONE}. */
    public static int bankTop(ColumnGrid g, int x, int z) {
        if (sourceLevel(g, x, z) != ColumnGrid.NONE) return ColumnGrid.NONE;
        int u = neighbourSource(g, x, z);
        return u > g.ground(x, z) ? u : ColumnGrid.NONE;
    }

    /** This column's water, or null if it has none. */
    public static Fill fill(ColumnGrid g, int x, int z) {
        int s = sourceLevel(g, x, z);
        if (s == ColumnGrid.NONE) return null;
        int top = Math.max(s, neighbourSource(g, x, z));
        boolean rock = false;
        for (int y = s + 1; y <= top && !rock; y++) rock = sourcesAt(g, x, z, y) >= 2;
        return new Fill(s, top, rock);
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
