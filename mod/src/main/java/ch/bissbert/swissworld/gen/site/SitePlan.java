package ch.bissbert.swissworld.gen.site;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import ch.bissbert.swissworld.gen.surface.OverlayPlan;
import ch.bissbert.swissworld.gen.surface.TransportPlan;
import ch.bissbert.swissworld.gen.surface.TreeData;
import ch.bissbert.swissworld.gen.surface.TreePlan;
import ch.bissbert.swissworld.gen.surface.WaterPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The resolved site of one tile of {@link #TILE} x {@link #TILE} columns: one shared {@link ColumnGrid} and
 * {@link TransportPlan}, the claim of every column, the final ground G, and the trees. Computed once per tile and used by
 * every stage of every chunk in it. No Minecraft classes. Owner: mod-siteplan.
 *
 * <p>Why a 128 x 128 tile: the plan needs {@link #MARGIN} columns of data around the area it answers for. Per chunk that
 * is (16 + 2·52)² / 16² = 56 columns sampled per column; per tile (128 + 104)² / 128² = 3.3. A tile is chunk aligned (8 x
 * 8 chunks), so a chunk lies in exactly one tile; a whole 1 km cell would not be (1000 is no multiple of 16), would hold
 * 3,900 chunks of which a player sees few, and would cost seconds before its first chunk.
 *
 * <p>Every value is a pure function of the cell data within a fixed reach of the column, keyed by world position, so a
 * column gets the same answer from every tile whose margin holds it, and chunks on a tile border agree.
 *
 * <p>Read API (also for other stages, e.g. mod-texture): {@link #claim}, {@link #ground} and {@link #distanceTo}, valid for
 * the columns of the tile plus {@link #PAD} around it ({@link #distanceTo}: the tile's columns). See NOTES-mod-siteplan.md.
 */
public final class SitePlan {
    /** Side of a tile in columns: 8 x 8 chunks. */
    public static final int TILE = 128;
    /** {@link #distanceTo} looks this far; claims and ground are known this far around the tile. */
    public static final int PAD = 16;
    /** Data around the tile: every claim and ground value within {@link #PAD} needs {@link TransportPlan#MARGIN} more. */
    public static final int MARGIN = PAD + TransportPlan.MARGIN;
    /** The answer of {@link #distanceTo} when the claim is not within {@link #PAD - 1}. */
    public static final int FAR = PAD;

    private final SwissCoords coords;
    private final int tx, tz, x0, z0;
    private final ColumnGrid grid;
    private final TransportPlan transport;
    /** Over the tile plus PAD: side SIDE, index (z - z0 + PAD) * SIDE + (x - x0 + PAD). */
    private static final int SIDE = TILE + 2 * PAD;
    private final byte[] claims = new byte[SIDE * SIDE];
    private final int[] ground = new int[SIDE * SIDE];
    private final List<TreePlan.Tree> trees;
    private final AtomicReferenceArray<byte[]> distances = new AtomicReferenceArray<>(Claim.values().length);

    /** Plans tile (tx, tz) from {@code cells}, which must hold every cell under the tile plus {@link #MARGIN}. */
    public SitePlan(SwissCoords coords, ColumnGrid.Cells cells, int tx, int tz) {
        this(coords, cells, TreeData.of(coords, cells), tx, tz);
    }

    public SitePlan(SwissCoords coords, ColumnGrid.Cells cells, TreeData treeData, int tx, int tz) {
        this(coords, byKm(coords, cells), cells, treeData, tx, tz);
    }

    /**
     * Plans tile (tx, tz) from the cells {@code cells} answers by km index: every cell under the tile plus
     * {@link #MARGIN}, and those the buildings near it reach ({@link SitePlans}).
     */
    public SitePlan(SwissCoords coords, GenContext.CellLookup cells, int tx, int tz) {
        this(coords, cells, byColumn(coords, cells), null, tx, tz);
    }

    private SitePlan(SwissCoords coords, GenContext.CellLookup km, ColumnGrid.Cells cells, TreeData treeData, int tx, int tz) {
        if (treeData == null) treeData = TreeData.of(coords, cells);
        this.lookup = km;
        this.coords = coords;
        this.tx = tx;
        this.tz = tz;
        this.x0 = tx * TILE;
        this.z0 = tz * TILE;
        long t0 = System.nanoTime();
        grid = new ColumnGrid(coords, cells, x0, z0, TILE, MARGIN);
        transport = new TransportPlan(grid);
        for (int z = z0 - PAD; z < z0 + TILE + PAD; z++) {
            for (int x = x0 - PAD; x < x0 + TILE + PAD; x++) resolve(x, z);
        }
        trees = TreePlan.trees(grid, treeData, x0, z0, x0 + TILE - 1, z0 + TILE - 1);
        nanos = System.nanoTime() - t0;
    }

    private final long nanos;
    private final GenContext.CellLookup lookup;

    private static GenContext.CellLookup byKm(SwissCoords c, ColumnGrid.Cells cells) {
        return (e, n) -> cells.at((int) ((long) e * 1000 - c.e0()), (int) (c.n0() - (long) n * 1000 - 1000));
    }

    private static ColumnGrid.Cells byColumn(SwissCoords c, GenContext.CellLookup cells) {
        return (x, z) -> cells.get(SwissCoords.cellKm(c.east(x)), SwissCoords.cellKm(c.north(z)));
    }

    /** The cell (eKm, nKm) as this plan was made with: null for ocean, or for a cell the plan did not load. */
    public SwdCell cell(int eKm, int nKm) {
        return lookup.get(eKm, nKm);
    }

    /** Time this plan took to compute. */
    public long nanos() {
        return nanos;
    }

    /** Tile of block column x (or z). */
    public static int tile(int x) {
        return Math.floorDiv(x, TILE);
    }

    public int tx() {
        return tx;
    }

    public int tz() {
        return tz;
    }

    public int minX() {
        return x0;
    }

    public int minZ() {
        return z0;
    }

    public SwissCoords coords() {
        return coords;
    }

    /** The tile's cell data: the tile plus {@link #MARGIN}. Read only. */
    public ColumnGrid grid() {
        return grid;
    }

    /** Ways, tunnels and earthworks for the tile's columns. */
    public TransportPlan transport() {
        return transport;
    }

    /** Every tree whose crown can reach the tile, in the order {@link TreePlan#trees} gives them. */
    public List<TreePlan.Tree> trees() {
        return trees;
    }

    /** The trees whose crown can reach the 16 x 16 chunk at (cx0, cz0), in the same order. */
    public List<TreePlan.Tree> trees(int cx0, int cz0) {
        List<TreePlan.Tree> out = new ArrayList<>();
        int r = TreePlan.REACH;
        for (TreePlan.Tree t : trees) {
            if (t.x() >= cx0 - r && t.x() <= cx0 + 15 + r && t.z() >= cz0 - r && t.z() <= cz0 + 15 + r) out.add(t);
        }
        return out;
    }

    /** The column lies in this tile. */
    public boolean contains(int x, int z) {
        return x >= x0 && x < x0 + TILE && z >= z0 && z < z0 + TILE;
    }

    private int index(int x, int z) {
        int i = x - x0 + PAD, j = z - z0 + PAD;
        if (i < 0 || j < 0 || i >= SIDE || j >= SIDE) {
            throw new IndexOutOfBoundsException("column " + x + "," + z + " outside tile " + tx + "," + tz + " + " + PAD);
        }
        return j * SIDE + i;
    }

    /** Who owns column (x, z). Valid within {@link #PAD} of the tile. */
    public Claim claim(int x, int z) {
        return Claim.of(claims[index(x, z)]);
    }

    /**
     * G: the top solid block of the column once the site is built (block y). Natural ground: TERR's block. A bank or
     * earthwork: its new top. A way: its deck block (rail ballast, road surface, bridge deck). Water: the bed. A
     * structure: its deck or crest (a tunnel: the top of its lining or cover). A building: the ground under it. The
     * surface over a deep tunnel: the terrain. Valid within {@link #PAD} of the tile.
     */
    public int ground(int x, int z) {
        return ground[index(x, z)];
    }

    /**
     * Chebyshev distance (columns) from (x, z) to the nearest column with claim {@code c}: 0 on such a column, at most
     * {@link #FAR} (= nothing within {@link #FAR} - 1). Valid for the tile's columns.
     */
    public int distanceTo(Claim c, int x, int z) {
        if (!contains(x, z)) throw new IndexOutOfBoundsException("column " + x + "," + z + " outside tile " + tx + "," + tz);
        byte[] d = distances.get(c.ordinal());
        if (d == null) {
            d = distanceField(c);
            distances.compareAndSet(c.ordinal(), null, d);
        }
        return d[(z - z0) * TILE + (x - x0)];
    }

    /** Chebyshev distance transform of one claim over the tile, capped at FAR: rows first, then columns. */
    private byte[] distanceField(Claim c) {
        byte target = (byte) c.ordinal();
        int[] row = new int[SIDE * SIDE];
        for (int j = 0; j < SIDE; j++) {
            int last = -10 * SIDE;
            for (int i = 0; i < SIDE; i++) {
                if (claims[j * SIDE + i] == target) last = i;
                row[j * SIDE + i] = i - last;
            }
            last = 10 * SIDE;
            for (int i = SIDE - 1; i >= 0; i--) {
                if (claims[j * SIDE + i] == target) last = i;
                row[j * SIDE + i] = Math.min(row[j * SIDE + i], last - i);
            }
        }
        byte[] out = new byte[TILE * TILE];
        for (int j = 0; j < TILE; j++) {
            for (int i = 0; i < TILE; i++) {
                int best = FAR, ci = i + PAD, cj = j + PAD;
                for (int k = 0; k <= PAD && k < best; k++) {
                    best = Math.min(best, Math.max(k, Math.min(row[(cj + k) * SIDE + ci], row[(cj - k) * SIDE + ci])));
                }
                out[j * TILE + i] = (byte) Math.min(FAR, best);
            }
        }
        return out;
    }

    /** Claim and G of one column. */
    private void resolve(int x, int z) {
        ColumnGrid g = grid;
        TransportPlan t = transport;
        int k = index(x, z);
        Claim c;
        int gy;
        if (!g.present(x, z)) {
            c = Claim.WATER;
            gy = g.ground(x, z);
        } else if (t.structure(x, z)) {
            int hi = t.tunnelHighCeiling(x, z), top = g.top(x, z);
            if (t.over(x, z)) {
                c = wayClaim(x, z);
                gy = t.deck(x, z);
            } else if (hi != ColumnGrid.NONE && hi + TreePlan.TUNNEL_COVER < top) {
                c = Claim.FREE; // a bore deep under the ground: the surface is natural
                gy = top;
            } else {
                c = Claim.STRUCTURE;
                gy = Math.max(top, hi + 1);
            }
        } else if (t.way(x, z)) {
            c = wayClaim(x, z);
            gy = t.deck(x, z);
        } else if (g.overlay(x, z) != 0 || g.flag(x, z, ColumnGrid.DAM_BODY)) {
            int cls = g.overlay(x, z);
            boolean wet = g.source(x, z) != ColumnGrid.NONE;
            boolean dam = cls == OverlayPlan.DAM_CREST || g.flag(x, z, ColumnGrid.DAM_BODY);
            if (dam || g.flag(x, z, ColumnGrid.BRIDGE)) {
                c = Claim.STRUCTURE;
                gy = g.deck(x, z);
            } else if (wet && !g.elevated(x, z)) {
                c = Claim.WATER; // a way drawn across water without height of its own is left out
                gy = g.ground(x, z);
            } else {
                c = TransportPlan.roadClass(cls) ? Claim.ROAD : Claim.PATH;
                gy = g.deck(x, z);
            }
        } else if (WaterPlan.sourceLevel(g, x, z) != ColumnGrid.NONE) {
            c = Claim.WATER;
            gy = g.ground(x, z);
        } else if (g.hasBuilding(x, z)) {
            c = Claim.BUILDING;
            gy = g.top(x, z);
        } else {
            int ew = t.earthwork(x, z);
            if (ew != ColumnGrid.NONE) {
                c = Claim.EARTHWORK;
                gy = ew;
            } else if (g.top(x, z) != g.ground(x, z)) {
                c = Claim.BANK;
                gy = g.top(x, z);
            } else {
                c = Claim.FREE;
                gy = g.ground(x, z);
            }
        }
        claims[k] = (byte) c.ordinal();
        ground[k] = gy;
    }

    private Claim wayClaim(int x, int z) {
        if (transport.railWay(x, z)) return Claim.RAIL;
        int cls = grid.overlay(x, z);
        return cls == 5 || cls == 6 || cls == 7 || cls == 13 ? Claim.PATH : Claim.ROAD;
    }
}
