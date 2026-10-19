package ch.bissbert.swissworld.cell;

import java.util.List;
import java.util.Map;

/**
 * One decoded 1 km cell. Array layers are indexed {@code dz * 1000 + dx}; TERR is {@code j * 501 + i}.
 * Optional layers are null when absent (also {@code trees}; use {@link #trees()} and {@link #hasTrees()}).
 */
public record SwdCell(
        int eKm,
        int nKm,
        short[] terr,
        byte[] surf,
        short[] wlvl,
        byte[] wknd,
        byte[] ocls,
        short[] oelv,
        byte[] oflg,
        byte[] insd,
        List<Building> buildings,
        Map<Integer, Span[]> spans,
        byte[] cnpy,
        List<Tree> trees) {

    public static final int SIZE = 1000;
    public static final int TERR_N = 501;

    /** Without canopy and trees (cells from before CNPY/TTOP, tests). */
    public SwdCell(int eKm, int nKm, short[] terr, byte[] surf, short[] wlvl, byte[] wknd, byte[] ocls, short[] oelv,
                   byte[] oflg, byte[] insd, List<Building> buildings, Map<Integer, Span[]> spans) {
        this(eKm, nKm, terr, surf, wlvl, wknd, ocls, oelv, oflg, insd, buildings, spans, null, null);
    }

    public static int key(int dx, int dz) {
        return dz * SIZE + dx;
    }

    /** Bilinear ground/bed height in metres at a position inside the cell, in metres from the NW corner. */
    public double terrain(double fx, double fz) {
        double gi = fx / 2.0, gj = fz / 2.0;
        int i = Math.min((int) gi, TERR_N - 2), j = Math.min((int) gj, TERR_N - 2);
        double ti = gi - i, tj = gj - j;
        double a = node(i, j), b = node(i + 1, j), c = node(i, j + 1), d = node(i + 1, j + 1);
        return (a * (1 - ti) + b * ti) * (1 - tj) + (c * (1 - ti) + d * ti) * tj;
    }

    public double node(int i, int j) {
        return Heights.toMetres(terr[j * TERR_N + i] & 0xFFFF);
    }

    public int surf(int dx, int dz) {
        return surf[key(dx, dz)] & 0xFF;
    }

    /** Water surface in metres, NaN if none. */
    public double water(int dx, int dz) {
        return wlvl == null ? Double.NaN : Heights.toMetres(wlvl[key(dx, dz)] & 0xFFFF);
    }

    public int waterKind(int dx, int dz) {
        return wknd == null ? 0 : wknd[key(dx, dz)] & 0xFF;
    }

    public int overlay(int dx, int dz) {
        return ocls == null ? 0 : ocls[key(dx, dz)] & 0xFF;
    }

    /** Overlay top in metres, NaN = follow terrain. */
    public double overlayElevation(int dx, int dz) {
        return oelv == null ? Double.NaN : Heights.toMetres(oelv[key(dx, dz)] & 0xFFFF);
    }

    public int overlayFlags(int dx, int dz) {
        return oflg == null ? 0 : oflg[key(dx, dz)] & 0xFF;
    }

    public boolean inside(int dx, int dz) {
        return insd == null || insd[key(dx, dz)] != 0;
    }

    /** Canopy height in whole metres above TERR (0 = none or below 2 m); 0 when the cell has no CNPY. */
    public int canopy(int dx, int dz) {
        return cnpy == null ? 0 : cnpy[key(dx, dz)] & 0xFF;
    }

    public boolean hasCanopy() {
        return cnpy != null;
    }

    /** True if the cell has TTOP (possibly with no trees): it replaces the statistical placement in this cell (§6a). */
    public boolean hasTrees() {
        return trees != null;
    }

    /** Tree tops whose top column lies in this cell, sorted by (dz, dx); empty without TTOP. */
    @Override
    public List<Tree> trees() {
        return trees == null ? List.of() : trees;
    }

    public Span[] spansAt(int dx, int dz) {
        return spans.get(key(dx, dz));
    }

    public record Building(long gid, long egid, int objektart, int gwrCategory, int gwrClass, int gwrPeriod,
                           int floors, int entranceSide, int baseH, int topH,
                           int minX, int minZ, int maxX, int maxZ) {}

    /**
     * A tree top (format §6a); {@link SwdCell#trees} is sorted by (dz, dx). {@code crownDm} 0 = unknown;
     * {@code kind} 0 unknown, 1 conifer, 2 broadleaf, 3 larch.
     */
    public record Tree(int dx, int dz, int heightM, int crownDm, int kind) {
        public static final int UNKNOWN = 0, CONIFER = 1, BROADLEAF = 2, LARCH = 3;
    }

    /** Heights are u16 dm encoded, see {@link Heights}. */
    public record Span(int bottomH, int topH, int buildingIdx, int part, int roofFlags) {}
}
