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
    private final int[] roadHalf, railBase, railNode, railLevel, floor, ceil, wet;

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
        return way(x, z) && !g.flag(x, z, ColumnGrid.BRIDGE) && !g.flag(x, z, ColumnGrid.TUNNEL);
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
                if (!road(x + i, z + j)) continue;
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
        int b = (int) Math.floor(profile(x, z, true));
        for (int jj = -RAIL_RAMP; jj <= RAIL_RAMP; jj++) {
            for (int ii = -RAIL_RAMP; ii <= RAIL_RAMP; ii++) {
                if (!railWay(x + ii, z + jj)) continue;
                int n = railNeed(x + ii, z + jj);
                if (n != NONE) b = Math.max(b, n - Math.max(Math.abs(ii), Math.abs(jj)));
            }
        }
        return railBase[k] = b;
    }

    // ---------------------------------------------------------------- rail track

    private boolean c0(int x, int z) {
        return OverlayPlan.railCentre(g, x, z);
    }

    /** Touching tracks: of two rail centres side by side, the one west or north keeps its rails. */
    private boolean c1(int x, int z) {
        if (!c0(x, z)) return false;
        boolean n = c0(x, z - 1), s = c0(x, z + 1), e = c0(x + 1, z), w = c0(x - 1, z);
        int deg = (n ? 1 : 0) + (s ? 1 : 0) + (e ? 1 : 0) + (w ? 1 : 0);
        if (deg < 3) return true;
        if (deg == 4) return false;
        // three neighbours: one through pair plus one extra side; drop when the extra side is north or west
        if (e && w) return !n;
        return !w;
    }

    private boolean c2(int x, int z) {
        if (!c1(x, z)) return false;
        int deg = (c1(x, z - 1) ? 1 : 0) + (c1(x, z + 1) ? 1 : 0) + (c1(x + 1, z) ? 1 : 0) + (c1(x - 1, z) ? 1 : 0);
        return deg <= 2;
    }

    /** A rail block goes here: a rail centre with at least one track neighbour, at most two. */
    public boolean rail(int x, int z) {
        int k = g.index(x, z);
        if (railNode[k] == UNSET) {
            boolean r = c2(x, z) && (c2(x, z - 1) || c2(x, z + 1) || c2(x + 1, z) || c2(x - 1, z));
            railNode[k] = r ? 1 : 0;
        }
        return railNode[k] == 1;
    }

    private static final int[][] DIRS = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}}; // N S E W

    /** Bit mask of track neighbours: 1 north, 2 south, 4 east, 8 west. */
    private int links(int x, int z) {
        int m = 0;
        for (int d = 0; d < 4; d++) if (rail(x + DIRS[d][0], z + DIRS[d][1])) m |= 1 << d;
        return m;
    }

    private static boolean curve(int m) {
        return Integer.bitCount(m) == 2 && m != 3 && m != 12;
    }

    /**
     * Level of a rail (its bed block; the rail lies one above). A curve takes the highest base level along its run
     * of curves up to {@link #CHAIN_WALK} steps each way, including the straight rail that ends the run, so the
     * straight rail ascends to the curve. A straight rail between two higher ones is raised to them.
     */
    public int railLevel(int x, int z) {
        int k = g.index(x, z);
        if (railLevel[k] != UNSET) return railLevel[k];
        int lvl = preLevel(x, z);
        int m = links(x, z);
        if (Integer.bitCount(m) == 2 && !curve(m)) {
            int a = -1, b = -1;
            for (int d = 0; d < 4; d++) {
                if ((m & 1 << d) == 0) continue;
                int p = preLevel(x + DIRS[d][0], z + DIRS[d][1]);
                if (a == -1) a = p; else b = p;
            }
            if (a > lvl && b > lvl) lvl = Math.min(a, b);
        }
        return railLevel[k] = lvl;
    }

    private int preLevel(int x, int z) {
        int m = links(x, z);
        if (!curve(m)) return railBase(x, z);
        int lvl = railBase(x, z);
        for (int d = 0; d < 4; d++) {
            if ((m & 1 << d) == 0) continue;
            int px = x, pz = z, cx = x + DIRS[d][0], cz = z + DIRS[d][1];
            for (int step = 1; step <= CHAIN_WALK; step++) {
                lvl = Math.max(lvl, railBase(cx, cz));
                int cm = links(cx, cz);
                if (!curve(cm)) break;
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
        int m = links(x, z);
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
        if (g.present(x, z) && (tunnel(x, z) || !way(x, z))) {
            if (tunnel(x, z)) {
                f = deck(x, z);
                rail = railWay(x, z);
            } else {
                int lo = Integer.MAX_VALUE;
                for (int j = -2; j <= 2; j++) {
                    for (int i = -2; i <= 2; i++) {
                        int nx = x + i, nz = z + j;
                        // one column beside a road, two beside a rail centre: 5 wide
                        boolean near = Math.abs(i) <= 1 && Math.abs(j) <= 1 && road(nx, nz);
                        if (tunnel(nx, nz) && (near || tunnelRailNode(nx, nz))) {
                            lo = Math.min(lo, deck(nx, nz));
                            rail |= railWay(nx, nz);
                        }
                    }
                }
                if (lo != Integer.MAX_VALUE) f = lo;
            }
        }
        floor[k] = f == NONE ? NONE : f * 2 + (rail ? 1 : 0);
        return f;
    }

    /** The highest air block of the profile here. */
    public int tunnelAirTop(int x, int z) {
        int f = tunnelFloor(x, z);
        if (f == NONE) return NONE;
        boolean rail = (floor[g.index(x, z)] & 1) != 0;
        return f + (rail ? RAIL_TUNNEL_HEIGHT : ROAD_TUNNEL_HEIGHT);
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
        return g.present(x, z) && tunnelFloor(x, z) == NONE && !way(x, z) && tunnelHighCeiling(x, z) != NONE;
    }

    /** Part of a tunnel (profile or wall): earthworks leave it alone. */
    public boolean structure(int x, int z) {
        return tunnelFloor(x, z) != NONE || tunnelWall(x, z);
    }

    // ---------------------------------------------------------------- blocks

    /** What goes into a block; {@link OverlayStage} picks the block state. */
    public enum Part {
        AIR, FILL, SOIL, BEAM, LINING, COVER, BALLAST, SURFACE, SLAB, RAIL, RAILING, LANTERN,
        /** The surface block that was on the column's old top (earthworks move it). */
        TOP
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
        } else if (way(x, z)) {
            wayColumn(x, z, out);
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

    /** A road or rail column outside a tunnel: embankment or cut, culvert roof over water, deck, slab or rail. */
    private void wayColumn(int x, int z, Sink out) {
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
            for (int y = Math.max(top, wet) + 1; y < deck; y++) out.set(y, Part.FILL);
        }
        for (int y = deck + 1; y <= top; y++) out.set(y, Part.AIR); // cut
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
        if (OverlayPlan.railing(g, x, z)) {
            out.set(deck + 1, Part.RAILING);
        } else if (road && roadSlab(x, z)) {
            out.set(deck + 1, Part.SLAB);
        } else if (!underBridge && rail(x, z)) {
            out.set(deck + 1, Part.RAIL);
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
            if (way(x, z)) {
                boolean road = road(x, z);
                out.set(floor, road ? Part.SURFACE : Part.BALLAST);
                if (road && roadSlab(x, z)) out.set(floor + 1, Part.SLAB);
                if (rail(x, z)) out.set(floor + 1, Part.RAIL);
                boolean centre = rail(x, z) || road && !g.flag(x, z, ColumnGrid.EDGE);
                if (centre && Math.floorMod(g.east(x) + g.north(z), LIGHT_SPACING) == 0) out.set(ceil - 1, Part.LANTERN);
            } else {
                out.set(floor, Part.LINING);
            }
        }
        if (wetTop(x, z) == NONE && top < hi + 2) {
            for (int y = Math.max(top, hi) + 1; y <= hi + 1; y++) out.set(y, y == hi + 1 ? Part.COVER : Part.SOIL);
        }
    }
}
