package ch.bissbert.swissworld.gen.surface;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which trees stand where, and their shapes. Pure, see {@link ColumnGrid}.
 * <p>Positions come from a jittered {@value #GRID} m grid over LV95 (one candidate per grid square, offset and kept by
 * {@link ColumnHash} of the square), so they depend on world position only. A candidate stands if its column's data
 * allows a tree there; the keep probability depends on SURF. Orchards are a fixed {@value #ORCHARD} m lattice.
 * Species follow SURF and the altitude band: broadleaf below {@value #BROADLEAF_TOP} m, mixed up to
 * {@value #MIXED_TOP} m, spruce up to {@value #SPRUCE_TOP} m, sparse larch-like trees up to {@value #TREE_LINE} m.
 * <p>Trees cross chunk borders: every chunk enumerates the trees within {@link #REACH} of it and places only the blocks
 * inside itself, so neighbours finish each other's trees from the same data.
 */
public final class TreePlan {
    static final int GRID = 4;
    static final int ORCHARD = 7;
    /** Furthest a tree's blocks reach from its trunk column. */
    public static final int REACH = 3;
    static final double BROADLEAF_TOP = 1000, MIXED_TOP = 1500, SPRUCE_TOP = 2000, TREE_LINE = 2300;
    private static final int SALT_X = 11, SALT_Z = 12, SALT_KEEP = 13, SALT_SPECIES = 14, SALT_SHAPE = 15;

    public enum Species { OAK, BIRCH, SPRUCE, LARCH, BUSH, FRUIT }

    /** A tree standing on column (x, z); {@code hash} drives its shape. */
    public record Tree(int x, int z, Species species, long hash) {}

    /**
     * One block of a tree relative to the block above its trunk column's ground. A leaf's {@code distance} is its
     * vanilla leaf distance to the nearest log of the same tree (1..6); 0 means persistent (a bush without a log).
     */
    public record Part(int dx, int dy, int dz, boolean log, int distance) {}

    private TreePlan() {}

    /** Chance that a grid candidate on this SURF class becomes a tree. */
    static double density(int surf) {
        return switch (surf) {
            case 1, 2, 3 -> 0.9; // forest
            case 14 -> 0.125; // sparse trees, bushes
            case 11 -> 0.05; // gardens, parks
            case 17 -> 0.003; // alpine pasture
            case 0 -> 0.0025; // meadow
            default -> 0;
        };
    }

    /** Trees whose trunk is within {@link #REACH} of the columns [minX..maxX] x [minZ..maxZ], in a fixed order. */
    public static List<Tree> trees(ColumnGrid g, int minX, int minZ, int maxX, int maxZ) {
        int ax = minX - REACH, az = minZ - REACH, bx = maxX + REACH, bz = maxZ + REACH;
        List<Tree> out = new ArrayList<>();
        // east grows with x, north shrinks with z
        long e0 = Math.floorDiv(g.east(ax), GRID), e1 = Math.floorDiv(g.east(bx), GRID);
        long n0 = Math.floorDiv(g.north(bz), GRID), n1 = Math.floorDiv(g.north(az), GRID);
        for (long i = e0; i <= e1; i++) {
            for (long j = n0; j <= n1; j++) {
                int x = g.xAt(i * GRID + ColumnHash.below(i, j, SALT_X, GRID));
                int z = g.zAt(j * GRID + ColumnHash.below(i, j, SALT_Z, GRID));
                if (x < ax || x > bx || z < az || z > bz || !allowed(g, x, z)) continue;
                int surf = g.surf(x, z);
                if (ColumnHash.unit(i, j, SALT_KEEP) >= density(surf)) continue;
                long e = g.east(x), n = g.north(z);
                Species s = species(surf, g.metres(x, z), ColumnHash.unit(e, n, SALT_SPECIES));
                if (s != null) out.add(new Tree(x, z, s, ColumnHash.of(e, n, SALT_SHAPE)));
            }
        }
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                long e = g.east(x), n = g.north(z);
                if (Math.floorMod(e, ORCHARD) != 0 || Math.floorMod(n, ORCHARD) != 0) continue;
                if (g.surf(x, z) != 12 || !allowed(g, x, z) || g.metres(x, z) > MIXED_TOP) continue;
                out.add(new Tree(x, z, Species.FRUIT, ColumnHash.of(e, n, SALT_SHAPE)));
            }
        }
        out.sort(Comparator.comparingInt(Tree::x).thenComparingInt(Tree::z));
        return out;
    }

    /** Data allows a tree on this column: dry land, no road, rail or building nearby, not raised as a bank. */
    static boolean allowed(ColumnGrid g, int x, int z) {
        if (!g.present(x, z) || g.metres(x, z) >= TREE_LINE || g.top(x, z) != g.ground(x, z)) return false;
        for (int i = -REACH; i <= REACH; i++) {
            for (int j = -REACH; j <= REACH; j++) {
                int cx = x + i, cz = z + j;
                if (surfaceOverlay(g, cx, cz)) return false;
                if (Math.abs(i) <= 2 && Math.abs(j) <= 2 && g.hasBuilding(cx, cz)) return false;
                if (Math.abs(i) <= 1 && Math.abs(j) <= 1 && g.source(cx, cz) != ColumnGrid.NONE) return false;
            }
        }
        return true;
    }

    /** Rock above a tunnel's roof (with this much to spare) is open ground for plants. */
    static final int TUNNEL_COVER = 10;

    /** The column has an overlay at the surface; a tunnel deep enough below the ground does not count. */
    public static boolean surfaceOverlay(ColumnGrid g, int x, int z) {
        if (g.overlay(x, z) == 0 && g.flags(x, z) == 0) return false;
        return !(g.flag(x, z, ColumnGrid.TUNNEL) && g.deck(x, z) + TUNNEL_COVER < g.ground(x, z));
    }

    /** Species by SURF and altitude; null above the tree line. {@code r} is uniform in [0, 1). */
    static Species species(int surf, double metres, double r) {
        if (metres >= TREE_LINE) return null;
        if (metres >= SPRUCE_TOP) return Species.LARCH;
        if ((surf == 14 || surf == 11) && r < 0.4) return Species.BUSH;
        Species broadleaf = r < 0.7 ? Species.OAK : Species.BIRCH;
        return switch (surf) {
            case 2 -> Species.SPRUCE;
            case 1 -> metres < MIXED_TOP ? broadleaf : Species.SPRUCE;
            case 3 -> metres < MIXED_TOP && r < 0.5 ? broadleaf : Species.SPRUCE;
            default -> metres < BROADLEAF_TOP ? broadleaf
                    : metres < MIXED_TOP ? (r < 0.5 ? broadleaf : Species.SPRUCE)
                    : Species.SPRUCE;
        };
    }

    /** The tree's blocks: logs first, then leaves with their distance. */
    public static List<Part> shape(Species s, long hash) {
        List<int[]> logs = new ArrayList<>(), leaves = new ArrayList<>();
        switch (s) {
            case OAK, BIRCH, FRUIT -> {
                int h = s == Species.FRUIT ? 3 : (s == Species.OAK ? 4 : 5) + (int) Math.floorMod(hash, 3L);
                for (int dy = 0; dy < h; dy++) logs.add(new int[] {0, dy, 0});
                for (int dy = Math.max(1, h - 3); dy <= h; dy++) {
                    int r = dy >= h - 1 ? 1 : 2;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                            if (corner && (dy == h || bit(hash, dx, dy, dz))) continue;
                            leaves.add(new int[] {dx, dy, dz});
                        }
                    }
                }
            }
            case SPRUCE, LARCH -> {
                boolean larch = s == Species.LARCH;
                int h = (larch ? 6 : 7) + (int) Math.floorMod(hash, 4L);
                for (int dy = 0; dy < h; dy++) logs.add(new int[] {0, dy, 0});
                for (int dy = larch ? 3 : 2; dy <= h; dy++) {
                    int t = h - dy;
                    int r = larch ? (t % 2 == 0 ? 1 : 0) : Math.min(REACH, 1 + t / 3) - (t % 2 == 0 ? 1 : 0);
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (dx * dx + dz * dz > r * r + r) continue;
                            if (larch && (dx != 0 || dz != 0) && Math.floorMod(ColumnHash.of(hash, dx * 31L + dz, dy), 3L) == 0) continue;
                            leaves.add(new int[] {dx, dy, dz});
                        }
                    }
                }
            }
            case BUSH -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) < 2 || !bit(hash, dx, 0, dz)) leaves.add(new int[] {dx, 0, dz});
                    }
                }
                leaves.add(new int[] {0, 1, 0});
            }
        }
        List<Part> out = new ArrayList<>();
        for (int[] l : logs) out.add(new Part(l[0], l[1], l[2], true, 0));
        for (int[] f : leaves) {
            if (contains(logs, f)) continue;
            int d = logs.isEmpty() ? 0 : Integer.MAX_VALUE;
            for (int[] l : logs) d = Math.min(d, Math.abs(f[0] - l[0]) + Math.abs(f[1] - l[1]) + Math.abs(f[2] - l[2]));
            if (d <= 6) out.add(new Part(f[0], f[1], f[2], false, d));
        }
        return out;
    }

    private static boolean bit(long hash, int dx, int dy, int dz) {
        return (ColumnHash.of(hash, dx * 31L + dz, dy) & 1) == 0;
    }

    private static boolean contains(List<int[]> list, int[] p) {
        for (int[] q : list) if (q[0] == p[0] && q[1] == p[1] && q[2] == p[2]) return true;
        return false;
    }
}
