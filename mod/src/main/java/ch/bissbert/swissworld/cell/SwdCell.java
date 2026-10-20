package ch.bissbert.swissworld.cell;

import java.util.List;
import java.util.Map;

/**
 * One decoded 1 km cell. Array layers are indexed {@code dz * 1000 + dx}; TERR is {@code j * 501 + i}.
 * Optional layers are null when absent (also {@code trees}; use {@link #trees()} and {@link #hasTrees()}).
 * The zone inputs REGN/LITH/TOPO/HABT (format §6b) read as 0 when absent: unknown region and lithology,
 * mid-slope / no flow / neutral wind, no habitat.
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
        List<Tree> trees,
        byte[] regn,
        byte[] lith,
        byte[] topo,
        byte[] habt) {

    public static final int SIZE = 1000;
    public static final int TERR_N = 501;

    /** Without canopy and trees (cells from before CNPY/TTOP, tests). */
    public SwdCell(int eKm, int nKm, short[] terr, byte[] surf, short[] wlvl, byte[] wknd, byte[] ocls, short[] oelv,
                   byte[] oflg, byte[] insd, List<Building> buildings, Map<Integer, Span[]> spans) {
        this(eKm, nKm, terr, surf, wlvl, wknd, ocls, oelv, oflg, insd, buildings, spans, null, null);
    }

    /** Without the zone inputs (cells from before REGN/LITH/TOPO/HABT, tests). */
    public SwdCell(int eKm, int nKm, short[] terr, byte[] surf, short[] wlvl, byte[] wknd, byte[] ocls, short[] oelv,
                   byte[] oflg, byte[] insd, List<Building> buildings, Map<Integer, Span[]> spans, byte[] cnpy,
                   List<Tree> trees) {
        this(eKm, nKm, terr, surf, wlvl, wknd, ocls, oelv, oflg, insd, buildings, spans, cnpy, trees,
                null, null, null, null);
    }

    // ---- REGN (§6b): region bits 0-2, subregion digit bits 3-5, 0x40 = filled from the nearest region
    public static final int REGION_JURA = 1, REGION_MITTELLAND = 2, REGION_ALPS_NORTH = 3, REGION_ALPS_WEST = 4,
            REGION_ALPS_EAST = 5, REGION_ALPS_SOUTH = 6;
    public static final int REGN_FILLED = 0x40;

    // ---- LITH (§6b): class bits 0-2, 0x08 glacier (rock = nearest), 0x10 lake, detail bits 5-7
    public static final int LITH_UNKNOWN = 0, LITH_LIMESTONE = 1, LITH_CRYSTALLINE = 2, LITH_SCHIST = 3,
            LITH_MOLASSE = 4, LITH_QUATERNARY = 5;
    public static final int LITH_GLACIER = 0x08, LITH_LAKE = 0x10;

    // ---- TOPO (§6b): TPI50 bits 0-1, TPI300 bits 2-3, flow bits 4-5, wind bits 6-7
    public static final int TPI_MID = 0, TPI_VALLEY = 1, TPI_FLAT = 2, TPI_RIDGE = 3;
    public static final int FLOW_NONE = 0, FLOW_GULLY = 1, FLOW_CHANNEL = 2, FLOW_HOLLOW = 3;
    public static final int WIND_NEUTRAL = 0, WIND_LEE = 1, WIND_EXPOSED = 2;

    // ---- HABT (§6b): 0x01 raised bog, 0x02 bog margin, 0x04 fen, Aue type (1-5) in bits 3-5
    public static final int HAB_RAISED_BOG = 0x01, HAB_BOG_MARGIN = 0x02, HAB_FEN = 0x04;
    public static final int AUE_GLACIER_FOREFIELD = 1, AUE_ALPINE_PLAIN = 2, AUE_RIVER = 3, AUE_DELTA = 4,
            AUE_LAKE_SHORE = 5;

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

    private static int u8(byte[] a, int dx, int dz) {
        return a == null ? 0 : a[key(dx, dz)] & 0xFF;
    }

    /** Raw REGN byte (0 without REGN). */
    public int regn(int dx, int dz) {
        return u8(regn, dx, dz);
    }

    /** Biogeographic region 1-6 ({@code REGION_*}), 0 = unknown. */
    public int region(int dx, int dz) {
        return regn(dx, dz) & 7;
    }

    /** Raw LITH byte (0 without LITH). */
    public int lith(int dx, int dz) {
        return u8(lith, dx, dz);
    }

    /** Lithology class ({@code LITH_*} 0-5). */
    public int lithClass(int dx, int dz) {
        return lith(dx, dz) & 7;
    }

    /** Class-specific detail 0-7 (format §6b). */
    public int lithDetail(int dx, int dz) {
        return lith(dx, dz) >>> 5;
    }

    /** Raw TOPO byte (0 = mid-slope, no flow, neutral wind; also without TOPO). */
    public int topo(int dx, int dz) {
        return u8(topo, dx, dz);
    }

    public int tpi50(int dx, int dz) {
        return topo(dx, dz) & 3;
    }

    public int tpi300(int dx, int dz) {
        return topo(dx, dz) >>> 2 & 3;
    }

    public int flow(int dx, int dz) {
        return topo(dx, dz) >>> 4 & 3;
    }

    public int wind(int dx, int dz) {
        return topo(dx, dz) >>> 6 & 3;
    }

    /** Raw HABT byte (0 without HABT). */
    public int habitat(int dx, int dz) {
        return u8(habt, dx, dz);
    }

    /** Aue (floodplain) type 1-5 ({@code AUE_*}), 0 = none. */
    public int aue(int dx, int dz) {
        return habitat(dx, dz) >>> 3 & 7;
    }

    public boolean hasZones() {
        return regn != null && lith != null;
    }

    public boolean hasTopo() {
        return topo != null;
    }

    public boolean hasHabitats() {
        return habt != null;
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
