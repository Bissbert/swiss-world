package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.gen.GenContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Building spans addressed by absolute block columns. A column in a cell that does not exist (yet) reads as unknown
 * ({@link #cell} returns null).
 */
public record CellView(SwissCoords coords, GenContext.CellLookup cells) {

    /** Lookup by block column, as {@link GenContext#cellAt}. */
    @FunctionalInterface
    public interface ColumnLookup {
        SwdCell at(int x, int z);
    }

    /** The chunk's cells, plus any neighbour a building reaches (see {@link #neighbour}). */
    public static CellView of(GenContext ctx) {
        CellCache cache = SwissRuntime.cells();
        return ofColumns(ctx.coords, ctx::cellAt).orElse((e, n) -> neighbour(cache, e, n));
    }

    /**
     * A cell from memory, else read on this thread: stages may run on the cache's own IO threads, so waiting for
     * {@link CellCache#get} could deadlock. The async load is still started to warm the cache for later chunks.
     */
    static SwdCell neighbour(CellCache cache, int e, int n) {
        SwdCell c = cache.getIfPresent(e, n);
        if (c != null) return c;
        Path f = cache.cellFile(e, n);
        if (!Files.isRegularFile(f)) return null;
        cache.get(e, n);
        try {
            return SwdReader.read(f);
        } catch (IOException ex) {
            return null;
        }
    }

    /** This view, asking {@code more} for the cells it lacks; each answer (null included) is remembered. Not thread-safe. */
    public CellView orElse(GenContext.CellLookup more) {
        Map<Long, Optional<SwdCell>> memo = new HashMap<>();
        return new CellView(coords, (e, n) -> {
            SwdCell c = cells.get(e, n);
            return c != null ? c : memo.computeIfAbsent(((long) e << 32) | (n & 0xFFFFFFFFL),
                    k -> Optional.ofNullable(more.get(e, n))).orElse(null);
        });
    }

    /** Adapts a block-column lookup: each cell is addressed by its NW corner column. */
    public static CellView ofColumns(SwissCoords c, ColumnLookup l) {
        return new CellView(c, (e, n) -> l.at((int) ((long) e * 1000 - c.e0()), (int) (c.n0() - (long) n * 1000 - 1000)));
    }

    /** The cell holding block column (x, z), or null. */
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
