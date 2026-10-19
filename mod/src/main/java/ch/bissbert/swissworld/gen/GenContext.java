package ch.bissbert.swissworld.gen;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Everything a stage needs for one chunk. All cells the chunk touches are loaded before the pipeline runs.
 * Stages must be deterministic (same cells + origin -> same blocks) and must not block.
 */
public final class GenContext {
    public final ChunkAccess chunk;
    public final SwissCoords coords;
    private final CellLookup cells;

    public GenContext(ChunkAccess chunk, SwissCoords coords, CellLookup cells) {
        this.chunk = chunk;
        this.coords = coords;
        this.cells = cells;
    }

    /** The cell containing block column (x, z), or null if outside coverage (ocean). */
    public SwdCell cellAt(int x, int z) {
        return cells.get(SwissCoords.cellKm(coords.east(x)), SwissCoords.cellKm(coords.north(z)));
    }

    @FunctionalInterface
    public interface CellLookup {
        SwdCell get(int eKm, int nKm);
    }
}
