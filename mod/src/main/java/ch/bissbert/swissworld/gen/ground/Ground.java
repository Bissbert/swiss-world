package ch.bissbert.swissworld.gen.ground;

/**
 * Read-only cell data the ground classifier needs, per block column. No Minecraft classes. Today {@link CellGround}
 * answers it from the cells of a {@code ColumnGrid}; a shared ground model (mod-siteplan's SitePlan) can implement it instead.
 *
 * <p>The optional layers of sidecar-zones (REGN, LITH, TOPO, HAB) have defaults meaning "unknown"; the classifier then
 * falls back to a coarse E/N lookup (region), a positional guess (lithology) and local TERR metrics (TPI, hollow). Old
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

    // ---- optional layers (sidecar-zones, swd-format §6b); "unknown" by default and wherever a cell lacks them

    /** {@link Region} id 1..6 (REGN), 0 unknown. */
    default int region(int x, int z) {
        return 0;
    }

    /** {@link Lith} id 0..5 (LITH class), 0 unknown. */
    default int lithology(int x, int z) {
        return 0;
    }

    /** Raw TOPO byte (TPI 50 m bits 0-1, TPI 300 m bits 2-3, flow bits 4-5, wind bits 6-7), -1 without TOPO. */
    default int topo(int x, int z) {
        return -1;
    }

    /** Raw HABT byte (raised bog 0x01, bog margin 0x02, fen 0x04, Aue type bits 3-5), -1 without HABT. */
    default int habitat(int x, int z) {
        return -1;
    }
}
