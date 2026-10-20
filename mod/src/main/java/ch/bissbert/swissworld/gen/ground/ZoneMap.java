package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import ch.bissbert.swissworld.gen.surface.ColumnHash;

/**
 * Ground zones of one chunk: a packed {@link ZoneId} per column plus the metrics the texture and snow stages read
 * (height, slope, aspect, hollow, snow depth, distances to water, paths and rails). No Minecraft classes.
 *
 * <p>Every value of a column is a pure function of cell data within {@link #REACH} columns of it (plus TPI samples
 * {@link #TPI_RADIUS} columns around 4-column lattice nodes), all keyed by world position: two chunks that share a
 * border agree on it, whatever order they are generated in. Read-only once built; a shared ground model can serve it
 * the same way later.
 */
public final class ZoneMap {
    /** Window around the chunk the neighbourhood rules look at. */
    public static final int REACH = 8;
    /** TPI ring radius in columns (a ~50 m window). Lattice nodes lie in the chunk, so the reach is 16 + this. */
    public static final int TPI_RADIUS = 24;
    private static final int TPI_STEP = 4;
    private static final int W = 16 + 2 * REACH;
    private static final int SALT_SNOW = 41, SALT_DITHER = 42;
    /** TOPO wind codes (swd-format §6b). */
    private static final int WIND_LEE = 1, WIND_EXPOSED = 2;
    /** Overlay classes of foot and farm paths, whose fringe gets trodden earth. */
    private static final int TRACK = 5, FOOTPATH = 6, RAIL_BED = 8, RACK_BED = 9;
    private static final int DIST_CAP = 7;

    public final int x0, z0;
    private final short[] id = new short[256];
    private final float[] metres = new float[256], slope = new float[256], northness = new float[256], hollow = new float[256];
    private final byte[] snow = new byte[256], waterDist = new byte[256], waterKind = new byte[256], pathDist = new byte[256],
            railDist = new byte[256], downhill = new byte[256];
    private final boolean[] natural = new boolean[256], cliffAbove = new boolean[256];
    private final long[] east = new long[16], north = new long[16];

    private ZoneMap(int x0, int z0) {
        this.x0 = x0;
        this.z0 = z0;
    }

    /** The zones of the chunk with north-west column (x0, z0). */
    public static ZoneMap of(SwissCoords coords, ColumnGrid.Cells cells, int x0, int z0) {
        return compute(new CellGround(coords, cells), x0, z0);
    }

    public static ZoneMap compute(Ground g, int x0, int z0) {
        ZoneMap m = new ZoneMap(x0, z0);
        m.build(g);
        return m;
    }

    private static int idx(int lx, int lz) {
        return lz * 16 + lx;
    }

    public short id(int x, int z) {
        return id[idx(x - x0, z - z0)];
    }

    public Zone zone(int x, int z) {
        return ZoneId.zone(id(x, z));
    }

    public double metres(int x, int z) {
        return metres[idx(x - x0, z - z0)];
    }

    public double slope(int x, int z) {
        return slope[idx(x - x0, z - z0)];
    }

    /** +1 north-facing, -1 south-facing. */
    public double northness(int x, int z) {
        return northness[idx(x - x0, z - z0)];
    }

    /** Downhill direction (smoothed over 8 m) in eighths of a turn: 0 east, 2 south (+z), 4 west, 6 north. */
    public int downhill(int x, int z) {
        return downhill[idx(x - x0, z - z0)];
    }

    /** Metres the column lies below the mean of its neighbours 4 m away (negative on a crest). */
    public double hollow(int x, int z) {
        return hollow[idx(x - x0, z - z0)];
    }

    /** Snow depth in eighths of a block, 0..{@link Snow#MAX}. */
    public int snow(int x, int z) {
        return snow[idx(x - x0, z - z0)];
    }

    /** Columns to the nearest water (capped at 7), and its kind. */
    public int waterDist(int x, int z) {
        return waterDist[idx(x - x0, z - z0)];
    }

    public int waterKind(int x, int z) {
        return waterKind[idx(x - x0, z - z0)];
    }

    /** Columns to the nearest footpath or track (capped at 7). */
    public int pathDist(int x, int z) {
        return pathDist[idx(x - x0, z - z0)];
    }

    /** Columns to the nearest rail bed (capped at 7). */
    public int railDist(int x, int z) {
        return railDist[idx(x - x0, z - z0)];
    }

    public boolean cliffAbove(int x, int z) {
        return cliffAbove[idx(x - x0, z - z0)];
    }

    /**
     * Natural ground: no water, building or surface overlay on the column (a tunnel deep enough below counts as
     * natural). Only these columns are textured or snowed.
     */
    public boolean natural(int x, int z) {
        return natural[idx(x - x0, z - z0)];
    }

    public long east(int x) {
        return east[x - x0];
    }

    public long north(int z) {
        return north[z - z0];
    }

    /** Snow-covered share of a 4x4 quart (columns with any snow), for the biome. */
    public int snowyColumns(int qx, int qz) {
        int count = 0;
        for (int dz = 0; dz < 4; dz++) {
            for (int dx = 0; dx < 4; dx++) if (snow[idx(qx + dx, qz + dz)] > 0) count++;
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------------ building

    private static int w(int i, int j) {
        return j * W + i;
    }

    private void build(Ground g) {
        int n = W * W;
        boolean[] present = new boolean[n], wet = new boolean[n], forest = new boolean[n], glacier = new boolean[n];
        boolean[] path = new boolean[n], rail = new boolean[n];
        int[] kind = new int[n];
        double[] h = new double[n];
        int wx0 = x0 - REACH, wz0 = z0 - REACH;
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                int x = wx0 + i, z = wz0 + j, k = w(i, j);
                present[k] = g.present(x, z);
                double m = g.metres(x, z);
                h[k] = Double.isNaN(m) ? 193 : m;
                if (!present[k]) continue;
                int surf = g.surf(x, z);
                wet[k] = g.wet(x, z);
                kind[k] = g.waterKind(x, z);
                forest[k] = surf == 1 || surf == 2 || surf == 3;
                glacier[k] = surf == 6;
                int ocls = g.overlay(x, z), flags = g.flags(x, z);
                path[k] = (ocls == TRACK || ocls == FOOTPATH) && Double.isNaN(g.overlayMetres(x, z));
                rail[k] = ocls == RAIL_BED || ocls == RACK_BED || (flags & ColumnGrid.RAIL) != 0;
                rail[k] &= (flags & (ColumnGrid.BRIDGE | ColumnGrid.TUNNEL)) == 0;
            }
        }
        // slope (degrees) over the window but its outer ring, central differences 2 m apart
        double[] slopeW = new double[n];
        for (int j = 1; j < W - 1; j++) {
            for (int i = 1; i < W - 1; i++) {
                double gx = (h[w(i + 1, j)] - h[w(i - 1, j)]) / 2, gz = (h[w(i, j + 1)] - h[w(i, j - 1)]) / 2;
                slopeW[w(i, j)] = Math.toDegrees(Math.atan(Math.hypot(gx, gz)));
            }
        }
        double[] tpi = tpiLattice(g);
        boolean anySteep = false;
        for (int q = 0; q < n; q++) anySteep |= present[q] && slopeW[q] >= 45;
        // highest steep (45 degrees+) column within 6 columns (a 13 x 13 square), separable: rows, then columns
        double[] steepAbove = null;
        if (anySteep) {
            double[] rowMax = new double[n];
            java.util.Arrays.fill(rowMax, Double.NEGATIVE_INFINITY);
            for (int j = REACH - 6; j < REACH + 22; j++) {
                for (int i = REACH; i < REACH + 16; i++) {
                    double m = Double.NEGATIVE_INFINITY;
                    for (int di = -6; di <= 6; di++) {
                        int q = w(i + di, j);
                        if (present[q] && slopeW[q] >= 45 && h[q] > m) m = h[q];
                    }
                    rowMax[w(i, j)] = m;
                }
            }
            steepAbove = new double[256];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    double m = Double.NEGATIVE_INFINITY;
                    for (int dj = -6; dj <= 6; dj++) m = Math.max(m, rowMax[w(lx + REACH, lz + REACH + dj)]);
                    steepAbove[idx(lx, lz)] = m;
                }
            }
        }
        boolean[] open = new boolean[n];
        for (int q = 0; q < n; q++) open[q] = present[q] && !forest[q];
        int[] nearWAt = new int[256];
        int[] nearW = near(wet, present, nearWAt), nearG = near(glacier, present, null), nearO = near(open, present, null);
        int[] nearF = near(forest, present, null), nearP = near(path, present, null), nearR = near(rail, present, null);
        for (int lx = 0; lx < 16; lx++) east[lx] = g.east(x0 + lx);
        for (int lz = 0; lz < 16; lz++) north[lz] = g.north(z0 + lz);

        GroundZones.Input in = new GroundZones.Input();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz, i = lx + REACH, j = lz + REACH, k = w(i, j), c = idx(lx, lz);
                long e = east[lx], nn = north[lz];
                metres[c] = (float) h[k];
                if (!present[k]) {
                    id[c] = ZoneId.pack(Region.UNKNOWN, Belt.UNKNOWN, Zone.NONE, Lith.UNKNOWN);
                    continue;
                }
                double gx = (h[w(i + 1, j)] - h[w(i - 1, j)]) / 2, gz = (h[w(i, j + 1)] - h[w(i, j - 1)]) / 2;
                double grad = Math.hypot(gx, gz);
                double s = slopeW[k];
                // z grows to the south: ground rising southwards faces north
                double north = grad < 1e-6 ? 0 : gz / grad;
                double hol = (h[w(i + 4, j)] + h[w(i - 4, j)] + h[w(i, j + 4)] + h[w(i, j - 4)]) / 4 - h[k];
                slope[c] = (float) s;
                northness[c] = (float) north;
                hollow[c] = (float) hol;
                // the zones see the slope over 8 m, smoothing out the metre-scale roughness of the terrain model
                double wx = (h[w(i + 4, j)] - h[w(i - 4, j)]) / 8, wz = (h[w(i, j + 4)] - h[w(i, j - 4)]) / 8;
                double wide = Math.toDegrees(Math.atan(Math.hypot(wx, wz)));
                // downhill direction in eighths of a turn, 0 = east, 2 = south (+z)
                downhill[c] = (byte) (Math.hypot(wx, wz) < 1e-6 ? 0 : Math.floorMod(Math.round(Math.atan2(-wz, -wx) / (Math.PI / 4)), 8));

                int ocls = g.overlay(x, z), flags = g.flags(x, z);
                boolean overlay = ocls != 0 || flags != 0;
                if (overlay && (flags & ColumnGrid.TUNNEL) != 0) {
                    double o = g.overlayMetres(x, z);
                    if (!Double.isNaN(o) && ColumnGrid.y(o) + 10 < ColumnGrid.y(h[k])) overlay = false;
                }
                boolean building = g.building(x, z);
                natural[c] = !wet[k] && !overlay && !building;

                // neighbourhood within REACH (exact squared distances, see near())
                int wd2 = nearW[c], gd2 = nearG[c], od2 = nearO[c], fd2 = nearF[c], pd2 = nearP[c], rd2 = nearR[c];
                int wk = wd2 == Integer.MAX_VALUE ? 0 : kind[nearWAt[c]];
                boolean cliff = steepAbove != null && steepAbove[c] > h[k] + 3;
                int wd = dist(wd2);
                waterDist[c] = (byte) Math.min(DIST_CAP, wd);
                waterKind[c] = (byte) wk;
                pathDist[c] = (byte) Math.min(DIST_CAP, dist(pd2));
                railDist[c] = (byte) Math.min(DIST_CAP, dist(rd2));
                cliffAbove[c] = cliff;

                int surf = g.surf(x, z);
                // sidecar-zones layers where the cell has them (0 / -1 = unknown), else the fallbacks
                int rid = g.region(x, z);
                Region region = rid > 0 ? Region.byId(rid) : Region.of(e, nn);
                Lith lith = Lith.byId(g.lithology(x, z));
                if (lith == Lith.UNKNOWN) lith = Lith.guess(e, nn, h[k], region);
                int topo = g.topo(x, z);
                double t = tpiAt(tpi, lx, lz);
                int wind = 0;
                if (topo >= 0) {
                    // the 50 m TPI class of the 2 m terrain replaces the local estimate's verdict
                    t = switch (topo & 3) {
                        case 3 -> Math.max(t, 5);
                        case 1 -> Math.min(t, -5);
                        default -> Math.max(-2, Math.min(2, t));
                    };
                    wind = topo >>> 6 & 3;
                }
                // drift: lee slopes collect snow (deeper, lower line), exposed ridges are blown bare
                double lee = wind == WIND_LEE ? hol + 3 : hol;
                double line0 = ClimateLines.snowLine(e, nn), tree = ClimateLines.treeline(e, nn);
                double south = Math.max(0, -north) * Math.min(1, s / 20);
                Belt belt = Belt.of(h[k], tree, line0, south);

                double jitter = (Noise.value(e, nn, 48, SALT_SNOW) - 0.5) * 120;
                double line = Snow.line(line0, s, north, lee, jitter);
                if (wind == WIND_EXPOSED) line += 80;
                double depth = Snow.depth(h[k], line, s, lee);
                if (wind == WIND_EXPOSED) depth *= 0.6;
                int surfForest = surf == 1 || surf == 2 || surf == 3 ? 1 : 0;
                if (surfForest == 1) depth *= 0.6; // crowns catch part of it
                int eighths = Snow.eighths(depth, ColumnHash.unit(e, nn, SALT_DITHER));
                if (!natural[c] && !(surf == 6 && !wet[k])) eighths = 0;

                in.wet = wet[k];
                in.building = building;
                in.surf = surf;
                in.waterKind = kind[k];
                in.overlay = ocls;
                in.flags = flags;
                in.h = h[k];
                in.slope = wide;
                in.northness = north;
                in.hollow = hol;
                in.tpi = t;
                in.canopy = g.canopy(x, z);
                in.waterDist = wd;
                in.nearWaterKind = wk;
                in.glacierDist = dist(gd2);
                in.openDist = dist(od2);
                in.forestDist = dist(fd2);
                in.cliffAbove = cliff;
                in.region = region;
                in.belt = belt;
                in.lith = lith;
                in.treeline = tree;
                in.snowLine = line0;
                in.snow = eighths;
                in.flow = topo >= 0 ? topo >>> 4 & 3 : -1;
                in.habitat = g.habitat(x, z);
                Zone zone = GroundZones.zone(in);
                if (lith == Lith.UNKNOWN) lith = Lith.ofCliff(zone);
                snow[c] = (byte) eighths;
                id[c] = ZoneId.pack(region, belt, zone, lith);
            }
        }
    }

    /**
     * Squared distance from each chunk column to the nearest {@code on} column within {@code REACH - 1} (else
     * MAX_VALUE), exact: the nearest in each row first, then over rows. {@code at} receives the window index of that
     * column; ties go to the first row from the north, then the nearer side west. Pure in relative offsets, so every
     * window agrees.
     */
    private static int[] near(boolean[] on, boolean[] present, int[] at) {
        final int R = REACH - 1, INF = Integer.MAX_VALUE;
        // per window cell: offset to the nearest 'on' cell in its row within R (west first on ties), or none
        int[] rowOff = new int[W * W];
        boolean any = false;
        for (int j = 0; j < W; j++) {
            int last = Integer.MIN_VALUE / 2;
            for (int i = 0; i < W; i++) {
                if (on[w(i, j)] && present[w(i, j)]) last = i;
                rowOff[w(i, j)] = i - last <= R ? last - i : INF;
            }
            last = Integer.MAX_VALUE / 2;
            for (int i = W - 1; i >= 0; i--) {
                if (on[w(i, j)] && present[w(i, j)]) last = i;
                int k = w(i, j), west = rowOff[k];
                // west wins ties
                if (last - i <= R && (west == INF || last - i < -west)) rowOff[k] = last - i;
                any |= rowOff[k] != INF;
            }
        }
        int[] out = new int[256];
        java.util.Arrays.fill(out, INF);
        if (!any) return out;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lx + REACH, j = lz + REACH, best = INF, bestAt = -1;
                for (int dj = -R; dj <= R; dj++) {
                    int o = rowOff[w(i, j + dj)];
                    if (o == INF) continue;
                    int d2 = o * o + dj * dj;
                    if (d2 <= R * R && d2 < best) {
                        best = d2;
                        bestAt = w(i + o, j + dj);
                    }
                }
                out[idx(lx, lz)] = best;
                if (at != null && bestAt >= 0) at[idx(lx, lz)] = bestAt;
            }
        }
        return out;
    }

    private static int dist(int d2) {
        return d2 == Integer.MAX_VALUE ? GroundZones.FAR : (int) Math.round(Math.sqrt(d2));
    }

    /** TPI at the 5x5 lattice nodes of the chunk (every 4 columns, world-aligned since chunk origins are). */
    private double[] tpiLattice(Ground g) {
        double[] t = new double[25];
        double r = TPI_RADIUS, d = TPI_RADIUS * Math.sqrt(0.5);
        int[][] ring = {{(int) r, 0}, {-(int) r, 0}, {0, (int) r}, {0, -(int) r},
                {(int) d, (int) d}, {-(int) d, (int) d}, {(int) d, -(int) d}, {-(int) d, -(int) d}};
        for (int b = 0; b < 5; b++) {
            for (int a = 0; a < 5; a++) {
                int x = x0 + a * TPI_STEP, z = z0 + b * TPI_STEP;
                double c = g.metres(x, z);
                if (Double.isNaN(c)) continue;
                double sum = 0;
                int cnt = 0;
                for (int[] o : ring) {
                    double v = g.metres(x + o[0], z + o[1]);
                    if (Double.isNaN(v)) continue;
                    sum += v;
                    cnt++;
                }
                t[b * 5 + a] = cnt == 0 ? 0 : c - sum / cnt;
            }
        }
        return t;
    }

    private static double tpiAt(double[] t, int lx, int lz) {
        int a = lx / TPI_STEP, b = lz / TPI_STEP;
        double fa = (lx % TPI_STEP) / (double) TPI_STEP, fb = (lz % TPI_STEP) / (double) TPI_STEP;
        double p = t[b * 5 + a] + (t[b * 5 + a + 1] - t[b * 5 + a]) * fa;
        double q = t[(b + 1) * 5 + a] + (t[(b + 1) * 5 + a + 1] - t[(b + 1) * 5 + a]) * fa;
        return p + (q - p) * fb;
    }
}
