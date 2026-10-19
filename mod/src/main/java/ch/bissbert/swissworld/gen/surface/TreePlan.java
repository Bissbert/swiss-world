package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.SwdCell;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which trees stand where, what their spot is like, and their shapes. Pure, see {@link ColumnGrid}.
 * <p>A cell with a {@code TTOP} table (swd-format §6a, via {@link TreeData}) gets exactly its real trees, with their
 * heights and crown radii. Other cells keep the statistical placement: a jittered {@value #GRID} m grid over LV95 (one
 * candidate per grid square, offset and kept by {@link ColumnHash} of the square), so positions depend on world position
 * only; the keep probability depends on SURF, and heights are drawn from realistic ranges (forest
 * {@value #FOREST_MIN}..{@value #FOREST_MAX} m, elsewhere {@value #OPEN_MIN}..{@value #OPEN_MAX} m, lower towards the
 * tree line). Forests have gaps ({@value #PATCH} m patches) with young trees. Orchards are a fixed {@value #ORCHARD} m
 * lattice. Species follow the data or SURF and the altitude band: broadleaf below {@value #BROADLEAF_TOP} m (willows by
 * the water), mixed up to {@value #MIXED_TOP} m, spruce up to {@value #SPRUCE_TOP} m, larch and mountain pine up to
 * {@value #TREE_LINE} m.
 * <p>Each tree gets a {@link Site}: the free room of its crown in 8 directions (shared out with every neighbour within
 * {@value #NEAR} m by size, so neighbouring crowns meet at their tips with a gap, and stopped by buildings), slope,
 * altitude and the solid ground under its crown. {@link TreeGrower} grows the tree for it.
 * <p>Trees cross chunk borders: every chunk enumerates the trees whose crown can reach it and places only the blocks
 * inside itself, so neighbours finish each other's trees from the same data. Whether a tree stands is decided from the
 * chunk's grid (no crown is wider than {@link #REACH}, inside the margin it samples; a statistical candidate also checks
 * {@value #CLEAR} columns around its trunk, so its crown stays within {@link #STAT_REACH}). Its site comes from the
 * {@link ColumnGrid#home home view} of its own cell, which every chunk sees alike: neighbours and ground across a cell
 * border are guessed from the home cell's data there.
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
    /** Neighbours this close (m) share the room with a tree. */
    static final int NEAR = 12;
    /** Gap (m) left between two crowns where they meet, for light. */
    static final double GAP = 0.5;
    /** Free height above a road or rail deck under an overhanging crown. */
    static final int OVERLAY_CLEARANCE = 5;
    static final double BROADLEAF_TOP = 1000, MIXED_TOP = 1500, SPRUCE_TOP = 2000, TREE_LINE = 2300;
    /** Mountain pine (krummholz) grows from here on sparse ground, among the larches from {@value #PINE_LARCH} m. */
    static final double PINE_LOW = 1700, PINE_LARCH = 2050, WILLOW_TOP = 1200;
    static final int FOREST_MIN = 18, FOREST_MAX = 35, OPEN_MIN = 8, OPEN_MAX = 20;
    /** Forest gaps: one in about {@code 1 / GAP_SHARE} patches of {@value #PATCH} m holds only young trees. */
    static final int PATCH = 24;
    static final double GAP_SHARE = 0.12, YOUNG_KEEP = 0.35;
    /** Leaves further than this from a log (vanilla leaf distance) would decay. */
    static final int LEAF_REACH = 6;
    private static final int SALT_X = 11, SALT_Z = 12, SALT_KEEP = 13, SALT_SPECIES = 14, SALT_SHAPE = 15, SALT_HEIGHT = 16,
            SALT_GAP = 17, SALT_WILLOW = 18, SALT_THIN = 19;

    public enum Species { OAK, BIRCH, SPRUCE, LARCH, BUSH, FRUIT, PINE, WILLOW }

    public enum Axis { X, Y, Z }

    /**
     * A tree standing on column (x, z), {@code height} blocks tall, its crown at most {@code radius} wide; {@code hash}
     * drives its shape, {@code site} what its spot is like.
     */
    public record Tree(int x, int z, Species species, int height, int radius, long hash, Site site) {}

    /**
     * What a tree's spot is like. {@code room}: how far its crown may reach in the 8 directions (east, south-east,
     * south, ... with x east and z south), out of {@code free} with nothing around. Terrain {@code metres} and
     * gradient (m per m, positive: rising to the east / south). {@code floor}: per column within {@link #REACH} of the
     * trunk, the lowest dy a block of the tree may take there (above the ground, banks, water, the clearance over a
     * road; {@link Integer#MAX_VALUE} on a building); null clips nothing.
     */
    public record Site(float[] room, float free, double metres, double slopeX, double slopeZ, int[] floor) {
        static final int SIDE = 2 * REACH + 1;

        /** Flat open ground with {@code radius} of room in every direction, nothing to clip. */
        public static Site open(int radius) {
            float[] room = new float[8];
            Arrays.fill(room, radius);
            return new Site(room, radius, 0, 0, 0, null);
        }

        public int floor(int dx, int dz) {
            return floor == null ? 0 : floor[(dz + REACH) * SIDE + dx + REACH];
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Site s && Arrays.equals(room, s.room) && free == s.free && metres == s.metres
                    && slopeX == s.slopeX && slopeZ == s.slopeZ && Arrays.equals(floor, s.floor);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(room) * 31 + Arrays.hashCode(floor);
        }

        @Override
        public String toString() {
            return "Site" + Arrays.toString(room) + " free " + free + " at " + Math.round(metres) + " m";
        }
    }

    /** A tree before its site is known: {@code free} is its crown radius with room all round. */
    private record Seed(int x, int z, Species species, int height, double free, long hash) {
        double weight() {
            return free * Math.sqrt(height);
        }
    }

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

    /** Trees whose crown can reach the columns [minX..maxX] x [minZ..maxZ], in a fixed order, each with its site. */
    public static List<Tree> trees(ColumnGrid g, TreeData data, int minX, int minZ, int maxX, int maxZ) {
        List<Seed> seeds = seeds(g, data, minX, minZ, maxX, maxZ, REACH, STAT_REACH);
        Map<ColumnGrid, List<Seed>> pools = new IdentityHashMap<>();
        List<Tree> out = new ArrayList<>(seeds.size());
        for (Seed s : seeds) {
            ColumnGrid v = g.home(s.x(), s.z());
            // the pool of possible neighbours: every tree the home view sees within NEAR of any tree here
            List<Seed> pool = pools.computeIfAbsent(v,
                    k -> seeds(k, data, minX, minZ, maxX, maxZ, REACH + NEAR, REACH + NEAR));
            Site site = site(v, s, pool);
            Species sp = wet(v, s) ? Species.WILLOW : s.species();
            int radius = 1;
            for (float f : site.room()) radius = Math.max(radius, (int) Math.ceil(f));
            out.add(new Tree(s.x(), s.z(), sp, s.height(), Math.min(REACH, radius), s.hash(), site));
        }
        return out;
    }

    /**
     * Trees of view v standing within {@code realPad} (real) or {@code statPad} (statistical, orchards) of the area,
     * sorted by (x, z).
     */
    private static List<Seed> seeds(ColumnGrid v, TreeData data, int minX, int minZ, int maxX, int maxZ, int realPad, int statPad) {
        List<Seed> out = new ArrayList<>();
        real(v, data, minX - realPad, minZ - realPad, maxX + realPad, maxZ + realPad, out);
        int ax = minX - statPad, az = minZ - statPad, bx = maxX + statPad, bz = maxZ + statPad;
        // east grows with x, north shrinks with z
        long e0 = Math.floorDiv(v.east(ax), GRID), e1 = Math.floorDiv(v.east(bx), GRID);
        long n0 = Math.floorDiv(v.north(bz), GRID), n1 = Math.floorDiv(v.north(az), GRID);
        for (long i = e0; i <= e1; i++) {
            for (long j = n0; j <= n1; j++) {
                int x = v.xAt(i * GRID + ColumnHash.below(i, j, SALT_X, GRID));
                int z = v.zAt(j * GRID + ColumnHash.below(i, j, SALT_Z, GRID));
                if (x < ax || x > bx || z < az || z > bz || hasTable(v, data, x, z) || !allowed(v, x, z)) continue;
                int surf = v.surf(x, z);
                long e = v.east(x), n = v.north(z);
                boolean young = forest(surf) && ColumnHash.unit(Math.floorDiv(e, PATCH), Math.floorDiv(n, PATCH), SALT_GAP) < GAP_SHARE;
                if (ColumnHash.unit(i, j, SALT_KEEP) >= (young ? YOUNG_KEEP : density(surf))) continue;
                double m = v.metres(x, z);
                Species s = species(surf, m, ColumnHash.unit(e, n, SALT_SPECIES));
                if (s == null || forest(surf) && !young && ColumnHash.unit(i, j, SALT_THIN) >= stand(s)) continue;
                double r = ColumnHash.unit(e, n, SALT_HEIGHT);
                int h = young && s != Species.PINE ? 3 + (int) (r * 5) : height(s, surf, m, r);
                out.add(new Seed(x, z, s, h, Math.min(STAT_REACH, 1.3 * crown(s, h)), ColumnHash.of(e, n, SALT_SHAPE)));
            }
        }
        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                long e = v.east(x), n = v.north(z);
                if (Math.floorMod(e, ORCHARD) != 0 || Math.floorMod(n, ORCHARD) != 0) continue;
                if (v.surf(x, z) != 12 || hasTable(v, data, x, z) || !allowed(v, x, z) || v.metres(x, z) > MIXED_TOP) continue;
                int h = height(Species.FRUIT, 12, v.metres(x, z), ColumnHash.unit(e, n, SALT_HEIGHT));
                out.add(new Seed(x, z, Species.FRUIT, h, Math.min(STAT_REACH, 1.3 * crown(Species.FRUIT, h)), ColumnHash.of(e, n, SALT_SHAPE)));
            }
        }
        out.sort(Comparator.comparingInt(Seed::x).thenComparingInt(Seed::z));
        return out;
    }

    /**
     * Share of the forest grid a species fills in a closed stand: about 350 spruces, 280 larches, 220 beeches or oaks,
     * 300 birches a hectare, so mature crowns have room.
     */
    static double stand(Species s) {
        return switch (s) {
            case SPRUCE -> 0.62;
            case LARCH -> 0.5;
            case OAK, WILLOW -> 0.4;
            case BIRCH -> 0.55;
            default -> 1;
        };
    }

    private static boolean forest(int surf) {
        return surf == 1 || surf == 2 || surf == 3;
    }

    /**
     * The site of seed s in its home view v: its room shared with the neighbours from {@code pool} (each gets the part
     * of the distance its size claims, less {@value #GAP}; a neighbour limits the directions within about 68° of it,
     * as a plane between the two trees would) and cut short before a building; slope; the floor under the crown.
     */
    private static Site site(ColumnGrid v, Seed s, List<Seed> pool) {
        float[] room = new float[8];
        Arrays.fill(room, (float) s.free());
        double w = s.weight();
        for (Seed n : pool) {
            int dx = n.x() - s.x(), dz = n.z() - s.z();
            if (dx == 0 && dz == 0 || dx * dx + dz * dz > NEAR * NEAR) continue;
            double d = Math.sqrt(dx * dx + dz * dz), phi = Math.atan2(dz, dx);
            double e = d * w / (w + n.weight()) - GAP;
            for (int i = 0; i < 8; i++) {
                double c = Math.cos(i * Math.PI / 4 - phi);
                if (c > 0.38) room[i] = (float) Math.min(room[i], Math.max(1, e / c));
            }
        }
        for (int i = 0; i < 8; i++) {
            double c = Math.cos(i * Math.PI / 4), sn = Math.sin(i * Math.PI / 4);
            for (int step = 1; step <= Math.ceil(room[i]); step++) {
                if (v.hasBuilding(s.x() + (int) Math.round(step * c), s.z() + (int) Math.round(step * sn))) {
                    room[i] = (float) Math.max(1, Math.min(room[i], step - 1.5));
                    break;
                }
            }
        }
        int[] floor = new int[Site.SIDE * Site.SIDE];
        int ground = v.ground(s.x(), s.z());
        for (int dz = -REACH; dz <= REACH; dz++) {
            for (int dx = -REACH; dx <= REACH; dx++) {
                int x = s.x() + dx, z = s.z() + dz;
                int f;
                if (v.hasBuilding(x, z)) {
                    f = Integer.MAX_VALUE;
                } else {
                    int top = Math.max(v.top(x, z), v.source(x, z));
                    if (surfaceOverlay(v, x, z)) top = Math.max(top, v.deck(x, z) + OVERLAY_CLEARANCE);
                    f = top - ground;
                }
                floor[(dz + REACH) * Site.SIDE + dx + REACH] = f;
            }
        }
        double sx = (v.metres(s.x() + 2, s.z()) - v.metres(s.x() - 2, s.z())) / 4;
        double sz = (v.metres(s.x(), s.z() + 2) - v.metres(s.x(), s.z() - 2)) / 4;
        return new Site(room, (float) s.free(), v.metres(s.x(), s.z()), sx, sz, floor);
    }

    /** A broadleaf tree within 4 m of water below {@value #WILLOW_TOP} m is (mostly) a willow. */
    private static boolean wet(ColumnGrid v, Seed s) {
        if (s.species() != Species.OAK && s.species() != Species.BIRCH || s.height() < 5) return false;
        if (v.metres(s.x(), s.z()) >= WILLOW_TOP || ColumnHash.unit(v.east(s.x()), v.north(s.z()), SALT_WILLOW) >= 0.7) return false;
        for (int i = -4; i <= 4; i++) {
            for (int j = -4; j <= 4; j++) {
                if (v.source(s.x() + i, s.z() + j) != ColumnGrid.NONE) return true;
            }
        }
        return false;
    }

    /** The column lies in a cell with a {@code TTOP} table, which replaces the statistical placement there. */
    private static boolean hasTable(ColumnGrid g, TreeData data, int x, int z) {
        return data.tops(g.cellE(x), g.cellN(z)) != null;
    }

    /** The {@code TTOP} trees of every cell under [ax..bx] x [az..bz] (at most 2 x 2 cells: the area is < 1 km). */
    private static void real(ColumnGrid g, TreeData data, int ax, int az, int bx, int bz, List<Seed> out) {
        long eMin = g.east(ax), eMax = g.east(bx), nMin = g.north(bz), nMax = g.north(az);
        Set<Long> done = new HashSet<>();
        for (int x : new int[] {ax, bx}) {
            for (int z : new int[] {az, bz}) {
                int eKm = g.cellE(x), nKm = g.cellN(z);
                if (!done.add(((long) eKm << 32) ^ (nKm & 0xFFFFFFFFL))) continue;
                TreeTops tops = data.tops(eKm, nKm);
                if (tops == null) continue;
                long e0 = eKm * 1000L, n0 = nKm * 1000L;
                for (SwdCell.Tree t : tops.within((int) (eMin - e0), (int) (999 - (nMax - n0)), (int) (eMax - e0), (int) (999 - (nMin - n0)))) {
                    long te = e0 + t.dx(), tn = n0 + 999 - t.dz();
                    int x0 = g.xAt(te), z0 = g.zAt(tn);
                    if (!standing(g, x0, z0)) continue;
                    double r = ColumnHash.unit(te, tn, SALT_SPECIES);
                    Species s = species(t, g.surf(x0, z0), g.metres(x0, z0), r);
                    int radius = t.crownDm() > 0 ? (int) Math.round(t.crownDm() / 10.0) : crown(s, t.heightM());
                    double free = t.crownDm() > 0 ? radius : 1.3 * radius;
                    out.add(new Seed(x0, z0, s, t.heightM(), Math.max(1, Math.min(REACH, free)), ColumnHash.of(te, tn, SALT_SHAPE)));
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
    static Species species(SwdCell.Tree t, int surf, double metres, double r) {
        if (t.heightM() < 4) return metres >= PINE_LOW ? Species.PINE : Species.BUSH;
        Species broadleaf = r < 0.7 ? Species.OAK : Species.BIRCH;
        return switch (t.kind()) {
            case SwdCell.Tree.CONIFER -> metres >= SPRUCE_TOP ? Species.LARCH : Species.SPRUCE;
            case SwdCell.Tree.LARCH -> Species.LARCH;
            case SwdCell.Tree.BROADLEAF -> broadleaf;
            default -> {
                Species s = species(surf, metres, r);
                yield s == null || s == Species.PINE ? Species.LARCH : s == Species.BUSH ? broadleaf : s;
            }
        };
    }

    /** Height in blocks of a statistical tree; {@code r} is uniform in [0, 1). Trees shrink by up to 45 % towards the tree line. */
    static int height(Species s, int surf, double metres, double r) {
        int lo, hi;
        if (s == Species.BUSH) return 2;
        if (s == Species.PINE) return 2 + (int) (r * 3);
        if (s == Species.FRUIT) {
            lo = 4;
            hi = 7;
        } else if (forest(surf) && metres < SPRUCE_TOP) {
            lo = FOREST_MIN;
            hi = FOREST_MAX;
        } else {
            lo = OPEN_MIN;
            hi = OPEN_MAX;
        }
        int h = lo + (int) (r * (hi - lo + 1));
        return (int) Math.max(4, Math.round(h * (1 - 0.45 * alpine(metres))));
    }

    /** 0 below 1800 m, rising to 1 at 2200 m: how close to the tree line. */
    static double alpine(double metres) {
        return Math.max(0, Math.min(1, (metres - 1800) / 400));
    }

    /** Crown radius when the data has none: conifers are slim, oaks wide. */
    static int crown(Species s, int height) {
        int r = switch (s) {
            case SPRUCE -> (int) Math.round(1 + 0.12 * height);
            case LARCH, BIRCH -> (int) Math.round(1 + 0.1 * height);
            case OAK -> (int) Math.round(0.22 * height);
            case WILLOW -> (int) Math.round(0.25 * height);
            case FRUIT -> height / 2;
            case PINE -> 2;
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
        if (metres >= SPRUCE_TOP) return metres >= PINE_LARCH && r < 0.5 ? Species.PINE : Species.LARCH;
        if (surf == 14 && metres >= PINE_LOW) return r < 0.5 ? Species.PINE : Species.LARCH;
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
        return TreeGrower.grow(t.species(), t.height(), t.hash(), t.site());
    }

    /** The tree grown on open, flat ground with {@code radius} of room all round (the shape without a site). */
    public static List<Part> shape(Species s, int height, int radius, long hash) {
        return TreeGrower.grow(s, height, hash, Site.open(Math.max(1, Math.min(REACH, radius))));
    }
}
