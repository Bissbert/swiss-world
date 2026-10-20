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
    /** Per-chunk results shared by stages (and the biome resolver), see {@link #memo}. */
    private final java.util.Map<Class<?>, Object> memo = new java.util.HashMap<>(4);

    public GenContext(ChunkAccess chunk, SwissCoords coords, CellLookup cells) {
        this.chunk = chunk;
        this.coords = coords;
        this.cells = cells;
    }

    /**
     * The cell containing block column (x, z), or null if there is no cell (ocean, or not built yet).
     * Local indices: {@code SwissCoords.dx(coords.east(x))}, {@code SwissCoords.dz(coords.north(z))}.
     * Sample {@link SwdCell#terrain} at column centres: {@code terrain(dx + 0.5, dz + 0.5)}.
     * Real height h becomes y via {@link SwissCoords#toY}; a column is solid up to {@code floor(toY(h))} inclusive.
     */
    public SwdCell cellAt(int x, int z) {
        return cells.get(SwissCoords.cellKm(coords.east(x)), SwissCoords.cellKm(coords.north(z)));
    }

    /**
     * A value computed once per chunk and shared by everyone who asks for {@code key}: e.g. the ground zones, which the
     * biome resolver, the texture stage and the snow stage all read. Single-threaded, like the pipeline itself.
     */
    public <T> T memo(Class<T> key, java.util.function.Supplier<T> compute) {
        Object v = memo.get(key);
        if (v == null) {
            v = compute.get();
            memo.put(key, v);
        }
        return key.cast(v);
    }

    @FunctionalInterface
    public interface CellLookup {
        SwdCell get(int eKm, int nKm);
    }
}
