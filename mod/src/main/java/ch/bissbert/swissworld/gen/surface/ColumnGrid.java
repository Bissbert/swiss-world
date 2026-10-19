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
    /** Must not exceed {@link SwissChunkGenerator#CELL_MARGIN}: columns further out may lie in cells that are not loaded. */
    public static final int MARGIN = SwissChunkGenerator.CELL_MARGIN;
    public static final int NONE = Integer.MIN_VALUE;
    /** Same as {@link SwissChunkGenerator#SEA_LEVEL_Y}, without loading that class (tests have no Minecraft bootstrap). */
    static final int SEA_Y = (int) SwissCoords.toY(193);
    static final int OCEAN_FLOOR_Y = SEA_Y - TerrainStage.OCEAN_FLOOR_DEPTH;

    public static final int BRIDGE = 1, TUNNEL = 2, RAIL = 4, EDGE = 8, DAM_BODY = 16;

    /** Margin of a {@link #home} view around the chunk: far enough for a tree's neighbours and their clearance. */
    public static final int HOME_MARGIN = 24;

    private final SwissCoords coords;
    private final Cells cells;
    private final int minX, minZ, size;
    /** A home view: only this cell's data, see {@link #home}. Null for the chunk grid. */
    private final SwdCell homeCell;
    /** Columns sampled so far in a lazy view; null when all are sampled up front. */
    private final boolean[] sampled;
    private Map<Long, ColumnGrid> homes;
    private final boolean[] present, spans;
    private final int[] ground, source, kind, surf, ocls, oflg, oelv;
    private final double[] metres;
    private int[] top;

    /** The cell containing block column (x, z), or null; {@link GenContext#cellAt} in the game. */
    @FunctionalInterface
    public interface Cells {
        SwdCell at(int x, int z);
    }

    public ColumnGrid(SwissCoords coords, Cells cells, int chunkMinX, int chunkMinZ) {
        this(coords, cells, chunkMinX - MARGIN, chunkMinZ - MARGIN, 16 + 2 * MARGIN, null);
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                sample(cells, minX + i, minZ + j, j * size + i);
            }
        }
    }

    private ColumnGrid(SwissCoords coords, Cells cells, int minX, int minZ, int size, SwdCell homeCell) {
        this.coords = coords;
        this.cells = cells;
        this.minX = minX;
        this.minZ = minZ;
        this.size = size;
        this.homeCell = homeCell;
        int n = size * size;
        sampled = homeCell == null ? null : new boolean[n];
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
        if (homes == null) homes = new HashMap<>();
        int d = HOME_MARGIN - MARGIN;
        return homes.computeIfAbsent(((long) cell.eKm() << 32) ^ (cell.nKm() & 0xFFFFFFFFL),
                k -> new ColumnGrid(coords, (bx, bz) -> cell, minX - d, minZ - d, size + 2 * d, cell));
    }

    /** The cell km of column x as this grid's data sees it: its home cell in a {@link #home} view. */
    public int cellE(int x) {
        return homeCell != null ? homeCell.eKm() : SwissCoords.cellKm(east(x));
    }

    public int cellN(int z) {
        return homeCell != null ? homeCell.nKm() : SwissCoords.cellKm(north(z));
    }

    public static ColumnGrid of(GenContext ctx) {
        return new ColumnGrid(ctx.coords, ctx::cellAt, ctx.chunk.getPos().getMinBlockX(), ctx.chunk.getPos().getMinBlockZ());
    }

    private void sample(Cells cells, int x, int z, int k) {
        long e = coords.east(x), nn = coords.north(z);
        SwdCell cell = cells.at(x, z);
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
        if (!Double.isNaN(w) && y(w) > ground[k]) source[k] = y(w);
        kind[k] = cell.waterKind(dx, dz);
        ocls[k] = cell.overlay(dx, dz);
        oflg[k] = cell.overlayFlags(dx, dz);
        double o = cell.overlayElevation(dx, dz);
        if (!Double.isNaN(o)) oelv[k] = y(o);
        spans[k] = cell.spansAt(dx, dz) != null;
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
        if (sampled != null && !sampled[k]) {
            sampled[k] = true;
            sample(cells, x, z, k);
        }
        return k;
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

    public boolean hasBuilding(int x, int z) {
        return spans[k(x, z)];
    }

    /**
     * Top solid block after {@link WaterStage} has raised banks: {@code max(ground, bank)}.
     * Valid one column less far out than the other accessors, because banks look at neighbours.
     */
    public int top(int x, int z) {
        if (top == null) {
            top = new int[size * size];
            Arrays.fill(top, NONE);
        }
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
