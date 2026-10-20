package ch.bissbert.swissworld.gen.ground;

/**
 * Read-only cell data the ground classifier needs, per block column. No Minecraft classes. Today {@link CellGround}
 * answers it from the cells of a {@code ColumnGrid}; a shared ground model (mod-siteplan's SitePlan) can implement it instead.
 *
 * <p>The optional layers of sidecar-zones (REGN, LITH, TOPO, HAB) have defaults meaning "unknown"; the classifier then
 * falls back to a coarse E/N lookup (region), generic rock (lithology) and local TERR metrics (TPI, flow, lee). Old
 * cells without those layers keep working.
 */
public interface Ground {
    int NO_WATER = 0;

    /** Inside Switzerland with a loaded cell. */
    boolean present(int x, int z);

    /** Terrain height in metres (TERR at the column centre). */
    double metres(int x, int z);

    /** SURF class (swd-format §4). */
    int surf(int x, int z);

    /** A water source stands on the column (lake, river, ...). */
    boolean wet(int x, int z);

    /** WKND (swd-format §3): 0 none, 1 lake, 2 river, 3 stream, 4 reservoir, 5 pond, 6 waterfall. */
    int waterKind(int x, int z);

    /** Water surface in metres where {@link #wet}, else NaN. */
    double waterMetres(int x, int z);

    /** OCLS (swd-format §5), 0 none. */
    int overlay(int x, int z);

    /** OFLG bits. */
    int flags(int x, int z);

    /** OELV in metres (bridge deck, tunnel floor, ...), or NaN. */
    double overlayMetres(int x, int z);

    /** A building span stands on the column. */
    boolean building(int x, int z);

    /** CNPY in metres, or -1 when the cell has no canopy layer. */
    int canopy(int x, int z);

    long east(int x);

    long north(int z);

    // ---- optional layers (sidecar-zones); unknown by default

    /** {@link Region} ordinal 1..6, or -1 unknown (REGN). */
    default int region(int x, int z) {
        return -1;
    }

    /** {@link Lith} ordinal, or -1 unknown (LITH). */
    default int lithology(int x, int z) {
        return -1;
    }

    /** Topographic position index at ~50 m in metres (positive = ridge), or NaN unknown (TOPO). */
    default double tpi(int x, int z) {
        return Double.NaN;
    }

    /** Flow accumulation in log2(upslope cells), or NaN unknown (TOPO). */
    default double flow(int x, int z) {
        return Double.NaN;
    }

    /** Wind exposure, -1 lee .. +1 exposed, or NaN unknown (TOPO). */
    default double exposure(int x, int z) {
        return Double.NaN;
    }

    /** Habitat hint (HAB), -1 unknown. */
    default int habitat(int x, int z) {
        return -1;
    }
}
