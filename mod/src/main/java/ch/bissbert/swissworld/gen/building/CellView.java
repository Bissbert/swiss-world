package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;

/**
 * Building spans addressed by absolute block columns. Only the cells the generator loaded for the chunk are visible;
 * a column in any other cell reads as unknown ({@link #cell} returns null).
 */
public record CellView(SwissCoords coords, GenContext.CellLookup cells) {

    public static CellView of(GenContext ctx) {
        return new CellView(ctx.coords, (e, n) -> ctx.cellAt(e, n));
    }

    /** The cell holding block column (x, z), or null. Note: unlike GenContext, this takes block coordinates. */
    public SwdCell cell(int x, int z) {
        return cells.get(SwissCoords.cellKm(coords.east(x)), SwissCoords.cellKm(coords.north(z)));
    }

    public SwdCell.Span[] spans(SwdCell cell, int x, int z) {
        return cell.spansAt(SwissCoords.dx(coords.east(x)), SwissCoords.dz(coords.north(z)));
    }

    /** Ground height in metres at the centre of column (x, z), NaN without a cell. */
    public double terrain(int x, int z) {
        SwdCell c = cell(x, z);
        return c == null ? Double.NaN
                : c.terrain(SwissCoords.dx(coords.east(x)) + 0.5, SwissCoords.dz(coords.north(z)) + 0.5);
    }

    /** Block x of a cell-relative column dx. */
    public int blockX(SwdCell cell, int dx) {
        return (int) ((long) cell.eKm() * 1000 - coords.e0()) + dx;
    }

    /** Block z of a cell-relative column dz. */
    public int blockZ(SwdCell cell, int dz) {
        return (int) (coords.n0() - (long) cell.nKm() * 1000 - 1000) + dz;
    }
}
