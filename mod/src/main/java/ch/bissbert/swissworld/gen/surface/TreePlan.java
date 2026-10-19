package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.SwissCoords;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which trees stand where, and their shapes. Pure, see {@link ColumnGrid}.
 * <p>A cell with a {@code TTOP} table (swd-format §6a, via {@link TreeData}) gets exactly its real trees, with their
 * heights and crown radii. Other cells keep the statistical placement: a jittered {@value #GRID} m grid over LV95 (one
 * candidate per grid square, offset and kept by {@link ColumnHash} of the square), so positions depend on world position
 * only; the keep probability depends on SURF, and heights are drawn from realistic ranges (forest
 * {@value #FOREST_MIN}..{@value #FOREST_MAX} m, elsewhere {@value #OPEN_MIN}..{@value #OPEN_MAX} m). Orchards are a
 * fixed {@value #ORCHARD} m lattice. Species follow the data or SURF and the altitude band: broadleaf below
 * {@value #BROADLEAF_TOP} m, mixed up to {@value #MIXED_TOP} m, spruce up to {@value #SPRUCE_TOP} m, sparse larch-like
 * trees up to {@value #TREE_LINE} m.
 * <p>Trees cross chunk borders: every chunk enumerates the trees whose crown can reach it and places only the blocks
 * inside itself, so neighbours finish each other's trees from the same data. No crown is wider than {@link #REACH}, just inside
 * the margin the grid samples. A statistical candidate also checks {@value #CLEAR} columns around its trunk, so its crown
 * stays within {@link #STAT_REACH}.
 */
public final class TreePlan {
    static final int GRID = 4;
    static final int ORCHARD = 7;
    /**
     * Widest crown radius of any tree: a tree's blocks stay within this of its trunk column. One less than the sampled
     * margin ({@code CELL_MARGIN}), because a trunk's {@link ColumnGrid#top} looks at its neighbours.
     */
    public static final int REACH = ColumnGrid.MARGIN - 1;
    /** Columns around a statistical trunk that must be free of overlays (water and buildings: less). */
    static final int CLEAR = 3;
    /** Widest crown of a statistical tree: its clearance check must stay inside the sampled margin. */
    static final int STAT_REACH = ColumnGrid.MARGIN - CLEAR;
    static final double BROADLEAF_TOP = 1000, MIXED_TOP = 1500, SPRUCE_TOP = 2000, TREE_LINE = 2300;
    static final int FOREST_MIN = 18, FOREST_MAX = 35, OPEN_MIN = 8, OPEN_MAX = 20;
    /** Leaves further than this from a log (vanilla leaf distance) would decay. */
    static final int LEAF_REACH = 6;
    private static final int SALT_X = 11, SALT_Z = 12, SALT_KEEP = 13, SALT_SPECIES = 14, SALT_SHAPE = 15, SALT_HEIGHT = 16;

    public enum Species { OAK, BIRCH, SPRUCE, LARCH, BUSH, FRUIT }

    public enum Axis { X, Y, Z }

    /** A tree standing on column (x, z), {@code height} blocks tall with a crown {@code radius}; {@code hash} drives its shape. */
    public record Tree(int x, int z, Species species, int height, int radius, long hash) {}

    /**
     * One block of a tree relative to the block above its trunk column's ground. A log has its {@code axis}; a leaf's
     * {@code distance} is its vanilla leaf distance to the nearest log of the same tree (1..6), 0 means persistent
     * (a bush without a log).
     */
    public record Part(int dx, int dy, int dz, boolean log, int distance, Axis axis) {}

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

    /** {@link #trees(ColumnGrid, TreeData, int, int, int, int)} without tree tables: statistical placement everywhere. */
    public static List<Tree> trees(ColumnGrid g, int minX, int minZ, int maxX, int maxZ) {
        return trees(g, TreeData.NONE, minX, minZ, maxX, maxZ);
    }

    /** Trees whose crown can reach the columns [minX..maxX] x [minZ..maxZ], in a fixed order. */
    public static List<Tree> trees(ColumnGrid g, TreeData data, int minX, int minZ, int maxX, int maxZ) {
        List<Tree> out = new ArrayList<>();
        real(g, data, minX - REACH, minZ - REACH, maxX + REACH, maxZ + REACH, out);
        int ax = minX - STAT_REACH, az = minZ - STAT_REACH, bx = maxX + STAT_REACH, bz = maxZ + STAT_REACH;
        // east grows with x, north shrinks with z
        long e0 = Math.floorDiv(g.east(ax), GRID), e1 = Math.floorDiv(g.east(bx), GRID);
        long n0 = Math.floorDiv(g.north(bz), GRID), n1 = Math.floorDiv(g.north(az), GRID);
        for (long i = e0; i <= e1; i++) {
            for (long j = n0; j <= n1; j++) {
                int x = g.xAt(i * GRID + ColumnHash.below(i, j, SALT_X, GRID));
                int z = g.zAt(j * GRID + ColumnHash.below(i, j, SALT_Z, GRID));
                if (x < ax || x > bx || z < az || z > bz || hasTable(g, data, x, z) || !allowed(g, x, z)) continue;
                int surf = g.surf(x, z);
                if (ColumnHash.unit(i, j, SALT_KEEP) >= density(surf)) continue;
                long e = g.east(x), n = g.north(z);
                double m = g.metres(x, z);
                Species s = species(surf, m, ColumnHash.unit(e, n, SALT_SPECIES));
                if (s == null) continue;
                int h = height(s, surf, m, ColumnHash.unit(e, n, SALT_HEIGHT));
                out.add(new Tree(x, z, s, h, Math.min(STAT_REACH, crown(s, h)), ColumnHash.of(e, n, SALT_SHAPE)));
            }
        }
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                long e = g.east(x), n = g.north(z);
                if (Math.floorMod(e, ORCHARD) != 0 || Math.floorMod(n, ORCHARD) != 0) continue;
                if (g.surf(x, z) != 12 || hasTable(g, data, x, z) || !allowed(g, x, z) || g.metres(x, z) > MIXED_TOP) continue;
                int h = height(Species.FRUIT, 12, g.metres(x, z), ColumnHash.unit(e, n, SALT_HEIGHT));
                out.add(new Tree(x, z, Species.FRUIT, h, crown(Species.FRUIT, h), ColumnHash.of(e, n, SALT_SHAPE)));
            }
        }
        out.sort(Comparator.comparingInt(Tree::x).thenComparingInt(Tree::z));
        return out;
    }

    /** The column lies in a cell with a {@code TTOP} table, which replaces the statistical placement there. */
    private static boolean hasTable(ColumnGrid g, TreeData data, int x, int z) {
        return data.tops(SwissCoords.cellKm(g.east(x)), SwissCoords.cellKm(g.north(z))) != null;
    }

    /** The {@code TTOP} trees of every cell under [ax..bx] x [az..bz] (at most 2 x 2 cells: the area is < 1 km). */
    private static void real(ColumnGrid g, TreeData data, int ax, int az, int bx, int bz, List<Tree> out) {
        long eMin = g.east(ax), eMax = g.east(bx), nMin = g.north(bz), nMax = g.north(az);
        Set<Long> done = new HashSet<>();
        for (long e : new long[] {eMin, eMax}) {
            for (long n : new long[] {nMin, nMax}) {
                int eKm = SwissCoords.cellKm(e), nKm = SwissCoords.cellKm(n);
                if (!done.add(((long) eKm << 32) ^ (nKm & 0xFFFFFFFFL))) continue;
                TreeTops tops = data.tops(eKm, nKm);
                if (tops == null) continue;
                long e0 = eKm * 1000L, n0 = nKm * 1000L;
                for (TreeTops.Top t : tops.within((int) (eMin - e0), (int) (999 - (nMax - n0)), (int) (eMax - e0), (int) (999 - (nMin - n0)))) {
                    long te = e0 + t.dx(), tn = n0 + 999 - t.dz();
                    int x = g.xAt(te), z = g.zAt(tn);
                    if (!standing(g, x, z)) continue;
                    double r = ColumnHash.unit(te, tn, SALT_SPECIES);
                    Species s = species(t, g.surf(x, z), g.metres(x, z), r);
                    int radius = t.crownDm() > 0 ? (int) Math.round(t.crownDm() / 10.0) : crown(s, t.heightM());
                    out.add(new Tree(x, z, s, t.heightM(), Math.max(1, Math.min(REACH, radius)), ColumnHash.of(te, tn, SALT_SHAPE)));
                }
            }
        }
    }

    /** A real tree stands where its own column is dry, open land (the sidecar already dropped trees on overlays). */
    static boolean standing(ColumnGrid g, int x, int z) {
        return g.present(x, z) && g.top(x, z) == g.ground(x, z) && !surfaceOverlay(g, x, z) && !g.hasBuilding(x, z)
                && g.source(x, z) == ColumnGrid.NONE;
    }

    /** Species of a real tree: its {@code kind} where known, else by SURF and altitude. Real trees are never dropped. */
    static Species species(TreeTops.Top t, int surf, double metres, double r) {
        if (t.heightM() < 4) return Species.BUSH;
        Species broadleaf = r < 0.7 ? Species.OAK : Species.BIRCH;
        return switch (t.kind()) {
            case TreeTops.CONIFER -> metres >= SPRUCE_TOP ? Species.LARCH : Species.SPRUCE;
            case TreeTops.LARCH -> Species.LARCH;
            case TreeTops.BROADLEAF -> broadleaf;
            default -> {
                Species s = species(surf, metres, r);
                yield s == null ? Species.LARCH : s == Species.BUSH ? broadleaf : s;
            }
        };
    }

    /** Height in blocks of a statistical tree; {@code r} is uniform in [0, 1). */
    static int height(Species s, int surf, double metres, double r) {
        int lo, hi;
        if (s == Species.BUSH) return 2;
        if (s == Species.FRUIT) {
            lo = 4;
            hi = 7;
        } else if ((surf == 1 || surf == 2 || surf == 3) && metres < SPRUCE_TOP) {
            lo = FOREST_MIN;
            hi = FOREST_MAX;
        } else {
            lo = OPEN_MIN;
            hi = OPEN_MAX;
        }
        return lo + (int) (r * (hi - lo + 1));
    }

    /** Crown radius when the data has none: conifers are slim, oaks wide. */
    static int crown(Species s, int height) {
        int r = switch (s) {
            case SPRUCE -> (int) Math.round(1 + 0.12 * height);
            case LARCH, BIRCH -> (int) Math.round(1 + 0.1 * height);
            case OAK -> (int) Math.round(0.22 * height);
            case FRUIT -> height / 2;
            case BUSH -> 1;
        };
        return Math.max(1, Math.min(REACH, r));
    }

    /** Data allows a tree on this column: dry land, no road, rail or building nearby, not raised as a bank. */
    static boolean allowed(ColumnGrid g, int x, int z) {
        if (!g.present(x, z) || g.metres(x, z) >= TREE_LINE || g.top(x, z) != g.ground(x, z)) return false;
        for (int i = -CLEAR; i <= CLEAR; i++) {
            for (int j = -CLEAR; j <= CLEAR; j++) {
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

    public static List<Part> shape(Tree t) {
        return shape(t.species(), t.height(), t.radius(), t.hash());
    }

    /**
     * The tree's blocks: logs first, then leaves with their vanilla distance, found by a breadth-first walk from the
     * logs through the leaves (as vanilla computes it). Leaves further than {@value #LEAF_REACH} are left out, so as
     * placed no leaf decays and an update changes no distance. Wide crowns get branch logs to keep that short.
     */
    public static List<Part> shape(Species s, int height, int radius, long hash) {
        Shape b = new Shape(Math.max(1, Math.min(REACH, radius)), Math.max(2, height));
        switch (s) {
            case SPRUCE -> b.conifer(Math.max(2, (int) Math.round(0.3 * b.h)), false, hash);
            case LARCH -> b.conifer(Math.max(2, (int) Math.round(0.35 * b.h)), true, hash);
            case OAK, FRUIT -> b.broadleaf(0.62, s == Species.FRUIT ? 0.3 : 0.4, true, hash);
            case BIRCH -> b.broadleaf(0.6, 0.35, false, hash);
            case BUSH -> b.bush(hash);
        }
        return b.parts();
    }

    /** A tree under construction: a box of (2r + 1)^2 x h cells, each empty, leaf or log. */
    private static final class Shape {
        private static final byte EMPTY = 0, LEAF = 1;
        final int r, h, side;
        final byte[] cell;
        final Axis[] axis;

        Shape(int r, int h) {
            this.r = r;
            this.h = h;
            side = 2 * r + 1;
            cell = new byte[side * side * h];
            axis = new Axis[cell.length];
        }

        boolean inside(int dx, int dy, int dz) {
            return Math.abs(dx) <= r && Math.abs(dz) <= r && dy >= 0 && dy < h;
        }

        int k(int dx, int dy, int dz) {
            return (dy * side + dz + r) * side + dx + r;
        }

        void leaf(int dx, int dy, int dz) {
            if (inside(dx, dy, dz) && axis[k(dx, dy, dz)] == null) cell[k(dx, dy, dz)] = LEAF;
        }

        void log(int dx, int dy, int dz, Axis a) {
            if (!inside(dx, dy, dz)) return;
            cell[k(dx, dy, dz)] = EMPTY;
            axis[k(dx, dy, dz)] = a;
        }

        /** Spruce or larch: tiers of discs narrowing to a single leaf on top, bare below {@code base}. */
        void conifer(int base, boolean sparse, long hash) {
            int top = h - 1;
            base = Math.min(base, top - 1);
            for (int dy = 0; dy < top; dy++) log(0, dy, 0, Axis.Y);
            for (int dy = base; dy <= top; dy++) {
                int t = top - dy;
                int rr = (int) Math.round(r * (double) t / (top - base));
                if (t % 3 == 2) rr = Math.max(1, rr - 1); // tiers: each third layer steps in
                if (t > 0) rr = Math.max(1, rr);
                for (int dx = -rr; dx <= rr; dx++) {
                    for (int dz = -rr; dz <= rr; dz++) {
                        if (dx * dx + dz * dz > rr * rr + rr) continue;
                        if (sparse && (dx != 0 || dz != 0) && Math.floorMod(ColumnHash.of(hash, dx * 31L + dz, dy), 3L) == 0) continue;
                        leaf(dx, dy, dz);
                    }
                }
                if (t % 3 == 0 && rr >= 4) branches(dy, rr - 3, 0, hash);
            }
        }

        /**
         * Oak, birch, fruit: an ellipsoid crown centred at {@code centre}·h, from {@code bottom}·h to the top, with a
         * ragged surface. {@code spreading}: the trunk ends in the crown and branches fan out at three levels.
         */
        void broadleaf(double centre, double bottom, boolean spreading, long hash) {
            int top = h - 1;
            int cy = Math.min(top - 1, (int) Math.round(centre * top));
            int lowest = Math.max(1, Math.min(cy, (int) Math.round(bottom * h)));
            double up = top - cy + 0.5, down = cy - lowest + 0.5, rad = r + 0.5;
            int trunkTop = spreading ? cy : top - 1;
            for (int dy = 0; dy <= trunkTop; dy++) log(0, dy, 0, Axis.Y);
            for (int dy = lowest; dy <= top; dy++) {
                double v = (dy - cy) / (dy >= cy ? up : down);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        double f = (dx * dx + dz * dz) / (rad * rad) + v * v;
                        if (f > 1 || f > 0.7 && (ColumnHash.of(hash, dx * 31L + dz, dy) & 1) == 0) continue;
                        leaf(dx, dy, dz);
                    }
                }
            }
            if (r < 4) return;
            if (spreading) {
                int third = (int) Math.max(1, Math.round(down / 2));
                for (int level : new int[] {cy - third, cy, cy + (int) (up / 3)}) branches(level, r - 2, 1, hash ^ level);
            } else {
                branches(cy, r - 3, 0, hash);
            }
        }

        /** Branch logs from the trunk in the four main and four diagonal directions, climbing one per {@code rise}·3. */
        void branches(int dy, int length, int rise, long hash) {
            boolean turn = (hash & 2) == 0; // diagonals on this level, or main directions
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                boolean diagonal = d[0] != 0 && d[1] != 0;
                int n = diagonal ? (int) Math.round(length / Math.sqrt(2)) : length;
                if (diagonal && !turn && rise == 0) n = Math.max(0, n - 1);
                for (int i = 1; i <= n; i++) {
                    int y = dy + (rise == 0 ? 0 : i / 3);
                    log(d[0] * i, y, d[1] * i, d[0] != 0 ? Axis.X : Axis.Z);
                }
            }
        }

        /** A low cluster of persistent leaves, no log. */
        void bush(long hash) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) < 2 || !bit(hash, dx, 0, dz)) leaf(dx, 0, dz);
                }
            }
            leaf(0, 1, 0);
        }

        List<Part> parts() {
            int[] dist = new int[cell.length];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            boolean anyLog = false;
            List<Part> out = new ArrayList<>();
            for (int dy = 0; dy < h; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int k = k(dx, dy, dz);
                        if (axis[k] == null) continue;
                        out.add(new Part(dx, dy, dz, true, 0, axis[k]));
                        queue.add(k);
                        anyLog = true;
                    }
                }
            }
            if (!anyLog) {
                for (int k = 0; k < cell.length; k++) if (cell[k] == LEAF) dist[k] = -1;
            }
            while (!queue.isEmpty()) {
                int k = queue.poll(), d = dist[k];
                if (d == LEAF_REACH) continue;
                int dx = k % side - r, dz = k / side % side - r, dy = k / (side * side);
                for (int[] n : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    int x = dx + n[0], y = dy + n[1], z = dz + n[2];
                    if (!inside(x, y, z)) continue;
                    int q = k(x, y, z);
                    if (cell[q] != LEAF || dist[q] != 0) continue;
                    dist[q] = d + 1;
                    queue.add(q);
                }
            }
            for (int dy = 0; dy < h; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int k = k(dx, dy, dz);
                        if (cell[k] == LEAF && dist[k] != 0) out.add(new Part(dx, dy, dz, false, Math.max(0, dist[k]), null));
                    }
                }
            }
            return out;
        }
    }

    private static boolean bit(long hash, int dx, int dy, int dz) {
        return (ColumnHash.of(hash, dx * 31L + dz, dy) & 1) == 0;
    }
}
