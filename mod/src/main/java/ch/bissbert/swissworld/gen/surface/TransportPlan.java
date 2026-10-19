package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.surface.OverlayPlan.Rail;
import java.util.Arrays;

/**
 * Roads, rails and tunnels laid out from {@link ColumnGrid} data only, so that two chunks sharing a border agree on
 * every block. Each decision is a pure function of the columns within a fixed distance; there is no iteration.
 * Every look-up stays within {@link #MARGIN} of the chunk (the longest chain, a tunnel wall over a rail, reaches about 18).
 * <ul>
 *   <li>Profile: a weighted mean of the deck heights of the same kind of way within {@link #ROAD_RADIUS} (roads) or
 *       {@link #RAIL_RADIUS} (rails). A mean over a disc keeps a constant grade and removes the cross slope, the
 *       per-column noise of the terrain, and single high or low columns.</li>
 *   <li>Rail track: the rail centre columns, minus one of two touching tracks, minus junction nodes; every rail's
 *       shape joins exactly its track neighbours, and its level is flat through curves (a curve cannot ascend).</li>
 *   <li>Earthworks: dry terrain within {@link #EARTHWORK_REACH} of an open way is cut or filled to the way's level
 *       at the shoulder and one block per column further out.</li>
 *   <li>Tunnels: a bored profile lined with a floor, walls and a ceiling, closed against water and, where the cover
 *       is thin, buried under a block of soil (a covered cut).</li>
 * </ul>
 */
public final class TransportPlan {
    /** Grid margin this plan needs: {@code new ColumnGrid(.., MARGIN)}. */
    public static final int MARGIN = 24;
    static final int ROAD_RADIUS = 5, RAIL_RADIUS = 4;
    /** How far a culvert or a water-side constraint ramps the way up: one half block (road) or block (rail) per column. */
    static final int ROAD_RAMP = 6, RAIL_RAMP = 4;
    /** Clear height above a tunnel floor. */
    static final int RAIL_TUNNEL_HEIGHT = 6, ROAD_TUNNEL_HEIGHT = 5;
    static final int EARTHWORK_REACH = 3;
    /** How far a curve looks along its track for the level it keeps. */
    static final int CHAIN_WALK = 3;
    static final int NONE = ColumnGrid.NONE;
    private static final int UNSET = Integer.MAX_VALUE;

    private final ColumnGrid g;
    private final int[] roadHalf, railBase, railNode, railLevel, floor, ceil, wet, gap, joins, flipped;
    private final int[][] thinned;

    public TransportPlan(ColumnGrid g) {
        if (g.margin() < MARGIN) throw new IllegalArgumentException("grid margin " + g.margin() + " < " + MARGIN);
        this.g = g;
        int n = g.cells();
        roadHalf = filled(n);
        railBase = filled(n);
        railNode = filled(n);
        railLevel = filled(n);
        floor = filled(n);
        ceil = filled(n);
        wet = filled(n);
        gap = filled(n);
        joins = filled(n);
        flipped = filled(n);
        thinned = new int[THIN.length][];
        for (int i = 0; i < THIN.length; i++) thinned[i] = filled(n);
    }

    private static int[] filled(int n) {
        int[] a = new int[n];
        Arrays.fill(a, UNSET);
        return a;
    }

    public ColumnGrid grid() {
        return g;
    }

    // ---------------------------------------------------------------- kinds of way

    /** Road-like overlay that gets a smoothed deck: roads, tracks, paths, platforms, runways, parking. */
    public static boolean roadClass(int cls) {
        return cls >= 1 && cls <= 6 || cls == 10 || cls == 12 || cls == 14;
    }

    /** Surface materials that have a slab: half steps are possible. */
    public static boolean slabClass(int cls) {
        return cls >= 1 && cls <= 4 || cls == 10 || cls == 12 || cls == 14;
    }

    public boolean road(int x, int z) {
        return g.present(x, z) && roadClass(g.overlay(x, z)) && !drowned(x, z);
    }

    /**
     * A way drawn across a lake, reservoir or pond without bridge, tunnel or height of its own: it would drown or need a
     * causeway, so it is left out. Rivers and streams are crossed on a culvert instead.
     */
    private boolean drowned(int x, int z) {
        if (g.source(x, z) == NONE || g.flags(x, z) != 0 && (g.flag(x, z, ColumnGrid.BRIDGE) || g.flag(x, z, ColumnGrid.TUNNEL))) {
            return false;
        }
        int kind = g.waterKind(x, z);
        return !g.elevated(x, z) && kind != 2 && kind != 3 && kind != WaterPlan.WATERFALL;
    }

    /** Rail bed (class 8, rack 9), or a rail centre under a road bridge. */
    public boolean railWay(int x, int z) {
        if (!g.present(x, z)) return false;
        int cls = g.overlay(x, z);
        if (drowned(x, z)) return false;
        return cls == OverlayPlan.RAIL_BED || cls == OverlayPlan.RACK_BED || OverlayPlan.railUnderBridge(g, x, z);
    }

    public boolean way(int x, int z) {
        return road(x, z) || railWay(x, z);
    }

    public boolean tunnel(int x, int z) {
        return way(x, z) && g.flag(x, z, ColumnGrid.TUNNEL);
    }

    /** A way on the ground: not a bridge, not in a tunnel. Its terrain is cut or filled to its deck. */
    public boolean open(int x, int z) {
        return way(x, z) && !g.flag(x, z, ColumnGrid.BRIDGE) && !g.flag(x, z, ColumnGrid.TUNNEL) && !absorbed(x, z);
    }

    /**
     * A way without the tunnel flag that crosses a bored profile: it runs over it ({@link #over}), or it would cut
     * through the bore, and then the bore wins: the column is part of the tunnel and the way gives way to it.
     */
    public boolean absorbed(int x, int z) {
        return way(x, z) && !g.flag(x, z, ColumnGrid.TUNNEL) && tunnelFloor(x, z) != NONE && !over(x, z);
    }

    /** A way without the tunnel flag whose deck lies above the lining of a bore under it: both are built, apart. */
    public boolean over(int x, int z) {
        if (!way(x, z) || g.flag(x, z, ColumnGrid.TUNNEL)) return false;
        int hi = tunnelHighCeiling(x, z);
        return hi != NONE && deck(x, z) > hi;
    }

    /** Water the mod places in this column (WaterPlan's level), or NONE. */
    private int water(int x, int z) {
        return WaterPlan.sourceLevel(g, x, z);
    }

    /** Highest water block of this column, falling water included, or NONE. */
    int wetTop(int x, int z) {
        int k = g.index(x, z);
        if (wet[k] != UNSET) return wet[k];
        WaterPlan.Fill f = WaterPlan.fill(g, x, z);
        return wet[k] = f == null ? NONE : Math.max(f.sourceTop(), f.fallTop());
    }

    /** Highest water block of the four neighbours, or NONE: a deck or bank beside it must reach it. */
    int neighbourWet(int x, int z) {
        int u = Math.max(wetTop(x + 1, z), wetTop(x - 1, z));
        return Math.max(u, Math.max(wetTop(x, z + 1), wetTop(x, z - 1)));
    }

    // ---------------------------------------------------------------- profiles

    /** Deck height of one column before smoothing, in block units (floor = the deck block). */
    private double sample(int x, int z, boolean rail) {
        double o = g.overlayMetres(x, z);
        boolean underBridge = rail && OverlayPlan.railUnderBridge(g, x, z);
        double v = !Double.isNaN(o) && !underBridge ? SwissCoords.toY(o) : SwissCoords.toY(g.metres(x, z));
        double w = g.waterMetres(x, z);
        boolean structure = g.flag(x, z, ColumnGrid.TUNNEL) || g.flag(x, z, ColumnGrid.BRIDGE) && !underBridge;
        // a way through water on the ground crosses it on a culvert: its deck lies above the water
        if (!Double.isNaN(w) && !structure) v = Math.max(v, SwissCoords.toY(w) + (rail ? 2 : 1));
        return v;
    }

    /**
     * Two way columns of different ways where one is bored and the other is not: a way crossing over a tunnel or
     * running beside it. Their profiles stay apart, however close they run. The same way leading into its portal
     * (same class) still blends into the tunnel's deck.
     */
    private boolean crossing(int x, int z, int nx, int nz) {
        return g.flag(nx, nz, ColumnGrid.TUNNEL) != g.flag(x, z, ColumnGrid.TUNNEL) && g.overlay(nx, nz) != g.overlay(x, z);
    }

    private double profile(int x, int z, boolean rail) {
        int r = rail ? RAIL_RADIUS : ROAD_RADIUS;
        int r2 = r * r, full = (r + 1) * (r + 1);
        double sw = 0, sv = 0;
        for (int j = -r; j <= r; j++) {
            for (int i = -r; i <= r; i++) {
                int d2 = i * i + j * j;
                if (d2 > r2) continue;
                int nx = x + i, nz = z + j;
                if (rail ? !railWay(nx, nz) : !road(nx, nz)) continue;
                if (crossing(x, z, nx, nz)) continue;
                int w = full - d2;
                sw += w;
                sv += w * sample(nx, nz, rail);
            }
        }
        return sv / sw;
    }

    /** Road deck in half blocks: {@code 2 * deck + 1} when a bottom slab lies on the deck. */
    private int roadHalf(int x, int z) {
        int k = g.index(x, z);
        if (roadHalf[k] != UNSET) return roadHalf[k];
        double v = profile(x, z, false);
        int cls = g.overlay(x, z);
        int half = slabClass(cls) ? (int) Math.floor(2 * v) : 2 * (int) Math.floor(v);
        // ramp up to the water constraints of road columns nearby, one half block per column
        int need = NONE;
        for (int j = -ROAD_RAMP; j <= ROAD_RAMP; j++) {
            for (int i = -ROAD_RAMP; i <= ROAD_RAMP; i++) {
                if (!road(x + i, z + j) || crossing(x, z, x + i, z + j)) continue;
                int n = roadNeed(x + i, z + j);
                if (n != NONE) need = Math.max(need, n - Math.max(Math.abs(i), Math.abs(j)));
            }
        }
        if (need != NONE) half = Math.max(half, slabClass(cls) ? need : 2 * Math.floorDiv(need + 1, 2));
        return roadHalf[k] = half;
    }

    /** Lowest deck in half blocks that this road column's water allows, or NONE. */
    private int roadNeed(int x, int z) {
        if (g.flag(x, z, ColumnGrid.TUNNEL)) return NONE;
        int s = wetTop(x, z);
        if (s != NONE) return 2 * (s + (g.flag(x, z, ColumnGrid.BRIDGE) ? 2 : 1)); // beam or culvert roof above it
        int ns = neighbourWet(x, z);
        return ns == NONE ? NONE : 2 * ns; // holds the water beside it
    }

    /** Lowest bed this rail column's water allows, or NONE. */
    private int railNeed(int x, int z) {
        if (g.flag(x, z, ColumnGrid.TUNNEL)) return NONE;
        int s = wetTop(x, z);
        if (s != NONE) return s + 2; // bridge beam or culvert roof over the water, ballast on it
        boolean bridge = g.flag(x, z, ColumnGrid.BRIDGE) && !OverlayPlan.railUnderBridge(g, x, z);
        int ns = bridge ? NONE : neighbourWet(x, z);
        return ns;
    }

    /** The road's deck block: OELV or terrain, smoothed along the road. */
    public int roadDeck(int x, int z) {
        return Math.floorDiv(roadHalf(x, z), 2);
    }

    /** A bottom slab of the road's material lies on {@link #roadDeck}. */
    public boolean roadSlab(int x, int z) {
        return (roadHalf(x, z) & 1) != 0;
    }

    /** Smoothed rail bed level before the track's own adjustments. */
    public int railBase(int x, int z) {
        int k = g.index(x, z);
        if (railBase[k] != UNSET) return railBase[k];
        if (gapKind(x, z) == GAP_RAIL) return railBase[k] = boreDeck(x, z);
        if (cut(x, z)) {
            int b = Integer.MAX_VALUE;
            for (int[] d : EIGHT) if (railTunnel(x + d[0], z + d[1])) b = Math.min(b, railBase(x + d[0], z + d[1]));
            return railBase[k] = b;
        }
        int b = (int) Math.floor(profile(x, z, true));
        for (int jj = -RAIL_RAMP; jj <= RAIL_RAMP; jj++) {
            for (int ii = -RAIL_RAMP; ii <= RAIL_RAMP; ii++) {
                if (!railWay(x + ii, z + jj) || crossing(x, z, x + ii, z + jj)) continue;
                int n = railNeed(x + ii, z + jj);
                if (n != NONE) b = Math.max(b, n - Math.max(Math.abs(ii), Math.abs(jj)));
            }
        }
        return railBase[k] = b;
    }

    // ---------------------------------------------------------------- rail track

    private boolean c0(int x, int z) {
        return OverlayPlan.railCentre(g, x, z) || gapKind(x, z) == GAP_RAIL;
    }

    /** Thinning passes: each drops the centres with none on this side (north, west, south, east, ...). */
    private static final int[] THIN = {0, 3, 1, 2, 0, 3};

    /** A rail centre, or a gap of one column inside a rail way with centres on all four sides (two tracks touching). */
    private boolean c00(int x, int z) {
        if (c0(x, z)) return true;
        for (int[] d : DIRS) if (!c0(x + d[0], z + d[1])) return false;
        return railWay(x, z);
    }

    /**
     * Two tracks drawn side by side (two centre lines touching, or one drawn two wide) thinned to one: pass by pass,
     * the centres with no centre on the pass's side are dropped where that keeps their neighbours joined and does not
     * shorten a track (a simple point of the 4-connected centres). Each pass looks one column further around.
     */
    private boolean thin(int x, int z, int pass) {
        if (pass == 0) return c00(x, z);
        int k = g.index(x, z);
        int[] cache = thinned[pass - 1];
        if (cache[k] != UNSET) return cache[k] == 1;
        boolean keep = thin(x, z, pass - 1);
        if (keep) {
            int[] d = DIRS[THIN[pass - 1]];
            if (!thin(x + d[0], z + d[1], pass - 1) && simple(x, z, pass - 1)) keep = false;
        }
        cache[k] = keep ? 1 : 0;
        return keep;
    }

    /** Ring of the eight neighbours, clockwise from north. */
    private static final int[][] RING = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};

    /**
     * Dropping (x, z) from the centres of {@code pass} keeps its centre neighbours (at least two) joined among its eight
     * neighbours: one run of set cells around the ring that holds all its set side neighbours.
     */
    private boolean simple(int x, int z, int pass) {
        boolean[] on = new boolean[8];
        int sides = 0;
        for (int i = 0; i < 8; i++) {
            on[i] = thin(x + RING[i][0], z + RING[i][1], pass);
            if (on[i] && i % 2 == 0) sides++;
        }
        if (sides < 2) return false;
        // corners count only between two set sides (4-connected); runs of set sides around the ring
        int runs = 0;
        for (int i = 0; i < 8; i += 2) {
            boolean a = on[i], next = on[(i + 2) % 8] && on[i + 1];
            if (a && !next) runs++;
        }
        return runs == 1;
    }

    private boolean t6(int x, int z) {
        return thin(x, z, THIN.length);
    }

    private int thinDegree(int x, int z) {
        int n = 0;
        for (int[] d : DIRS) if (t6(x + d[0], z + d[1])) n++;
        return n;
    }

    private static final int[][] EIGHT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private boolean railTunnel(int x, int z) {
        return railWay(x, z) && g.flag(x, z, ColumnGrid.TUNNEL);
    }

    /**
     * A column another way cut out of a rail tunnel where the two cross at an angle (the sidecar burns the way last):
     * not rail way itself, with at least two rail tunnel columns among its eight neighbours. A track's ends on both
     * sides of the cut join through it ({@link #bridge}).
     */
    private boolean cut(int x, int z) {
        if (railWay(x, z) || g.overlay(x, z) == 0 || g.flag(x, z, ColumnGrid.TUNNEL)) return false;
        int n = 0;
        for (int[] d : EIGHT) if (railTunnel(x + d[0], z + d[1])) n++;
        return n >= 2;
    }

    /** Longest join from a track's end to a track beside it (steps). */
    static final int BRIDGE = 4;

    /**
     * Where ways merge, the data sometimes ends one track's centre line a few columns short of the track it joins. Such
     * an end gets a short join through the rail way to the nearest track at most {@link #BRIDGE} steps away (not back
     * along its own track): the columns of that join, end excluded, or null.
     */
    private int[][] bridge(int ex, int ez) {
        if (!t6(ex, ez) || thinDegree(ex, ez) != 1) return null;
        int fx = 0, fz = 0; // towards the track it comes from
        for (int[] d : DIRS) {
            if (t6(ex + d[0], ez + d[1])) {
                fx = d[0];
                fz = d[1];
            }
        }
        int bx = NONE, bz = NONE, best = Integer.MAX_VALUE;
        for (int dz = -BRIDGE; dz <= BRIDGE; dz++) {
            for (int dx = -BRIDGE; dx <= BRIDGE; dx++) {
                int m = Math.abs(dx) + Math.abs(dz);
                if (m < 2 || m > BRIDGE) continue;
                int ahead = -(dx * fx + dz * fz);
                if (ahead < 0) continue; // behind the end: its own track
                int tx = ex + dx, tz = ez + dz;
                if (!t6(tx, tz)) continue;
                int[][] p;
                if (thinDegree(tx, tz) == 2) {
                    p = path(ex, ez, tx, tz);
                } else if (thinDegree(tx, tz) == 1) {
                    // the other end of a track a crossing way cut: one join, the same from both ends, through the cut
                    p = ex < tx || ex == tx && ez < tz ? path(ex, ez, tx, tz) : path(tx, tz, ex, ez);
                    boolean through = false;
                    if (p != null) for (int[] q : p) through |= cut(q[0], q[1]);
                    if (!through) continue;
                } else {
                    continue;
                }
                int score = m * 16 - ahead;
                if (score < best && p != null) {
                    best = score;
                    bx = tx;
                    bz = tz;
                }
            }
        }
        if (bx == NONE) return null;
        if (thinDegree(bx, bz) == 1 && !(ex < bx || ex == bx && ez < bz)) return path(bx, bz, ex, ez);
        return path(ex, ez, bx, bz);
    }

    /** A 4-connected line from (ex, ez) to (tx, tz) over free rail way columns, both ends excluded, or null. */
    private int[][] path(int ex, int ez, int tx, int tz) {
        int n = Math.abs(tx - ex) + Math.abs(tz - ez) - 1;
        int sx = Integer.signum(tx - ex), sz = Integer.signum(tz - ez);
        int[][] p = new int[n][];
        int cx = ex, cz = ez;
        for (int i = 0; i < n; i++) {
            boolean xs;
            if (cx == tx) xs = false;
            else if (cz == tz) xs = true;
            else {
                // step that stays nearest the straight line
                long ax = Math.abs((long) (cx + sx - ex) * (tz - ez) - (long) (cz - ez) * (tx - ex));
                long az = Math.abs((long) (cx - ex) * (tz - ez) - (long) (cz + sz - ez) * (tx - ex));
                xs = ax <= az;
            }
            if (xs) cx += sx;
            else cz += sz;
            if (!railWay(cx, cz) && !cut(cx, cz) || t6(cx, cz)) return null;
            p[i] = new int[] {cx, cz};
        }
        // no other track beside the join
        for (int[] q : p) {
            for (int[] d : DIRS) {
                int ax = q[0] + d[0], az = q[1] + d[1];
                if (t6(ax, az) && !(ax == ex && az == ez) && !(ax == tx && az == tz)) return null;
            }
        }
        return p;
    }

    /** On the join of a track's end within {@link #BRIDGE} (see {@link #bridge}). */
    private boolean bridged(int x, int z) {
        if (t6(x, z) || !railWay(x, z) && !cut(x, z)) return false;
        int k = g.index(x, z);
        if (joins[k] != UNSET) return joins[k] == 1;
        boolean on = false;
        for (int dz = -BRIDGE + 1; dz <= BRIDGE - 1 && !on; dz++) {
            for (int dx = -BRIDGE + 1; dx <= BRIDGE - 1 && !on; dx++) {
                if (Math.abs(dx) + Math.abs(dz) >= BRIDGE) continue;
                int[][] p = bridge(x + dx, z + dz);
                if (p == null) continue;
                for (int[] q : p) on |= q[0] == x && q[1] == z;
            }
        }
        joins[k] = on ? 1 : 0;
        return on;
    }

    /** The thinned centres plus the joins: the track graph. */
    private boolean c1(int x, int z) {
        return t6(x, z) || bridged(x, z);
    }

    private int degree(int x, int z) {
        int n = 0;
        for (int[] d : DIRS) if (c1(x + d[0], z + d[1])) n++;
        return n;
    }

    private boolean c2(int x, int z) {
        if (!c1(x, z)) return false;
        int deg = degree(x, z);
        if (deg <= 2) return true;
        return deg == 3;
    }

    /** Shortest track kept: where tracks merge, the thinning leaves short pieces that lead nowhere. */
    static final int MIN_RUN = 4;

    /** A track node: a rail centre on a run of at least {@link #MIN_RUN} nodes, before corners are flipped. */
    private boolean node0(int x, int z) {
        int k = g.index(x, z);
        if (railNode[k] == UNSET) railNode[k] = c2(x, z) && run(x, z) >= MIN_RUN ? 1 : 0;
        return railNode[k] == 1;
    }

    /**
     * A rail block goes here: a track node, with the corners of diagonal staircases flipped where the track changes
     * level (see {@link #flip}).
     */
    public boolean rail(int x, int z) {
        int k = g.index(x, z);
        if (flipped[k] == UNSET) {
            boolean on = node0(x, z) ? !flip(x, z) : false;
            for (int[] d : DIAG) on |= flip(x + d[0], z + d[1]) && !node0(x, z) && flipTarget(x + d[0], z + d[1], x, z);
            flipped[k] = on ? 1 : 0;
        }
        return flipped[k] == 1;
    }

    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Bit mask of track neighbours before flips. */
    private int links0(int x, int z) {
        int m = 0;
        for (int d = 0; d < 4; d++) if (node0(x + DIRS[d][0], z + DIRS[d][1])) m |= 1 << d;
        return m;
    }

    /**
     * A corner of a diagonal staircase (a curve between two curves) where the bed rises from the neighbour it shares a
     * level with to the other: no rail can climb between curves, so the corner moves to the other side of the step,
     * which turns both neighbours into straight rails, one of them ascending. The column it moves to must be free rail
     * way, and no other such corner lies within two nodes.
     */
    private boolean flip(int x, int z) {
        if (!flipCorner(x, z)) return false;
        int px = x, pz = z;
        int[] next = new int[2];
        for (int d = 0; d < 4; d++) {
            if ((links0(x, z) & 1 << d) == 0) continue;
            int cx = x + DIRS[d][0], cz = z + DIRS[d][1];
            if (flipCorner(cx, cz)) return false;
            if (other(cx, cz, px, pz, next) && flipCorner(next[0], next[1])) return false;
        }
        return true;
    }

    /** The track neighbour of (x, z) other than (px, pz), into {@code out}; false if none. */
    private boolean other(int x, int z, int px, int pz, int[] out) {
        int m = links0(x, z);
        if (Integer.bitCount(m) != 2) return false;
        for (int e = 0; e < 4; e++) {
            int qx = x + DIRS[e][0], qz = z + DIRS[e][1];
            if ((m & 1 << e) != 0 && (qx != px || qz != pz)) {
                out[0] = qx;
                out[1] = qz;
                return true;
            }
        }
        return false;
    }

    /** {@link #flip}'s test without the spacing rule. */
    private boolean flipCorner(int x, int z) {
        if (!node0(x, z)) return false;
        int m = links0(x, z);
        if (!curve(m)) return false;
        int ax = NONE, az = NONE, bx = NONE, bz = NONE;
        for (int d = 0; d < 4; d++) {
            if ((m & 1 << d) == 0) continue;
            int cx = x + DIRS[d][0], cz = z + DIRS[d][1];
            if (!curve(links0(cx, cz))) return false;
            if (ax == NONE) {
                ax = cx;
                az = cz;
            } else {
                bx = cx;
                bz = cz;
            }
        }
        int a = railBase(ax, az), b = railBase(bx, bz), here = railBase(x, z);
        if (Math.abs(a - b) != 1 || here != Math.min(a, b)) return false;
        int fx = ax + bx - x, fz = az + bz - z;
        if (node0(fx, fz) || !railWay(fx, fz)) return false;
        // nothing else beside the column it moves to
        for (int[] d : DIRS) {
            int qx = fx + d[0], qz = fz + d[1];
            if (node0(qx, qz) && !(qx == ax && qz == az) && !(qx == bx && qz == bz)) return false;
        }
        return true;
    }

    /** The corner (x, z), when flipped, moves to (tx, tz). */
    private boolean flipTarget(int x, int z, int tx, int tz) {
        int m = links0(x, z), sx = 0, sz = 0;
        for (int d = 0; d < 4; d++) {
            if ((m & 1 << d) == 0) continue;
            sx += DIRS[d][0];
            sz += DIRS[d][1];
        }
        return x + sx == tx && z + sz == tz;
    }

    /** Nodes in the run of {@code c2} nodes through (x, z), counted up to {@link #MIN_RUN} (each has at most two). */
    private int run(int x, int z) {
        int n = 1;
        for (int d = 0; d < 4 && n < MIN_RUN; d++) {
            int px = x, pz = z, cx = x + DIRS[d][0], cz = z + DIRS[d][1];
            while (n < MIN_RUN && c2(cx, cz)) {
                if (cx == x && cz == z) return MIN_RUN; // a loop
                n++;
                int nx = NONE, nz = NONE;
                for (int[] e : DIRS) {
                    int qx = cx + e[0], qz = cz + e[1];
                    if ((qx != px || qz != pz) && c2(qx, qz)) {
                        nx = qx;
                        nz = qz;
                    }
                }
                if (nx == NONE) break;
                px = cx;
                pz = cz;
                cx = nx;
                cz = nz;
            }
        }
        return n;
    }

    private static final int[][] DIRS = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}}; // N S E W

    /** Bit mask of track neighbours: 1 north, 2 south, 4 east, 8 west. */
    private int links(int x, int z) {
        int m = 0;
        for (int d = 0; d < 4; d++) if (rail(x + DIRS[d][0], z + DIRS[d][1])) m |= 1 << d;
        return m;
    }

    private static final int[] OPP = {1, 0, 3, 2};
    /** How far a switch looks back along its branch for the side the branch comes from. */
    static final int SWITCH_WALK = 6;

    /**
     * The two neighbours a rail joins (bit mask as {@link #links}): its track neighbours, or at a switch (three) the
     * branch and the side of the through track it runs on to, away from where the branch comes from. Vanilla rails
     * have no switch block; a curve with a straight rail meeting its outside is what a player builds instead.
     */
    private int track(int x, int z) {
        int m = links(x, z);
        if (Integer.bitCount(m) != 3) return m;
        int stem = 0;
        for (int d = 0; d < 4; d++) if ((m & 1 << d) != 0 && (m & 1 << OPP[d]) == 0) stem = d;
        // a switch beside a switch: the two join each other
        int a = stem <= 1 ? 2 : 0, b = OPP[a];
        boolean sa = Integer.bitCount(links(x + DIRS[a][0], z + DIRS[a][1])) == 3;
        boolean sb = Integer.bitCount(links(x + DIRS[b][0], z + DIRS[b][1])) == 3;
        if (sa != sb) return 1 << stem | 1 << (sa ? a : b);
        int px = x, pz = z, cx = x + DIRS[stem][0], cz = z + DIRS[stem][1];
        for (int step = 1; step < SWITCH_WALK; step++) {
            int cm = links(cx, cz);
            if (Integer.bitCount(cm) != 2) break;
            int nx = NONE, nz = NONE;
            for (int e = 0; e < 4; e++) {
                int qx = cx + DIRS[e][0], qz = cz + DIRS[e][1];
                if ((cm & 1 << e) != 0 && (qx != px || qz != pz)) {
                    nx = qx;
                    nz = qz;
                }
            }
            px = cx;
            pz = cz;
            cx = nx;
            cz = nz;
        }
        boolean ns = stem <= 1; // the through track runs east-west
        int delta = ns ? cx - x : cz - z;
        int side = ns ? (delta > 0 ? 3 : 2) : (delta > 0 ? 0 : 1);
        return 1 << stem | 1 << side;
    }

    private static boolean curve(int m) {
        return Integer.bitCount(m) == 2 && m != 3 && m != 12;
    }

    /**
     * Level of a rail (its bed block; the rail lies one above). A curve takes the highest base level along its run
     * of curves up to {@link #CHAIN_WALK} steps each way. A straight rail between two higher ones is raised to them;
     * one a level above the curve beside it drops to the curve and ascends towards its other side.
     */
    public int railLevel(int x, int z) {
        int k = g.index(x, z);
        if (railLevel[k] != UNSET) return railLevel[k];
        int lvl = preLevel(x, z);
        int m = track(x, z);
        if (Integer.bitCount(m) == 2 && !curve(m)) {
            int a = NONE, b = NONE;
            boolean ac = false, bc = false;
            for (int d = 0; d < 4; d++) {
                if ((m & 1 << d) == 0) continue;
                int nx = x + DIRS[d][0], nz = z + DIRS[d][1];
                int p = preLevel(nx, nz);
                boolean c = curve(track(nx, nz));
                if (a == NONE) {
                    a = p;
                    ac = c;
                } else {
                    b = p;
                    bc = c;
                }
            }
            if (a > lvl && b > lvl) lvl = Math.min(a, b);
            // one up from a curve (which cannot climb): drop to it and climb towards the other side instead
            else if (ac && a == lvl - 1 && b >= lvl - 1 && b <= lvl || bc && b == lvl - 1 && a >= lvl - 1 && a <= lvl) lvl--;
        }
        return railLevel[k] = lvl;
    }

    private int preLevel(int x, int z) {
        int m = track(x, z);
        if (!curve(m)) return railBase(x, z);
        int lvl = railBase(x, z);
        for (int d = 0; d < 4; d++) {
            if ((m & 1 << d) == 0) continue;
            int px = x, pz = z, cx = x + DIRS[d][0], cz = z + DIRS[d][1];
            for (int step = 1; step <= CHAIN_WALK; step++) {
                int cm = track(cx, cz);
                if (!curve(cm)) break;
                lvl = Math.max(lvl, railBase(cx, cz));
                int nx = NONE, nz = NONE;
                for (int e = 0; e < 4; e++) {
                    if ((cm & 1 << e) == 0) continue;
                    int qx = cx + DIRS[e][0], qz = cz + DIRS[e][1];
                    if (qx != px || qz != pz) {
                        nx = qx;
                        nz = qz;
                    }
                }
                px = cx;
                pz = cz;
                cx = nx;
                cz = nz;
            }
        }
        return lvl;
    }

    /** Shape joining exactly the track neighbours; a straight rail ascends towards a neighbour one level higher. */
    public Rail railShape(int x, int z) {
        int m = track(x, z);
        boolean n = (m & 1) != 0, s = (m & 2) != 0, e = (m & 4) != 0, w = (m & 8) != 0;
        if (curve(m)) {
            if (n) return e ? Rail.NORTH_EAST : Rail.NORTH_WEST;
            return e ? Rail.SOUTH_EAST : Rail.SOUTH_WEST;
        }
        int up = railLevel(x, z) + 1;
        if (n || s) {
            if (n && railLevel(x, z - 1) == up) return Rail.ASCENDING_NORTH;
            if (s && railLevel(x, z + 1) == up) return Rail.ASCENDING_SOUTH;
            return Rail.NORTH_SOUTH;
        }
        if (e && railLevel(x + 1, z) == up) return Rail.ASCENDING_EAST;
        if (w && railLevel(x - 1, z) == up) return Rail.ASCENDING_WEST;
        return Rail.EAST_WEST;
    }

    /**
     * The ballast block of a rail way column: a rail's level, else the smoothed bed, never above a rail centre within
     * two columns (so no rail lies in a trench below its own bed).
     */
    public int railBed(int x, int z) {
        if (rail(x, z)) return railLevel(x, z);
        int b = railBase(x, z);
        for (int j = -2; j <= 2; j++) {
            for (int i = -2; i <= 2; i++) {
                if (c0(x + i, z + j)) b = Math.min(b, railBase(x + i, z + j));
            }
        }
        int n = railNeed(x, z);
        return n == NONE ? b : Math.max(b, n);
    }

    // ---------------------------------------------------------------- decks

    /** The deck block of a way column (road deck, or rail ballast; under a road bridge, the road deck). */
    public int deck(int x, int z) {
        if (road(x, z)) return roadDeck(x, z);
        if (railWay(x, z)) return railBed(x, z);
        return g.deck(x, z);
    }

    /** Deck estimate within {@link #EARTHWORK_REACH}: rail columns use their smoothed base. */
    private int shoulderDeck(int x, int z) {
        return road(x, z) ? roadDeck(x, z) : railBase(x, z);
    }

    // ---------------------------------------------------------------- earthworks

    /**
     * New terrain top for a dry column without overlay or building near an open way, or NONE when it stays. At
     * Chebyshev distance k from a way column with deck D the top is held within D ± (k - 1): level with the deck at
     * the shoulder, then a 1:1 slope. Never lowered below water beside it; tunnel lining columns are left alone.
     */
    public int earthwork(int x, int z) {
        if (!g.present(x, z) || g.overlay(x, z) != 0 || g.flags(x, z) != 0 || g.hasBuilding(x, z)) return NONE;
        if (water(x, z) != NONE || structure(x, z)) return NONE;
        int lo = Integer.MIN_VALUE, hi = Integer.MAX_VALUE;
        for (int j = -EARTHWORK_REACH; j <= EARTHWORK_REACH; j++) {
            for (int i = -EARTHWORK_REACH; i <= EARTHWORK_REACH; i++) {
                if (!open(x + i, z + j)) continue;
                int k = Math.max(Math.abs(i), Math.abs(j));
                int d = shoulderDeck(x + i, z + j);
                lo = Math.max(lo, d - (k - 1));
                hi = Math.min(hi, d + (k - 1));
            }
        }
        if (lo == Integer.MIN_VALUE) return NONE;
        int top = g.top(x, z);
        int t = Math.max(Math.min(top, Math.max(lo, hi)), Math.min(lo, hi));
        int ns = neighbourWet(x, z);
        if (ns != NONE) t = Math.max(t, ns);
        return t == top ? NONE : t;
    }

    // ---------------------------------------------------------------- tunnels

    /** Longest stretch of a tunnel that ways burnt over it (crossing above, or along it) can cut out. */
    static final int BORE_GAP = 8;
    private static final int GAP_NONE = 0, GAP_RAIL = 1, GAP_ROAD = 2, GAP_OTHER = 3;

    /**
     * The sidecar burns tunnels first and roads and rails over them (overlay.py: later wins), so a way crossing above a
     * tunnel cuts its cells out. A column on a straight line between two cells of the same tunnel, at most
     * {@link #BORE_GAP} apart across overlay cells, is still bored: its floor lies between the two ends. Encoded as
     * floor * 4 + kind, or NONE.
     */
    private int gap(int x, int z) {
        int k = g.index(x, z);
        if (gap[k] != UNSET) return gap[k];
        int best = NONE, span = Integer.MAX_VALUE;
        if (g.present(x, z) && g.overlay(x, z) != 0 && !g.flag(x, z, ColumnGrid.TUNNEL)) {
            for (int[] d : DIRS) {
                if (d[0] < 0 || d[1] < 0) continue; // each axis once
                int a = reach(x, z, -d[0], -d[1]), b = reach(x, z, d[0], d[1]);
                if (a == NONE || b == NONE || a + b - 1 > BORE_GAP || a + b >= span) continue;
                int ax = x - a * d[0], az = z - a * d[1], bx = x + b * d[0], bz = z + b * d[1];
                if (g.overlay(ax, az) != g.overlay(bx, bz)) continue;
                if (across(ax, az, d) || across(bx, bz, d)) continue;
                // the smoothed bed, not deck(): a rail deck looks at rail centres, which may be gaps themselves
                int da = road(ax, az) ? roadDeck(ax, az) : railBase(ax, az);
                int db = road(bx, bz) ? roadDeck(bx, bz) : railBase(bx, bz);
                int f = da + (int) Math.round((db - da) * (double) a / (a + b));
                int kind = road(ax, az) && road(bx, bz) ? GAP_ROAD
                        : tunnelRailNode(ax, az) && tunnelRailNode(bx, bz) ? GAP_RAIL : GAP_OTHER;
                span = a + b;
                best = f * 4 + kind;
            }
        }
        return gap[k] = best;
    }

    /** Run of tunnel cells across a gap's direction, each side, beyond which the tunnel runs across it. */
    private static final int TUBE_RUN = 8;

    /**
     * The tunnel at (x, z) runs straight across direction d, not along it: a line of tunnel cells through it at right
     * angles to d, {@link #TUBE_RUN} each side, longer than any tunnel is wide. A gap in direction d from there would
     * lie between two parallel tubes, not inside one tube a way crossed.
     */
    private boolean across(int x, int z, int[] d) {
        int px = d[1], pz = d[0];
        for (int s = -TUBE_RUN; s <= TUBE_RUN; s++) {
            int cx = x + s * px, cz = z + s * pz;
            if (!g.present(cx, cz) || !tunnel(cx, cz)) return false;
        }
        return true;
    }

    /** Steps from (x, z) to the first tunnel cell in direction (dx, dz) across overlay cells, or NONE. */
    private int reach(int x, int z, int dx, int dz) {
        for (int s = 1; s <= BORE_GAP + 1; s++) {
            int cx = x + s * dx, cz = z + s * dz;
            if (!g.inside(cx, cz, ROAD_RADIUS + ROAD_RAMP + 1) || !g.present(cx, cz)) return NONE;
            if (tunnel(cx, cz)) return s;
            if (g.overlay(cx, cz) == 0 || g.flag(cx, cz, ColumnGrid.TUNNEL)) return NONE;
        }
        return NONE;
    }

    private int gapKind(int x, int z) {
        int v = gap(x, z);
        return v == NONE ? GAP_NONE : Math.floorMod(v, 4);
    }

    /** A tunnel way column, or a column a crossing way cut out of the tunnel ({@link #gap}). */
    public boolean bored(int x, int z) {
        return tunnel(x, z) || gap(x, z) != NONE;
    }

    /**
     * Overlay class of the tunnel a bored column belongs to: that of the nearest tunnel way column (a path or crossing
     * the bore took over gets the tunnel's road or rail surface), or the column's own.
     */
    public int boreClass(int x, int z) {
        if (tunnel(x, z)) return g.overlay(x, z);
        for (int r = 1; r <= BORE_GAP; r++) {
            for (int i = -r; i <= r; i++) {
                for (int[] p : new int[][] {{x + i, z - r}, {x + i, z + r}, {x - r, z + i}, {x + r, z + i}}) {
                    if (g.inside(p[0], p[1], 0) && g.present(p[0], p[1]) && tunnel(p[0], p[1])) return g.overlay(p[0], p[1]);
                }
            }
        }
        return g.overlay(x, z);
    }

    private int boreDeck(int x, int z) {
        return tunnel(x, z) ? deck(x, z) : Math.floorDiv(gap(x, z), 4);
    }

    private boolean boreRoad(int x, int z) {
        return tunnel(x, z) ? road(x, z) : gapKind(x, z) == GAP_ROAD;
    }

    private boolean boreRailNode(int x, int z) {
        return tunnel(x, z) ? tunnelRailNode(x, z) : gapKind(x, z) == GAP_RAIL;
    }

    private boolean tunnelRailNode(int x, int z) {
        return c0(x, z) && g.flag(x, z, ColumnGrid.TUNNEL) && railWay(x, z);
    }

    /**
     * Floor of the bored profile at this column (the block under its air), or NONE outside it. A tunnel way column's
     * floor is its own deck. The profile adds one column each side of a road and two each side of a rail centre; such
     * a column takes the lowest deck it serves, so it never closes the headroom of the way beside it.
     */
    public int tunnelFloor(int x, int z) {
        int k = g.index(x, z);
        if (floor[k] != UNSET) return floor[k] == NONE ? NONE : floor[k] >> 1;
        int f = NONE;
        boolean rail = false;
        if (g.present(x, z)) {
            if (bored(x, z)) {
                f = boreDeck(x, z);
                rail = !boreRoad(x, z);
            } else {
                int lo = Integer.MAX_VALUE;
                boolean own = false;
                for (int j = -2; j <= 2; j++) {
                    for (int i = -2; i <= 2; i++) {
                        int nx = x + i, nz = z + j;
                        // one column beside a road, two beside a rail centre: 5 wide
                        boolean near = Math.abs(i) <= 1 && Math.abs(j) <= 1 && boreRoad(nx, nz);
                        if (bored(nx, nz) && (near || boreRailNode(nx, nz))) {
                            lo = Math.min(lo, boreDeck(nx, nz));
                            rail |= !boreRoad(nx, nz);
                            own |= way(x, z) && tunnel(nx, nz) && g.overlay(nx, nz) == g.overlay(x, z);
                        }
                    }
                }
                if (lo != Integer.MAX_VALUE) f = lo;
                // the tunnel's own approach stays open; any other way here gives way to the bore
                if (f != NONE && own) f = NONE;
            }
        }
        floor[k] = f == NONE ? NONE : f * 2 + (rail ? 1 : 0);
        return f;
    }

    /** Least headroom under a watercourse: a cart and its rider, or a walker, still pass. */
    static final int MIN_HEADROOM = 3;

    /**
     * The highest air block of the profile here. Under a watercourse it stays one below the bed of every wet column
     * within 2, so the lining (up to the highest ceiling beside it) ends at the bed and the water keeps running above.
     */
    public int tunnelAirTop(int x, int z) {
        int f = tunnelFloor(x, z);
        if (f == NONE) return NONE;
        boolean rail = (floor[g.index(x, z)] & 1) != 0;
        int a = f + (rail ? RAIL_TUNNEL_HEIGHT : ROAD_TUNNEL_HEIGHT);
        for (int j = -2; j <= 2; j++) {
            for (int i = -2; i <= 2; i++) {
                if (g.present(x + i, z + j) && wetTop(x + i, z + j) != NONE) {
                    a = Math.min(a, Math.max(f + MIN_HEADROOM, g.ground(x + i, z + j) - 1));
                }
            }
        }
        return a;
    }

    /** The ceiling of the profile here: above the highest air of every profile column beside it. */
    public int tunnelCeiling(int x, int z) {
        int k = g.index(x, z);
        if (ceil[k] != UNSET) return ceil[k];
        int c = NONE;
        if (tunnelFloor(x, z) != NONE) {
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    int a = tunnelAirTop(x + i, z + j);
                    if (a != NONE) c = Math.max(c, a + 1);
                }
            }
        }
        return ceil[k] = c;
    }

    /** Lowest floor among the profile columns beside (x, z) and itself: the lining reaches down to it. */
    public int tunnelLowFloor(int x, int z) {
        int lo = Integer.MAX_VALUE;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                int f = tunnelFloor(x + i, z + j);
                if (f != NONE) lo = Math.min(lo, f);
            }
        }
        return lo == Integer.MAX_VALUE ? NONE : lo;
    }

    /** Highest ceiling among the profile columns beside (x, z) and itself: the lining reaches up to it. */
    public int tunnelHighCeiling(int x, int z) {
        int hi = NONE;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                if (tunnelFloor(x + i, z + j) != NONE) hi = Math.max(hi, tunnelCeiling(x + i, z + j));
            }
        }
        return hi;
    }

    /** A wall column: outside the profile, beside it, and no way (the way leading to a portal stays open). */
    public boolean tunnelWall(int x, int z) {
        if (!g.present(x, z) || tunnelFloor(x, z) != NONE || tunnelHighCeiling(x, z) == NONE) return false;
        return !way(x, z) || over(x, z);
    }

    /** Part of a tunnel (profile or wall): earthworks leave it alone. */
    public boolean structure(int x, int z) {
        return tunnelFloor(x, z) != NONE || tunnelWall(x, z);
    }

    // ---------------------------------------------------------------- blocks

    /** What goes into a block; {@link OverlayStage} picks the block state. */
    public enum Part {
        AIR, FILL, SOIL, BEAM, LINING, COVER, BALLAST, SURFACE, SLAB, RAIL, RAILING, LANTERN,
        /** The floor of a bore under a way it absorbed or cut: the tunnel's own surface ({@link #boreClass}). */
        FLOOR,
        /** The surface block that was on the column's old top (earthworks move it). */
        TOP,
        /** A stream's bed right over a tunnel's air: its old bed block if that cannot fall, else one like it that cannot. */
        BED
    }

    @FunctionalInterface
    public interface Sink {
        void set(int y, Part p);
    }

    /** A lantern every this many blocks along a tunnel. */
    static final int LIGHT_SPACING = 16;

    /**
     * Writes this column's road, rail, tunnel or earthwork blocks, bottom to top. False when the column is other
     * overlay (steps, dam, grass), which keeps {@link ColumnGrid#deck}; nothing is written then.
     */
    public boolean build(int x, int z, Sink out) {
        if (!g.present(x, z)) return true;
        if (structure(x, z)) {
            tunnelColumn(x, z, out);
            if (over(x, z)) wayColumn(x, z, tunnelHighCeiling(x, z) + 1, out);
        } else if (way(x, z)) {
            wayColumn(x, z, Integer.MIN_VALUE, out);
        } else if (g.overlay(x, z) != 0 || g.flag(x, z, ColumnGrid.DAM_BODY)) {
            return false;
        } else {
            int to = earthwork(x, z);
            if (to != NONE) {
                int top = g.top(x, z);
                if (to < top) {
                    for (int y = to + 1; y <= top; y++) out.set(y, Part.AIR);
                } else {
                    for (int y = top; y < to; y++) out.set(y, Part.SOIL);
                }
                out.set(to, Part.TOP);
            }
        }
        return true;
    }

    /** A rail block on this column's deck (outside a tunnel, not under a road bridge). */
    private boolean trackOnDeck(int x, int z) {
        return !OverlayPlan.railUnderBridge(g, x, z) && rail(x, z) && gap(x, z) == NONE && !cut(x, z);
    }

    /**
     * A bridge railing on this column: none where the track runs, since a diagonal track's staircase touches the edges
     * of a narrow rail bridge, and a wall there would cut the line.
     */
    public boolean railing(int x, int z) {
        return OverlayPlan.railing(g, x, z) && !(railWay(x, z) && trackOnDeck(x, z));
    }

    /** A road or rail column outside a tunnel: embankment or cut, culvert roof over water, deck, slab or rail. */
    private void wayColumn(int x, int z, int bottom, Sink out) {
        boolean bridge = g.flag(x, z, ColumnGrid.BRIDGE);
        boolean underBridge = OverlayPlan.railUnderBridge(g, x, z);
        int top = g.top(x, z), deck = deck(x, z);
        int wet = wetTop(x, z);
        boolean road = road(x, z);
        if (bridge) {
            out.set(deck - 1, Part.BEAM);
            if (OverlayPlan.pier(g, x, z)) {
                for (int y = g.ground(x, z) + 1; y <= deck - 2; y++) out.set(y, Part.BEAM);
            }
        } else {
            // embankment on the terrain, or the roof of a culvert over this column's water
            for (int y = Math.max(Math.max(top, wet) + 1, bottom); y < deck; y++) out.set(y, Part.FILL);
        }
        for (int y = Math.max(deck + 1, bottom); y <= top; y++) out.set(y, Part.AIR); // cut
        if (underBridge) {
            // swd-format §5: a rail on a ballast bed on the terrain, under the road deck and its beam
            int bed = railBed(x, z);
            if (bed + 2 < deck - 1) {
                for (int y = top + 1; y < bed; y++) out.set(y, Part.FILL);
                for (int y = bed + 1; y <= Math.min(top, deck - 2); y++) out.set(y, Part.AIR);
                out.set(bed, Part.BALLAST);
                if (rail(x, z)) out.set(bed + 1, Part.RAIL);
            }
        }
        out.set(deck, road ? Part.SURFACE : Part.BALLAST);
        if (trackOnDeck(x, z)) {
            out.set(deck + 1, Part.RAIL);
        } else if (OverlayPlan.railing(g, x, z)) {
            out.set(deck + 1, Part.RAILING);
        } else if (road && roadSlab(x, z)) {
            out.set(deck + 1, Part.SLAB);
        }
    }

    /**
     * A column of a bored tunnel. Profile columns: lining from the lowest floor nearby up to the own floor, air up to
     * the ceiling, lining from the ceiling up to the highest ceiling nearby. Wall columns: lining over that whole
     * range. So every air block of the profile has lining or air on all six sides, and no water reaches it. Where
     * less than two blocks of terrain lie above the lining, a layer of soil covers it (a covered cut).
     */
    private void tunnelColumn(int x, int z, Sink out) {
        int top = g.top(x, z), lo = tunnelLowFloor(x, z), hi = tunnelHighCeiling(x, z);
        int floor = tunnelFloor(x, z);
        for (int y = Math.min(top, g.ground(x, z)) + 1; y < lo; y++) out.set(y, Part.FILL);
        if (floor == NONE) {
            for (int y = lo; y <= hi; y++) out.set(y, Part.LINING);
        } else {
            int ceil = tunnelCeiling(x, z);
            for (int y = lo; y < floor; y++) out.set(y, Part.LINING);
            for (int y = floor + 1; y < ceil; y++) out.set(y, Part.AIR);
            for (int y = ceil; y <= hi; y++) out.set(y, Part.LINING);
            if (tunnel(x, z) || gapKind(x, z) == GAP_RAIL) {
                boolean road = road(x, z) && tunnel(x, z);
                out.set(floor, road ? Part.SURFACE : Part.BALLAST);
                if (road && roadSlab(x, z)) out.set(floor + 1, Part.SLAB);
                if (rail(x, z)) out.set(floor + 1, Part.RAIL);
                boolean centre = rail(x, z) || road && !g.flag(x, z, ColumnGrid.EDGE);
                boolean lit = Math.floorMod(g.east(x) + g.north(z), LIGHT_SPACING) == 0 && ceil - 1 > floor + MIN_HEADROOM;
                if (centre && lit) out.set(ceil - 1, Part.LANTERN);
            } else {
                out.set(floor, Part.FLOOR);
                if (rail(x, z) && railLevel(x, z) == floor) out.set(floor + 1, Part.RAIL);
            }
        }
        // a stream over a shallow bore keeps its bed: the lining's top block under the water is bed material
        int bed = g.ground(x, z);
        if (wetTop(x, z) != NONE && bed > (floor == NONE ? lo : floor) && bed <= hi) {
            boolean lined = floor == NONE || bed - 1 >= tunnelCeiling(x, z); // lining under it, not the bore's air
            out.set(bed, lined ? Part.TOP : Part.BED);
        }
        if (wetTop(x, z) == NONE && top < hi + 2 && !over(x, z)) {
            for (int y = Math.max(top, hi) + 1; y <= hi + 1; y++) out.set(y, y == hi + 1 ? Part.COVER : Part.SOIL);
        }
    }
}
