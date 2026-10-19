package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;

/** The {@code TTOP} tree tables of the cells (swd-format §6a), by cell. */
@FunctionalInterface
public interface TreeData {
    /** No cell has a tree table: statistical placement everywhere. */
    TreeData NONE = (eKm, nKm) -> null;

    /** The tree table of cell (eKm, nKm), or null when the cell has no {@code TTOP} layer (an empty table is not null). */
    TreeTops tops(int eKm, int nKm);

    /** The tables of the cells a chunk's context has loaded. */
    static TreeData of(GenContext ctx) {
        return of(ctx.coords, ctx::cellAt);
    }

    /** The tables of the cells {@code cells} has, by block column. */
    static TreeData of(SwissCoords coords, ColumnGrid.Cells cells) {
        long e0 = coords.east(0), n0 = coords.north(0);
        return (eKm, nKm) -> {
            SwdCell cell = cells.at((int) (eKm * 1000L - e0), (int) (n0 - nKm * 1000L));
            return cell != null && cell.hasTrees() ? new TreeTops(cell.trees()) : null;
        };
    }
}
