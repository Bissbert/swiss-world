package ch.bissbert.swissworld.gen.surface;

/**
 * Where water and banks go, chosen so that vanilla's fluid rules would leave it exactly as placed: nothing flows when
 * a block update finally reaches it. Pure, see {@link ColumnGrid}.
 * <ul>
 *   <li>Sources fill a column from the ground up to its source level. Every source has water or solid on its four
 *       sides at its own level: a dry neighbour that is lower gets a bank up to that level ({@link #bankTop}).</li>
 *   <li>A drop (river step, waterfall): the lower column gets falling water up to just below the upper source level
 *       and one flowing block (level 1) at it. Non-source water above water never spreads sideways.</li>
 *   <li>A waterfall column (WKND 6) is a one-block pool on its ground with falling water up to its WLVL.</li>
 * </ul>
 */
public final class WaterPlan {
    public static final int WATERFALL = 6;

    private WaterPlan() {}

    /** Water of one column. Sources from {@code ground + 1} to {@code sourceTop}, falling above, up to {@code fallTop}. */
    public record Fill(int sourceTop, int fallTop, boolean fedTop) {
        /** No falling part when {@code fallTop <= sourceTop}. */
        public boolean falls() {
            return fallTop > sourceTop;
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
        int u = neighbourSource(g, x, z);
        int own = g.source(x, z);
        int fallTop = Math.max(u, own);
        if (fallTop <= s) return new Fill(s, s, false);
        return new Fill(s, fallTop, fallTop == u);
    }
}
