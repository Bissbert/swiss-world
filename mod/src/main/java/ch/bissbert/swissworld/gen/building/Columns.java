package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.Heights;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The spans of one building (by gid) over its world bbox, as block ranges.
 * Heights are extruded 1:1 from the mapped base (PLAN §3): {@code y(h) = baseY + (h - baseH)}, baseY = floor(toY(baseH)).
 * A column in a cell that is not loaded is UNKNOWN; callers treat it like the column they compare it with.
 */
public final class Columns {
    public static final byte OUT = 0, BODY = 1, EAVE = 2, UNKNOWN = 3;
    public static final int NONE = Integer.MIN_VALUE;

    public final int minX, minZ, maxX, maxZ, baseY;
    public final double baseH;
    private final int w;
    private final byte[] state;
    private final int[] lo, top2, eave2;

    private Columns(int minX, int minZ, int maxX, int maxZ, double baseH) {
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.baseH = baseH;
        this.baseY = (int) Math.floor(SwissCoords.toY(baseH));
        this.w = maxX - minX + 1;
        int n = w * (maxZ - minZ + 1);
        state = new byte[n];
        lo = new int[n];
        top2 = new int[n];
        eave2 = new int[n];
    }

    /** Collects the columns of {@code b} (an entry of {@code home}) from every loaded cell. */
    public static Columns of(CellView view, SwdCell home, SwdCell.Building b) {
        Columns c = new Columns(view.blockX(home, b.minX()), view.blockZ(home, b.minZ()),
                view.blockX(home, b.maxX()), view.blockZ(home, b.maxZ()), Heights.toMetres(b.baseH()));
        Map<SwdCell, Integer> idx = new IdentityHashMap<>();
        for (int z = c.minZ; z <= c.maxZ; z++) {
            for (int x = c.minX; x <= c.maxX; x++) {
                int i = c.index(x, z);
                SwdCell cell = view.cell(x, z);
                if (cell == null) {
                    c.state[i] = UNKNOWN;
                    continue;
                }
                int bi = idx.computeIfAbsent(cell, k -> indexOf(k, b.gid()));
                SwdCell.Span[] spans = bi < 0 ? null : view.spans(cell, x, z);
                c.lo[i] = Integer.MAX_VALUE;
                c.top2[i] = c.eave2[i] = NONE;
                if (spans != null) {
                    for (SwdCell.Span s : spans) {
                        if (s.buildingIdx() != bi) continue;
                        int t2 = (int) Math.round(2 * c.y(Heights.toMetres(s.topH())));
                        if (s.part() == 1) {
                            c.eave2[i] = Math.max(c.eave2[i], t2);
                        } else {
                            c.lo[i] = Math.min(c.lo[i], (int) Math.round(c.y(Heights.toMetres(s.bottomH()))));
                            c.top2[i] = Math.max(c.top2[i], t2);
                        }
                    }
                }
                // a body needs at least one full block; thinner slivers become eaves
                if (c.top2[i] != NONE && Math.floorDiv(c.top2[i], 2) - 1 >= c.lo[i]) c.state[i] = BODY;
                else if (c.top2[i] != NONE || c.eave2[i] != NONE) {
                    c.eave2[i] = Math.max(c.eave2[i], c.top2[i]);
                    c.state[i] = EAVE;
                }
            }
        }
        return c;
    }

    static int indexOf(SwdCell cell, long gid) {
        for (int i = 0; i < cell.buildings().size(); i++) if (cell.buildings().get(i).gid() == gid) return i;
        return -1;
    }

    /** Real height in metres to fractional y, anchored at this building's base. */
    public double y(double h) {
        return baseY + (h - baseH);
    }

    private int index(int x, int z) {
        return (z - minZ) * w + (x - minX);
    }

    public boolean inBox(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public byte state(int x, int z) {
        return inBox(x, z) ? state[index(x, z)] : OUT;
    }

    public boolean body(int x, int z) {
        return state(x, z) == BODY;
    }

    /** Lowest body block (y). Only meaningful for BODY columns. */
    public int lo(int x, int z) {
        return lo[index(x, z)];
    }

    /** Body top in half blocks; the column is solid below {@code top2 / 2}. */
    public int top2(int x, int z) {
        return top2[index(x, z)];
    }

    /** Highest full body block. */
    public int topFull(int x, int z) {
        return Math.floorDiv(top2(x, z), 2) - 1;
    }

    public int eave2(int x, int z) {
        return eave2[index(x, z)];
    }

    /** Top in half blocks of whatever roof (body or eave) the column has, NONE if outside or unknown. */
    public int roof2(int x, int z) {
        return switch (state(x, z)) {
            case BODY -> top2(x, z);
            case EAVE -> eave2(x, z);
            default -> NONE;
        };
    }

    /** True if (x, z) and all four neighbours are body (unknown neighbours count as body). */
    public boolean interior(int x, int z) {
        if (!body(x, z)) return false;
        for (Dir d : Dir.values()) {
            byte s = state(x + d.dx, z + d.dz);
            if (s != BODY && s != UNKNOWN) return false;
        }
        return true;
    }

    /** Neighbour of a body column with no building at all (not even an eave): an outer facade side. */
    public boolean facade(int x, int z, Dir d) {
        byte s = state(x + d.dx, z + d.dz);
        return s == OUT || s == EAVE;
    }

    /** Uphill direction from the roof gradient over two columns each way, or null on flat roofs and ridges. */
    public Dir uphill(int x, int z) {
        int own = top2(x, z);
        Dir best = null;
        int bestG = 0;
        for (Dir d : new Dir[]{Dir.E, Dir.S}) {
            int g = 0;
            for (int i = 1; i <= 2; i++) g += roof2(x + d.dx * i, z + d.dz * i, own) - roof2(x - d.dx * i, z - d.dz * i, own);
            if (Math.abs(g) > Math.abs(bestG)) {
                bestG = g;
                best = d;
            }
        }
        if (best == null) return null;
        Dir up = bestG > 0 ? best : best.opposite(), down = up.opposite();
        int n = roof2(x + down.dx, z + down.dz);
        return n == NONE && state(x + down.dx, z + down.dz) != UNKNOWN || n != NONE && n < own
                ? up : null;
    }

    private int roof2(int x, int z, int own) {
        int r = roof2(x, z);
        return r == NONE ? own : r;
    }
}
