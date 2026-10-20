package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.surface.OverlayPlan.Rail;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Roads, rails and tunnels laid out from {@link ColumnGrid} data only, so that two chunks sharing a border agree on
 * every block. Each decision is a pure function of the columns within a fixed distance; there is no iteration.
 * Every look-up stays within {@link #MARGIN} of the chunk (the longest chain, a tunnel wall over a rail track whose
 * centre line a crossing way broke, reaches about 25, plus {@link #RAIL_CLIMB} where a rail bed is raised towards a
 * bridge; a chunk planned with a wider grid comes out the same).
 * <ul>
 *   <li>Profile: a weighted mean of the deck heights of the same kind of way within {@link #ROAD_RADIUS} (roads) or
 *       {@link #RAIL_RADIUS} (rails). A mean over a disc keeps a constant grade and removes the cross slope, the
 *       per-column noise of the terrain, and single high or low columns.</li>
 *   <li>Rail track: the rail centre columns, minus one of two touching tracks; every rail's
 *       shape joins exactly its track neighbours, and its level is flat through curves (a curve cannot ascend).</li>
 *   <li>Earthworks: dry terrain within {@link #EARTHWORK_REACH} of an open way is cut or filled to the way's level
 *       at the shoulder, one block per column out to {@link #EARTHWORK_STEEP}, then easing back into the terrain,
 *       so a cut into a steep slope ends in grassed steps instead of a wall.</li>
 *   <li>Tunnels: a bored profile lined with a floor, walls and a ceiling, closed against water and, where the cover
 *       is thin, buried under a block of soil (a covered cut).</li>
 * </ul>
 */
public final class TransportPlan {
    /** Grid margin this plan needs: {@code new ColumnGrid(.., MARGIN)}. */
    public static final int MARGIN = 36;
    static final int ROAD_RADIUS = 5, RAIL_RADIUS = 4;
    /** How far a road column looks across its road for the middle whose level it takes. */
    static final int ROAD_ACROSS = 8;
    private static final int[][] AXES = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
    /** How far a culvert or a water-side constraint ramps the way up: one half block (road) or block (rail) per column. */
    static final int ROAD_RAMP = 6, RAIL_RAMP = 4;
    /** A rail bed rises at most one block per CLIMB_RUN columns, raised (never cut) within RAIL_CLIMB columns. */
    static final int RAIL_CLIMB = 10, CLIMB_RUN = 2;
    /** Clear height above a tunnel floor. */
    static final int RAIL_TUNNEL_HEIGHT = 6, ROAD_TUNNEL_HEIGHT = 5;
    static final int EARTHWORK_REACH = 12, EARTHWORK_STEEP = 3;
    /** Soil under a cut's top: as deep as the steepest step of a cut, so its faces show no bare stone. */
    static final int CUT_SOIL = EARTHWORK_STEEP;
    /** How far a curve looks along its track for the level it keeps. */
    static final int CHAIN_WALK = 3;
    static final int NONE = ColumnGrid.NONE;
    private static final int UNSET = Integer.MAX_VALUE;

    private final ColumnGrid g;
    private final int[] roadHalf, railBase, railProfile, railNode, railLevel, floor, ceil, wet, gap, joins, flipped, low;
    private final int[][] thinned;

    public TransportPlan(ColumnGrid g) {
        if (g.margin() < MARGIN) throw new IllegalArgumentException("grid margin " + g.margin() + " < " + MARGIN);
        this.g = g;
        int n = g.cells();
        roadHalf = filled(n);
        railBase = filled(n);
        railProfile = filled(n);
        railNode = filled(n);
        railLevel = filled(n);
        floor = filled(n);
        ceil = filled(n);
        wet = filled(n);
        gap = filled(n);
        joins = filled(n);
        flipped = filled(n);
        low = filled(n);
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

    /**
     * A way on the ground: not a bridge (unless built as an embankment, {@link #lowBridge}), not in a tunnel. Its
     * terrain is cut or filled to its deck.
     */
    public boolean open(int x, int z) {
        return way(x, z) && !g.flag(x, z, ColumnGrid.TUNNEL) && !absorbed(x, z)
                && (!g.flag(x, z, ColumnGrid.BRIDGE) || lowBridge(x, z));
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

    /**
     * The road profile of the column in the middle of the road across this one, so a cross-section is level: the disc
     * of an edge column is cut off by the road's edge and, on a cross slope, would set the edge a half step off the
     * middle. Across is the shortest of four axes (two straight, two diagonal) through the road; where no axis is
     * clearly the shortest (a square or junction) or the road is wider than {@link #ROAD_ACROSS}, the column keeps its
     * own profile.
     */
    private double acrossProfile(int x, int z) {
        double shortest = Double.MAX_VALUE, longest = 0;
        int cx = x, cz = z;
        for (int[] a : AXES) {
            int p = roadRun(x, z, a[0], a[1]), n = roadRun(x, z, -a[0], -a[1]);
            double len = (p + n + 1) * (a[0] != 0 && a[1] != 0 ? Math.sqrt(2) : 1);
            longest = Math.max(longest, len);
            if (len < shortest) {
                shortest = len;
                boolean capped = p == ROAD_ACROSS || n == ROAD_ACROSS;
                int m = Math.floorDiv(p - n, 2);
                cx = capped ? x : x + a[0] * m;
                cz = capped ? z : z + a[1] * m;
            }
        }
        if (longest < 2 * shortest) return profile(x, z, false);
        return profile(cx, cz, false);
    }

    /** Road columns of this road in a row from this one in direction (dx, dz), at most {@link #ROAD_ACROSS}. */
    private int roadRun(int x, int z, int dx, int dz) {
        int k = 0;
        while (k < ROAD_ACROSS && road(x + (k + 1) * dx, z + (k + 1) * dz) && !crossing(x, z, x + (k + 1) * dx, z + (k + 1) * dz)) k++;
        return k;
    }

    /** Road deck in half blocks: {@code 2 * deck + 1} when a bottom slab lies on the deck. */
    private int roadHalf(int x, int z) {
        int k = g.index(x, z);
        if (roadHalf[k] != UNSET) return roadHalf[k];
        double v = acrossProfile(x, z);
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

    /** The rail profile (see {@link #profile}) as a block level. */
    private int railProfile(int x, int z) {
        int k = g.index(x, z);
        if (railProfile[k] == UNSET) railProfile[k] = (int) Math.floor(profile(x, z, true));
        return railProfile[k];
    }

    /** Smoothed rail bed level before the track's own adjustments. */
    public int railBase(int x, int z) {
        int k = g.index(x, z);
        if (railBase[k] != UNSET) return railBase[k];
        if (gapKind(x, z) == GAP_RAIL) return railBase[k] = boreDeck(x, z);
        if (gapSide(x, z)) {
            int b = Integer.MAX_VALUE;
            // the lower of the track's columns beside it: a corner moved here is the foot of the step
            for (int[] d : DIRS) {
                int nx = x + d[0], nz = z + d[1];
                if (gapKind(nx, nz) == GAP_RAIL || tunnelRailNode(nx, nz)) b = Math.min(b, railBase(nx, nz));
            }
            return railBase[k] = b;
        }
        if (cut(x, z)) {
            int b = Integer.MAX_VALUE;
            for (int[] d : EIGHT) if (railTunnel(x + d[0], z + d[1])) b = Math.min(b, railBase(x + d[0], z + d[1]));
            return railBase[k] = b;
        }
        int b = railProfile(x, z);
        if (!g.flag(x, z, ColumnGrid.TUNNEL)) {
            // never more than one block per CLIMB_RUN columns below a bridge's profile: the steep rise from the ground
            // onto a bridge deck becomes a ramp a diagonal track can climb by its flipped corners
            for (int jj = -RAIL_CLIMB; jj <= RAIL_CLIMB; jj++) {
                for (int ii = -RAIL_CLIMB; ii <= RAIL_CLIMB; ii++) {
                    int qx = x + ii, qz = z + jj;
                    if (!railWay(qx, qz) || !g.flag(qx, qz, ColumnGrid.BRIDGE) || g.flag(qx, qz, ColumnGrid.TUNNEL) || crossing(x, z, qx, qz)) continue;
                    b = Math.max(b, railProfile(qx, qz) - Math.max(Math.abs(ii), Math.abs(jj)) / CLIMB_RUN);
                }
            }
        }
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
        for (int[] d : DIAG) {
            // the corner of a diagonal step whose both corners another way took (overlay.py: later wins)
            if (c0(x + d[0], z) && c0(x, z + d[1]) && !c0(x + d[0], z + d[1]) && corner(x, z, x + d[0], z + d[1])) {
                return true;
            }
        }
        for (int[] d : DIRS) if (!c0(x + d[0], z + d[1])) return false;
        return railWay(x, z);
    }

    /** Of a step's two corners, (x, z) takes the track before (ox, oz): rail way first, then the southern one. */
    private boolean corner(int x, int z, int ox, int oz) {
        int a = railWay(x, z) ? 1 : 0, b = railWay(ox, oz) ? 1 : 0;
        return a != b ? a > b : z > oz;
    }

    /**
     * Two tracks drawn side by side (two centre lines touching, or one drawn two wide) thinned to one: pass by pass,
     * the centres with no centre on the pass's side are dropped where that keeps their neighbours joined and does not
     * shorten a track (a simple point of the 4-connected centres). Each pass looks one column further around. A
     * main-line centre (swd-format §5, bit5) that touches another track's centre stays: the other track gives way, so
     * where two tracks run side by side the main line keeps its rails (2339,-1987).
     */
    private boolean thin(int x, int z, int pass) {
        if (pass == 0) return c00(x, z);
        int k = g.index(x, z);
        int[] cache = thinned[pass - 1];
        if (cache[k] != UNSET) return cache[k] == 1;
        boolean keep = thin(x, z, pass - 1);
        if (keep) {
            int[] d = DIRS[THIN[pass - 1]];
            if (!thin(x + d[0], z + d[1], pass - 1) && simple(x, z, pass - 1) && !mainBeside(x, z, pass - 1)) keep = false;
        }
        cache[k] = keep ? 1 : 0;
        return keep;
    }

    /** A main-line centre with a centre of another track (or a gap column between tracks) beside it. */
    private boolean mainBeside(int x, int z, int pass) {
        if (!g.flag(x, z, ColumnGrid.MAIN)) return false;
        for (int[] d : DIRS) {
            if (thin(x + d[0], z + d[1], pass) && !g.flag(x + d[0], z + d[1], ColumnGrid.MAIN)) return true;
        }
        return false;
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
        // every node stays, a crossing (four) included: dropping it broke both lines (2339,-1989); see track()
        return degree(x, z) <= 4;
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
        // in a bore under a crossing way ({@link #joinGap}) the corner may move onto the way's column beside the track
        if (node0(fx, fz) || !railWay(fx, fz) && !(gapKind(x, z) == GAP_RAIL && gapSide(fx, fz))) return false;
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
    /** How far a switch looks along each of its arms (see {@link #arm}). */
    static final int SWITCH_WALK = 6;

    /**
     * The two neighbours a rail joins (bit mask as {@link #links}): its track neighbours, or at a switch (three) the
     * two arms that run on most nearly in a straight line (a line steps sideways at a switch where it jogs between
     * parallel tracks), so a line runs on unbroken past every branch; the branch ends beside it. Vanilla rails have no
     * switch block (a powered T junction turns, an unpowered one keeps its shape), so the branch is the side that
     * gives way. Two exceptions: an arm that ends within {@link #SWITCH_WALK} rails is a stub and gives way instead,
     * and of two arms that meet again at the same switch (a short loop, where two tracks drawn side by side were
     * thinned apart) the longer one gives way, so both switches of the loop route the line the same way. Before both,
     * where the data tags a main line (swd-format §5) and exactly two arms run on it, those two carry on: at a Y where
     * a loop or siding merges, the straighter pair is often the loop's, and the main line broke there.
     */
    private int track(int x, int z) {
        int m = links(x, z);
        if (Integer.bitCount(m) == 4) return crossing(x, z);
        if (Integer.bitCount(m) != 3) return m;
        int[][] arm = new int[4][];
        for (int d = 0; d < 4; d++) if ((m & 1 << d) != 0) arm[d] = arm(x, z, d);
        int stubs = 0, stub = -1;
        for (int d = 0; d < 4; d++) {
            if (arm[d] != null && arm[d][0] == ARM_END) {
                stubs++;
                stub = d;
            }
        }
        if (stubs == 1) return m & ~(1 << stub);
        int main = mainPair(arm);
        if (main != 0) return main;
        for (int d = 0; d < 4; d++) {
            for (int e = d + 1; e < 4; e++) {
                if (arm[d] == null || arm[e] == null || arm[d][0] != ARM_SWITCH || arm[e][0] != ARM_SWITCH) continue;
                if (arm[d][2] != arm[e][2] || arm[d][3] != arm[e][3] || arm[d][1] == arm[e][1]) continue;
                return m & ~(1 << (arm[d][1] > arm[e][1] ? d : e));
            }
        }
        // the two arms that run on most nearly in a straight line, seen from where their walks end
        int drop = -1;
        double best = Double.MAX_VALUE;
        for (int d = 0; d < 4; d++) {
            if (arm[d] == null) continue;
            int a = -1, b = -1;
            for (int e = 0; e < 4; e++) {
                if (e == d || arm[e] == null) continue;
                if (a < 0) a = e;
                else b = e;
            }
            double ax = arm[a][2] - x, az = arm[a][3] - z, bx = arm[b][2] - x, bz = arm[b][3] - z;
            double cos = (ax * bx + az * bz) / Math.sqrt((ax * ax + az * az) * (bx * bx + bz * bz));
            boolean through = OPP[a] == b;
            if (cos < best - 1e-9 || Math.abs(cos - best) <= 1e-9 && through) {
                best = cos;
                drop = d;
            }
        }
        return m & ~(1 << drop);
    }

    /**
     * At a crossing (four track neighbours) vanilla rails can join only two: the pair of arms that runs on most nearly
     * in a straight line, seen from where their walks end, carries on; the other two end beside it. Arms that end
     * within {@link #SWITCH_WALK} rails (stubs) are passed over while a pair of longer ones exists. Bit mask as
     * {@link #links}.
     */
    private int crossing(int x, int z) {
        int[][] arm = new int[4][];
        for (int d = 0; d < 4; d++) arm[d] = arm(x, z, d);
        int main = mainPair(arm);
        if (main != 0) return main;
        int pick = 0;
        double best = Double.MAX_VALUE;
        for (int d = 0; d < 4; d++) {
            for (int e = d + 1; e < 4; e++) {
                double ax = arm[d][2] - x, az = arm[d][3] - z, bx = arm[e][2] - x, bz = arm[e][3] - z;
                double cos = (ax * bx + az * bz) / Math.sqrt((ax * ax + az * az) * (bx * bx + bz * bz));
                int stubs = (arm[d][0] == ARM_END ? 1 : 0) + (arm[e][0] == ARM_END ? 1 : 0);
                double score = stubs * 4 + cos - (OPP[d] == e ? 1e-6 : 0);
                if (score < best) {
                    best = score;
                    pick = 1 << d | 1 << e;
                }
            }
        }
        return pick;
    }

    private static final int ARM_OPEN = 0, ARM_END = 1, ARM_SWITCH = 2;

    /**
     * The two arms on the main line (most of their walked rails tagged {@link ColumnGrid#MAIN}) when exactly two are
     * and neither is a stub, as a {@link #links} bit mask; else 0 (no tag, as in older cells, or a yard where more
     * arms are tagged), and the shape rules decide.
     */
    private static int mainPair(int[][] arm) {
        int m = 0;
        for (int d = 0; d < 4; d++) {
            if (arm[d] != null && arm[d][4] * 2 > arm[d][1]) {
                if (arm[d][0] == ARM_END) return 0;
                m |= 1 << d;
            }
        }
        return Integer.bitCount(m) == 2 ? m : 0;
    }

    /**
     * Walks a switch's arm in direction d for up to {@link #SWITCH_WALK} rails: {kind, steps, x, z, main} where kind is
     * {@link #ARM_END} (the track ends), {@link #ARM_SWITCH} (another switch, at x, z) or {@link #ARM_OPEN}, and main
     * counts the walked rails on a main line ({@link ColumnGrid#MAIN}).
     */
    private int[] arm(int x, int z, int d) {
        int px = x, pz = z, cx = x + DIRS[d][0], cz = z + DIRS[d][1], main = 0;
        for (int step = 1; step <= SWITCH_WALK; step++) {
            if (g.present(cx, cz) && g.flag(cx, cz, ColumnGrid.MAIN)) main++;
            int cm = links(cx, cz);
            int n = Integer.bitCount(cm);
            if (n <= 1) return new int[] {ARM_END, step, cx, cz, main};
            if (n >= 3) return new int[] {ARM_SWITCH, step, cx, cz, main};
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
        return new int[] {ARM_OPEN, SWITCH_WALK, cx, cz, main};
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
     * the shoulder, then a 1:1 slope out to {@link #EARTHWORK_STEEP}, then easing back into the terrain by
     * {@link #EARTHWORK_REACH}. Never lowered below water beside it; tunnel
     * lining columns are left alone.
     */
    public int earthwork(int x, int z) {
        if (!g.present(x, z) || g.overlay(x, z) != 0 || g.flags(x, z) != 0 || g.hasBuilding(x, z)) return NONE;
        if (water(x, z) != NONE || structure(x, z)) return NONE;
        int lo = Integer.MIN_VALUE, hi = Integer.MAX_VALUE, near = Integer.MAX_VALUE;
        for (int j = -EARTHWORK_REACH; j <= EARTHWORK_REACH; j++) {
            for (int i = -EARTHWORK_REACH; i <= EARTHWORK_REACH; i++) {
                if (!open(x + i, z + j)) continue;
                int k = Math.max(Math.abs(i), Math.abs(j));
                int d = shoulderDeck(x + i, z + j);
                lo = Math.max(lo, d - (k - 1));
                hi = Math.min(hi, d + (k - 1));
                near = Math.min(near, k);
            }
        }
        if (lo == Integer.MIN_VALUE) return NONE;
        int top = g.top(x, z);
        int t = Math.max(Math.min(top, Math.max(lo, hi)), Math.min(lo, hi));
        // beyond the 1:1 slope the cut or fill eases back into the terrain, meeting it at the terrain's own slope, so
        // no wall stands where it ends
        if (near > EARTHWORK_STEEP) {
            double u = (near - EARTHWORK_STEEP) / (double) (EARTHWORK_REACH + 1 - EARTHWORK_STEEP);
            double w = 1 - (1 - u) * (1 - u);
            t = (int) Math.round(t + w * (top - t));
        }
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
            // a join between two tunnel ends wins over the axis scan: one track, one level profile through the cut
            int j = joinGap(x, z);
            if (j != NONE) best = j;
        } else if (g.present(x, z) && g.overlay(x, z) != 0 && !OverlayPlan.railCentre(g, x, z)) {
            // a tunnel's own band column beside a cut centre line (the band is wider than the line)
            best = joinGap(x, z);
        }
        return gap[k] = best;
    }

    /**
     * A column on the staircase between two rail tunnel ends that face each other more than {@link #BRIDGE} steps and at
     * most {@link #BORE_GAP} + 1 apart (each axis) across other ways: floor * 4 + {@link #GAP_RAIL}, or NONE. The axis scan of {@link #gap} misses a way
     * that crosses the tunnel at a slant, whose two ends are offset both ways (Brienz, 12194,-7972 to 12198,-7969: the
     * sidecar keeps a short road crossing's deck over the tunnel, and the mod bores under it).
     */
    private int joinGap(int x, int z) {
        int r = BORE_GAP + 1;
        if (!g.inside(x, z, r + ROAD_RADIUS + ROAD_RAMP + 1)) return NONE;
        List<int[]> ends = null;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int[] u = tunnelEnd(x + dx, z + dz);
                if (u == null) continue;
                if (ends == null) ends = new ArrayList<>();
                ends.add(new int[] {x + dx, z + dz, u[0], u[1]});
            }
        }
        if (ends == null || ends.size() < 2) return NONE;
        int best = NONE, span = Integer.MAX_VALUE;
        for (int i = 0; i < ends.size(); i++) {
            for (int j = i + 1; j < ends.size(); j++) {
                int[] a = ends.get(i), b = ends.get(j); // a before b: the same staircase from both
                int ex = b[0] - a[0], ez = b[1] - a[1];
                int m = Math.abs(ex) + Math.abs(ez);
                // a shorter cut is joined by its track's ends (bridge) and keeps the axis gap's floor
                if (m <= BRIDGE || m >= span || Math.max(Math.abs(ex), Math.abs(ez)) > r) continue;
                if (a[2] * ex + a[3] * ez <= 0 || -(b[2] * ex + b[3] * ez) <= 0) continue; // facing each other
                int[][] p = staircase(a[0], a[1], b[0], b[1]);
                int at = -1;
                boolean ok = true;
                for (int q = 0; q < p.length && ok; q++) {
                    int px = p[q][0], pz = p[q][1];
                    ok = g.present(px, pz) && g.overlay(px, pz) != 0 && !OverlayPlan.railCentre(g, px, pz);
                    if (px == x && pz == z) at = q;
                }
                if (!ok || at < 0) continue;
                // along the line's main axis: the level changes on straight links (or on the north-south steps of a
                // diagonal, which climb by their flipped corners), never between the two rails of a corner
                int da = railBase(a[0], a[1]), db = railBase(b[0], b[1]);
                double t = 2 * Math.abs(ez) >= Math.abs(ex) ? (z - a[1]) / (double) ez : (x - a[0]) / (double) ex;
                int f = da + (int) Math.round((db - da) * t);
                span = m;
                best = f * 4 + GAP_RAIL;
            }
        }
        return best;
    }

    /** A column of another way beside a {@link #joinGap} rail column, where a staircase corner may move (see {@link #flip}). */
    private boolean gapSide(int x, int z) {
        if (!g.present(x, z) || railWay(x, z) || g.overlay(x, z) == 0 || OverlayPlan.railCentre(g, x, z)) return false;
        if (gapKind(x, z) == GAP_RAIL) return false;
        for (int[] d : DIRS) if (gapKind(x + d[0], z + d[1]) == GAP_RAIL) return true;
        return false;
    }

    /**
     * A rail tunnel's centre column with exactly one centre beside it: the way out, from that neighbour to this end, or
     * null.
     */
    private int[] tunnelEnd(int x, int z) {
        if (!g.present(x, z) || !g.flag(x, z, ColumnGrid.TUNNEL) || !OverlayPlan.railCentre(g, x, z)) return null;
        int[] out = null;
        for (int[] d : DIRS) {
            if (!OverlayPlan.railCentre(g, x + d[0], z + d[1])) continue;
            if (out != null) return null;
            out = new int[] {-d[0], -d[1]};
        }
        return out;
    }

    /** The 4-connected line {@link #path} would take from (ex, ez) to (tx, tz), both ends excluded, unchecked. */
    private static int[][] staircase(int ex, int ez, int tx, int tz) {
        int n = Math.abs(tx - ex) + Math.abs(tz - ez) - 1;
        int sx = Integer.signum(tx - ex), sz = Integer.signum(tz - ez);
        int[][] p = new int[n][];
        int cx = ex, cz = ez;
        for (int i = 0; i < n; i++) {
            boolean xs;
            if (cx == tx) xs = false;
            else if (cz == tz) xs = true;
            else {
                long ax = Math.abs((long) (cx + sx - ex) * (tz - ez) - (long) (cz - ez) * (tx - ex));
                long az = Math.abs((long) (cx - ex) * (tz - ez) - (long) (cz + sz - ez) * (tx - ex));
                xs = ax <= az;
            }
            if (xs) cx += sx;
            else cz += sz;
            p[i] = new int[] {cx, cz};
        }
        return p;
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
        return tunnel(x, z) && gapKind(x, z) != GAP_RAIL ? deck(x, z) : Math.floorDiv(gap(x, z), 4);
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
        /** Under a cut's moved top: earth under a grown top, the same rock under rock, so a cut face is no bare stone. */
        SUBSOIL,
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
                    for (int y = to - CUT_SOIL; y < to; y++) out.set(y, Part.SUBSOIL);
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
        // a staircase corner moved beside a crossing way's bore (gapSide) lies in the bore, not on the way's deck
        return !OverlayPlan.railUnderBridge(g, x, z) && rail(x, z) && gap(x, z) == NONE && !gapSide(x, z) && !cut(x, z);
    }

    /**
     * A bridge railing on this column: none where the track runs, since a diagonal track's staircase touches the edges
     * of a narrow rail bridge, and a wall there would cut the line. None on a {@link #lowBridge} either: on an
     * embankment a block or two high the wall read as a long retaining wall (playtest 00.07, 28980,-1255,49).
     */
    public boolean railing(int x, int z) {
        return OverlayPlan.railing(g, x, z) && !(railWay(x, z) && trackOnDeck(x, z)) && !lowBridge(x, z);
    }

    /** Most air between the terrain and the beam of a bridge that is built as a low embankment instead. */
    static final int LOW_BRIDGE = 2;

    /**
     * A bridge with nothing under it is built as fill where it runs along the ground: the column has at most
     * {@link #SIDE_HILL_MAX} blocks of air under the beam, and a column of the same bridge within {@link #SIDE_HILL_REACH}
     * along a straight line at most {@link #SIDE_HILL}.
     */
    static final int SIDE_HILL = 5, SIDE_HILL_MAX = 12, SIDE_HILL_REACH = 8;

    /**
     * A bridge column built as an embankment of soil (the earthwork beside it slopes down from its edge): dry, with
     * no rail under it, and either at most {@link #LOW_BRIDGE} blocks of air between terrain and beam, or on a
     * side-hill bridge ({@link #sideHill}). Playtest 00.07 (28980,-1255,49): such a deck showed as a long stone-brick
     * wall on a slope, and then as a beam and railing standing on the downhill half.
     */
    boolean lowBridge(int x, int z) {
        int k = g.index(x, z);
        if (low[k] != UNSET) return low[k] == 1;
        boolean r = !OverlayPlan.railUnderBridge(g, x, z) && wetTop(x, z) == NONE && g.source(x, z) == NONE
                && (deck(x, z) - 2 - g.top(x, z) <= LOW_BRIDGE || sideHill(x, z)); // air between terrain and beam
        low[k] = r ? 1 : 0;
        return r;
    }

    /** Air between the terrain and the beam by the overlay's own deck (no smoothing: it looks far). */
    private int rawAir(int x, int z) {
        return g.deck(x, z) - 2 - g.top(x, z);
    }

    private boolean bridgeColumn(int x, int z) {
        return g.present(x, z) && g.overlay(x, z) != 0 && g.flag(x, z, ColumnGrid.BRIDGE);
    }

    /**
     * On a bridge along a slope with nothing under it: every bridge column within {@link #SIDE_HILL_REACH} is dry,
     * carries no rail under it and meets no lower way or water beside it, and one reached along a straight
     * line of bridge columns lies within {@link #SIDE_HILL} of the terrain. A bridge over a valley, water or a way keeps its beam: across its middle the
     * terrain lies well below the deck; only its ends, near the ground, become fill.
     */
    private boolean sideHill(int x, int z) {
        if (!bridgeColumn(x, z) || g.flag(x, z, ColumnGrid.RAIL) || rawAir(x, z) > SIDE_HILL_MAX) return false; // viaducts keep their beam
        for (int j = -SIDE_HILL_REACH; j <= SIDE_HILL_REACH; j++) {
            for (int i = -SIDE_HILL_REACH; i <= SIDE_HILL_REACH; i++) {
                int cx = x + i, cz = z + j;
                if (!bridgeColumn(cx, cz)) continue;
                if (OverlayPlan.railUnderBridge(g, cx, cz) || g.source(cx, cz) != NONE) return false;
                for (int[] e : DIRS) {
                    int nx = cx + e[0], nz = cz + e[1];
                    if (!g.present(nx, nz) || bridgeColumn(nx, nz)) continue;
                    if (g.source(nx, nz) != NONE) return false; // water beside the footprint runs under it
                    if (g.overlay(nx, nz) != 0 && g.deck(nx, nz) < g.deck(cx, cz) - LOW_BRIDGE - 2) return false; // a way under
                }
            }
        }
        // the ground comes near the deck along the bridge itself, not on another bridge beside it
        for (int[] e : RING) {
            for (int i = 0; i <= SIDE_HILL_REACH; i++) {
                int cx = x + i * e[0], cz = z + i * e[1];
                if (!bridgeColumn(cx, cz)) break;
                if (rawAir(cx, cz) <= SIDE_HILL) return true;
            }
        }
        return false;
    }

    /** A road or rail column outside a tunnel: embankment or cut, culvert roof over water, deck, slab or rail. */
    private void wayColumn(int x, int z, int bottom, Sink out) {
        boolean bridge = g.flag(x, z, ColumnGrid.BRIDGE);
        boolean underBridge = OverlayPlan.railUnderBridge(g, x, z);
        int top = g.top(x, z), deck = deck(x, z);
        int wet = wetTop(x, z);
        boolean road = road(x, z);
        if (bridge && lowBridge(x, z)) {
            // a deck a block or two over dry ground: a low embankment of soil, not a beam standing on the grass
            for (int y = Math.max(top + 1, bottom); y < deck; y++) out.set(y, Part.SOIL);
        } else if (bridge) {
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
        } else if (railing(x, z)) {
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
            // a rail of a crossing way's bore that sits off its floor (a moved staircase corner) keeps its own level below
            boolean own = (!tunnel(x, z) || gapSide(x, z) || gapKind(x, z) == GAP_RAIL) && rail(x, z)
                    && railLevel(x, z) != floor;
            if (!own && (tunnel(x, z) || gapKind(x, z) == GAP_RAIL)) {
                boolean road = road(x, z) && tunnel(x, z);
                out.set(floor, road ? Part.SURFACE : Part.BALLAST);
                if (road && roadSlab(x, z)) out.set(floor + 1, Part.SLAB);
                if (rail(x, z)) out.set(floor + 1, Part.RAIL);
                boolean centre = rail(x, z) || road && !g.flag(x, z, ColumnGrid.EDGE);
                boolean lit = Math.floorMod(g.east(x) + g.north(z), LIGHT_SPACING) == 0 && ceil - 1 > floor + MIN_HEADROOM;
                if (centre && lit) out.set(ceil - 1, Part.LANTERN);
            } else {
                out.set(floor, Part.FLOOR);
                if (rail(x, z)) {
                    // the corner of a track's staircase where a crossing way cut the bore: the rail keeps its own level
                    int lvl = railLevel(x, z);
                    if (lvl >= lo && lvl + 1 < ceil) {
                        for (int y = lvl + 1; y <= floor; y++) out.set(y, Part.AIR);
                        for (int y = floor + 1; y < lvl; y++) out.set(y, Part.FLOOR);
                        if (lvl != floor) out.set(lvl, Part.BALLAST);
                        out.set(lvl + 1, Part.RAIL);
                    }
                }
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
