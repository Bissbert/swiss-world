package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.SwissChunkGenerator;
import ch.bissbert.swissworld.gen.stage.TerrainStage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Cell data for a chunk plus {@link #MARGIN} columns around it, sampled once per stage, in block y.
 * Every decision that involves a neighbour column is made from this data only (never from the chunk's blocks),
 * so two chunks that share a border agree on it. No Minecraft classes: unit-testable without a bootstrap.
 */
public final class ColumnGrid {
    /**
     * Columns around the chunk whose {@link #top} a default grid answers. It samples {@link WaterPlan#BANK_REACH} - 1
     * more, because a bank's top looks that far beyond its neighbours. A grid's margin must not exceed {@link SwissChunkGenerator#CELL_MARGIN}: columns further out may lie
     * in cells that are not loaded.
     */
    public static final int MARGIN = 8;
    public static final int NONE = Integer.MIN_VALUE;
    /** Same as {@link SwissChunkGenerator#SEA_LEVEL_Y}, without loading that class (tests have no Minecraft bootstrap). */
    static final int SEA_Y = (int) SwissCoords.toY(193);
    static final int OCEAN_FLOOR_Y = SEA_Y - TerrainStage.OCEAN_FLOOR_DEPTH;

    public static final int BRIDGE = 1, TUNNEL = 2, RAIL = 4, EDGE = 8, DAM_BODY = 16;
    /** swd-format §5: a rail centre (RAIL) on its railway's main line. Cells built before it have none. */
    public static final int MAIN = 32;
    /** swd-format §5: a main-line centre (MAIN) on its network's trunk, kept where more than two main lines meet. */
    public static final int TRUNK = 64;

    /** Margin of a {@link #home} view around the chunk: far enough for a tree's neighbours and their clearance. */
    public static final int HOME_MARGIN = 24;

    private final SwissCoords coords;
    private final Cells cells;
    /** First sampled column, side of the sampled square, margin around the area, side of the area (16 for a chunk). */
    private final int minX, minZ, size, margin, width;
    /** A home view: only this cell's data, see {@link #home}. Null for the chunk grid. */
    private final SwdCell homeCell;
    /** Columns sampled so far in a lazy view; null when all are sampled up front. */
    private final java.util.concurrent.atomic.AtomicIntegerArray sampled;
    private final Map<Long, ColumnGrid> homes = new HashMap<>();
    private final boolean[] present, spans;
    private final int[] ground, source, kind, surf, ocls, oflg, oelv;
    private final double[] metres, waterMetres, overlayMetres;
    /** {@link #top} by column, NONE until computed; the value is a pure function, so a racing duplicate write is harmless. */
    private final int[] top;

    /** The cell containing block column (x, z), or null; {@link GenContext#cellAt} in the game. */
    @FunctionalInterface
    public interface Cells {
        SwdCell at(int x, int z);
    }

    public ColumnGrid(SwissCoords coords, Cells cells, int chunkMinX, int chunkMinZ) {
        this(coords, cells, chunkMinX, chunkMinZ, MARGIN + WaterPlan.BANK_REACH - 1);
    }

    public ColumnGrid(SwissCoords coords, Cells cells, int chunkMinX, int chunkMinZ, int margin) {
        this(coords, cells, chunkMinX, chunkMinZ, 16, margin);
        if (margin > SwissChunkGenerator.CELL_MARGIN) throw new IllegalArgumentException("margin " + margin);
    }

    /**
     * The square area of {@code width} columns from (minX, minZ) plus {@code margin} around it, sampled up front. The
     * caller makes sure {@code cells} has every cell under it (a site plan tile loads them itself).
     */
    public ColumnGrid(SwissCoords coords, Cells cells, int minX, int minZ, int width, int margin) {
        this(coords, cells, minX - margin, minZ - margin, width, margin, null);
        // row by row, the cell looked up once per km it crosses (a lookup is a hash map get)
        SwdCell last = null;
        long lastKey = Long.MIN_VALUE;
        for (int j = 0; j < size; j++) {
            int z = this.minZ + j;
            long nKm = SwissCoords.cellKm(coords.north(z));
            for (int i = 0; i < size; i++) {
                int x = this.minX + i;
                long key = ((long) SwissCoords.cellKm(coords.east(x)) << 32) ^ (nKm & 0xffffffffL);
                if (key != lastKey) {
                    last = cells.at(x, z);
                    lastKey = key;
                }
                sample(last, x, z, j * size + i);
            }
        }
        overlaySums = new int[(size + 1) * (size + 1)];
        for (int j = 0; j < size; j++) {
            int run = 0;
            for (int i = 0; i < size; i++) {
                if (ocls[j * size + i] != 0) run++;
                overlaySums[(j + 1) * (size + 1) + i + 1] = overlaySums[j * (size + 1) + i + 1] + run;
            }
        }
    }

    /** Prefix sums of overlay != 0 over the sampled square ((size + 1)², row major), or null in a lazy {@link #home} view. */
    private int[] overlaySums;

    /**
     * Some column of the box [x0, x1] x [z0, z1] (clipped to the sampled square) has overlay. Always true in a
     * {@link #home} view, which samples on demand. Every way, bore, dam and bridge needs overlay, so a box without any
     * has none of them.
     */
    public boolean anyOverlay(int x0, int z0, int x1, int z1) {
        int[] s = overlaySums;
        if (s == null) return true;
        int i0 = Math.max(0, x0 - minX), j0 = Math.max(0, z0 - minZ);
        int i1 = Math.min(size - 1, x1 - minX), j1 = Math.min(size - 1, z1 - minZ);
        if (i0 > i1 || j0 > j1) return false;
        int w = size + 1;
        return s[(j1 + 1) * w + i1 + 1] - s[j0 * w + i1 + 1] - s[(j1 + 1) * w + i0] + s[j0 * w + i0] != 0;
    }

    private ColumnGrid(SwissCoords coords, Cells cells, int minX, int minZ, int width, int margin, SwdCell homeCell) {
        this.coords = coords;
        this.cells = cells;
        this.minX = minX;
        this.minZ = minZ;
        this.margin = margin;
        this.width = width;
        this.size = width + 2 * margin;
        this.homeCell = homeCell;
        int n = size * size;
        sampled = homeCell == null ? null : new java.util.concurrent.atomic.AtomicIntegerArray(n);
        present = new boolean[n];
        spans = new boolean[n];
        ground = new int[n];
        source = new int[n];
        kind = new int[n];
        surf = new int[n];
        ocls = new int[n];
        oflg = new int[n];
        oelv = new int[n];
        metres = new double[n];
        waterMetres = new double[n];
        overlayMetres = new double[n];
        top = new int[n];
        Arrays.fill(top, NONE);
    }

    /**
     * The view of the cell containing column (x, z) as a tree there sees it: {@link #HOME_MARGIN} columns around the
     * chunk, sampled on demand, from that one cell only. Columns of other cells read as the nearest column of the home
     * cell (terrain and SURF), with no water, overlay or building. The home cell is loaded whenever a chunk looks at a
     * column in it, so every chunk gets the same answers from this view, whichever other cells it has. Null over ocean.
     */
    public ColumnGrid home(int x, int z) {
        if (homeCell != null) return this;
        SwdCell cell = cells.at(x, z);
        if (cell == null) return null;
        int d = HOME_MARGIN - margin;
        synchronized (homes) {
            return homes.computeIfAbsent(((long) cell.eKm() << 32) ^ (cell.nKm() & 0xFFFFFFFFL),
                    k -> new ColumnGrid(coords, (bx, bz) -> cell, minX - d, minZ - d, width, HOME_MARGIN, cell));
        }
    }

    /** The cell km of column x as this grid's data sees it: its home cell in a {@link #home} view. */
    public int cellE(int x) {
        return homeCell != null ? homeCell.eKm() : SwissCoords.cellKm(east(x));
    }

    public int cellN(int z) {
        return homeCell != null ? homeCell.nKm() : SwissCoords.cellKm(north(z));
    }

    public static ColumnGrid of(GenContext ctx) {
        return of(ctx, MARGIN + WaterPlan.BANK_REACH - 1);
    }

    public static ColumnGrid of(GenContext ctx, int margin) {
        return new ColumnGrid(ctx.coords, ctx::cellAt, ctx.chunk.getPos().getMinBlockX(), ctx.chunk.getPos().getMinBlockZ(), margin);
    }

    public int margin() {
        return margin;
    }

    /** Side of the area the grid was made for (16 for a chunk, a tile's side for a site plan). */
    public int width() {
        return width;
    }

    /** First column of the area (without the margin). */
    public int areaMinX() {
        return minX + margin;
    }

    public int areaMinZ() {
        return minZ + margin;
    }

    private void sample(SwdCell cell, int x, int z, int k) {
        long e = coords.east(x), nn = coords.north(z);
        boolean away = false;
        if (homeCell != null) {
            long e0 = homeCell.eKm() * 1000L, n0 = homeCell.nKm() * 1000L;
            away = e < e0 || e > e0 + 999 || nn < n0 || nn > n0 + 999;
            e = Math.max(e0, Math.min(e0 + 999, e));
            nn = Math.max(n0, Math.min(n0 + 999, nn));
        }
        int dx = SwissCoords.dx(e), dz = SwissCoords.dz(nn);
        source[k] = NONE;
        oelv[k] = NONE;
        waterMetres[k] = Double.NaN;
        overlayMetres[k] = Double.NaN;
        if (cell == null || !cell.inside(dx, dz)) {
            ground[k] = OCEAN_FLOOR_Y;
            metres[k] = 193;
            return;
        }
        present[k] = true;
        double h = cell.terrain(dx + 0.5, dz + 0.5);
        metres[k] = h;
        ground[k] = y(h);
        surf[k] = cell.surf(dx, dz);
        if (away) return;
        double w = cell.water(dx, dz);
        if (!Double.isNaN(w) && y(w) > ground[k]) {
            source[k] = y(w);
            waterMetres[k] = w;
        }
        kind[k] = cell.waterKind(dx, dz);
        ocls[k] = cell.overlay(dx, dz);
        oflg[k] = cell.overlayFlags(dx, dz);
        if (ocls[k] == PARKING && roofed(cell, dx, dz)) {
            // a car park polygon under a building is its deck or hall, not paving cut into the hillside the hall stands on
            ocls[k] = 0;
            oflg[k] = 0;
        }
        double o = cell.overlayElevation(dx, dz);
        if (!Double.isNaN(o)) {
            oelv[k] = y(o);
            overlayMetres[k] = o;
        }
        spans[k] = cell.spansAt(dx, dz) != null;
    }

    private static final int PARKING = 14;

    /** A building other than an open one (OBJEKTART 11 open building, 12 canopy: they stand over their paving) has a span here. */
    private static boolean roofed(SwdCell cell, int dx, int dz) {
        SwdCell.Span[] sp = cell.spansAt(dx, dz);
        if (sp == null || cell.buildings() == null) return false;
        for (SwdCell.Span s : sp) {
            int kind = cell.buildings().get(s.buildingIdx()).objektart();
            if (kind != 11 && kind != 12) return true;
        }
        return false;
    }

    /** The block occupied by height h: a column is solid (or wet) up to this y inclusive. */
    public static int y(double h) {
        return (int) Math.floor(SwissCoords.toY(h));
    }

    private int k(int x, int z) {
        int i = x - minX, j = z - minZ;
        if (i < 0 || j < 0 || i >= size || j >= size) {
            throw new IndexOutOfBoundsException("column " + x + "," + z + " outside the sampled margin");
        }
        int k = j * size + i;
        if (sampled != null && sampled.get(k) == 0) {
            // the flag is set (a volatile write) after the column's data, so a reader that sees it sees the data
            synchronized (sampled) {
                if (sampled.get(k) == 0) {
                    sample(cells.at(x, z), x, z, k);
                    sampled.set(k, 1);
                }
            }
        }
        return k;
    }

    /** Index of a column in per-column caches of {@link #cells()} entries. */
    int index(int x, int z) {
        return k(x, z);
    }

    /** The column lies at least {@code pad} columns inside the sampled square. */
    boolean inside(int x, int z, int pad) {
        int i = x - minX, j = z - minZ;
        return i >= pad && j >= pad && i < size - pad && j < size - pad;
    }

    int cells() {
        return size * size;
    }

    public long east(int x) {
        return coords.east(x);
    }

    public long north(int z) {
        return coords.north(z);
    }

    /** The block column x whose east is {@code e}: inverse of {@link #east}. */
    public int xAt(long e) {
        return (int) (e - coords.east(0));
    }

    /** The block column z whose north is {@code n}: inverse of {@link #north}. */
    public int zAt(long n) {
        return (int) (coords.north(0) - n);
    }

    /** Inside Switzerland with a loaded cell; false means ocean. */
    public boolean present(int x, int z) {
        return present[k(x, z)];
    }

    /** Top solid block from TERR (ocean: the sea floor). */
    public int ground(int x, int z) {
        return ground[k(x, z)];
    }

    /** Top source block of this column's water, or {@link #NONE}. */
    public int source(int x, int z) {
        return source[k(x, z)];
    }

    public int waterKind(int x, int z) {
        return kind[k(x, z)];
    }

    public int surf(int x, int z) {
        return surf[k(x, z)];
    }

    public int overlay(int x, int z) {
        return ocls[k(x, z)];
    }

    public int flags(int x, int z) {
        return oflg[k(x, z)];
    }

    public boolean flag(int x, int z, int bit) {
        return (oflg[k(x, z)] & bit) != 0;
    }

    /** Terrain height in real metres (ocean: 193). */
    public double metres(int x, int z) {
        return metres[k(x, z)];
    }

    /** Water surface in real metres where {@link #source} is set, else NaN. */
    public double waterMetres(int x, int z) {
        return waterMetres[k(x, z)];
    }

    /** OELV in real metres where {@link #elevated}, else NaN. */
    public double overlayMetres(int x, int z) {
        return overlayMetres[k(x, z)];
    }

    /** The column lies in the sampled area (the chunk plus {@link #margin()}). */
    public boolean contains(int x, int z) {
        int i = x - minX, j = z - minZ;
        return i >= 0 && j >= 0 && i < size && j < size;
    }

    public boolean hasBuilding(int x, int z) {
        return spans[k(x, z)];
    }

    /**
     * Top solid block after {@link WaterStage} has raised banks: {@code max(ground, bank)}.
     * Valid {@link WaterPlan#BANK_REACH} columns less far out than the other accessors, because banks look at water nearby.
     */
    public int top(int x, int z) {
        int k = k(x, z);
        if (top[k] == NONE) top[k] = Math.max(ground[k], WaterPlan.bankTop(this, x, z));
        return top[k];
    }

    /** OELV is set: the overlay has its own height (bridge, tunnel, embankment, dam crest). */
    public boolean elevated(int x, int z) {
        return oelv[k(x, z)] != NONE;
    }

    /** The overlay's surface block: OELV, else the (banked) terrain top. */
    public int deck(int x, int z) {
        int o = oelv[k(x, z)];
        return o != NONE ? o : top(x, z);
    }
}
