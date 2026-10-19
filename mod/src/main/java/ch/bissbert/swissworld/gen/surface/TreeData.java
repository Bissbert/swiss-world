package ch.bissbert.swissworld.gen.surface;

/**
 * The optional vegetation layers of a cell, {@code TTOP} and {@code CNPY} (swd-format §3, §6a). Until
 * {@code SwdCell} carries them, the game uses {@link #NONE}, so every cell keeps the statistical tree placement.
 */
public interface TreeData {
    /** No cell has trees or canopy data. */
    TreeData NONE = new TreeData() {
        @Override
        public TreeTops tops(int eKm, int nKm) {
            return null;
        }

        @Override
        public byte[] canopy(int eKm, int nKm) {
            return null;
        }
    };

    /** The tree table of cell (eKm, nKm), or null when the cell has no {@code TTOP} layer. */
    TreeTops tops(int eKm, int nKm);

    /** The {@code CNPY} layer of cell (eKm, nKm) as parsed by {@link TreeTops#parseCanopy}, or null when absent. */
    byte[] canopy(int eKm, int nKm);
}
