package ch.bissbert.swissworld.gen.site;

import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.building.Buildings;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.LongAdder;

/**
 * The site plans of the tiles being generated: requested once per tile, computed once, shared by every chunk of it.
 * Owner: mod-siteplan.
 *
 * <p>A chunk asks {@link #forChunk}: the cells under its tile plus {@link SitePlan#MARGIN} are requested from the
 * {@link CellCache} (asynchronously, nothing blocks), then the cells the buildings near the tile reach, then the plan is
 * computed on the worldgen executor. Every chunk of the tile gets the same future. Plans are kept in an LRU cache of
 * {@code -Dswissworld.siteplan.cache} tiles (default {@value #DEFAULT_CACHE}), keyed by the tile and by which of its
 * cells exist, so a plan made without a cell is never used once that cell is there.
 */
public final class SitePlans {
    static final int DEFAULT_CACHE = 24;
    static final int CACHE = Math.max(1, Integer.getInteger("swissworld.siteplan.cache", DEFAULT_CACHE));
    /**
     * Columns around a building's bbox whose cells its plan and way out read: {@link Buildings#MARGIN}, the longest way out
     * and its check for calm ground beyond, the cut beside it, the facade's look for a road in front.
     */
    public static final int BUILDING_REACH = 48;

    record Key(int tx, int tz, long present) {}

    private static final Map<Key, CompletableFuture<SitePlan>> PLANS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, CompletableFuture<SitePlan>> eldest) {
            return size() > CACHE;
        }
    };
    private static final LongAdder MISSES = new LongAdder();

    private SitePlans() {}

    /** Cell lookups that fell outside the cells a plan loaded (should stay 0: every read is requested up front). */
    public static long misses() {
        return MISSES.sum();
    }

    /**
     * The plan of the tile holding block column (x, z), once its cells are there; completes with null when a cell
     * request was aborted (shutdown, stall: the chunk becomes a placeholder). Never blocks.
     */
    public static CompletableFuture<SitePlan> forColumn(CellCache cache, SwissCoords coords, int x, int z, Executor exec) {
        int tx = SitePlan.tile(x), tz = SitePlan.tile(z);
        Map<Long, CompletableFuture<CellCache.Result>> tile = request(cache, coords, tileBox(tx, tz), new HashMap<>());
        return all(tile).thenCompose(v -> {
            Map<Long, SwdCell> cells = loaded(tile);
            if (cells == null) return CompletableFuture.completedFuture(null);
            Key key = new Key(tx, tz, presence(tile));
            CompletableFuture<SitePlan> f;
            boolean mine = false;
            synchronized (PLANS) {
                f = PLANS.get(key);
                if (f == null) {
                    PLANS.put(key, f = new CompletableFuture<>());
                    mine = true;
                }
            }
            if (mine) start(f, key, cache, coords, tile, cells, exec);
            return f;
        });
    }

    private static void start(CompletableFuture<SitePlan> f, Key key, CellCache cache, SwissCoords coords,
                              Map<Long, CompletableFuture<CellCache.Result>> tile, Map<Long, SwdCell> cells, Executor exec) {
        Map<Long, CompletableFuture<CellCache.Result>> more = new HashMap<>(tile);
        for (int[] box : buildingBoxes(coords, cells, key.tx, key.tz)) request(cache, coords, box, more);
        all(more).thenApplyAsync(v -> {
            Map<Long, SwdCell> all = loaded(more);
            if (all == null) return null;
            return compute(lookup(all, more.keySet(), cache), coords, key.tx, key.tz);
        }, exec).whenComplete((plan, err) -> {
            if (plan == null || err != null) {
                synchronized (PLANS) {
                    PLANS.remove(key, f); // ask again next time
                }
            }
            if (err != null) f.completeExceptionally(err);
            else f.complete(plan);
        });
    }

    /** The cells a plan reads: those it requested; any other is a miss (counted, answered from memory if there). */
    private static GenContext.CellLookup lookup(Map<Long, SwdCell> cells, java.util.Set<Long> known, CellCache cache) {
        return (e, n) -> {
            long k = CellCache.key(e, n);
            SwdCell c = cells.get(k);
            if (c != null || known.contains(k)) return c;
            MISSES.increment();
            return cache.getIfPresent(e, n);
        };
    }

    /** Computes the plan of tile (tx, tz) on this thread from {@code cells} (tests, tools: any lookup). */
    public static SitePlan compute(GenContext.CellLookup cells, SwissCoords coords, int tx, int tz) {
        SitePlan p = new SitePlan(coords, cells, tx, tz);
        GenStats.record(GenStats.PLAN, p.nanos());
        return p;
    }

    /** Block box x0, z0, x1, z1 of the tile plus its margin. */
    static int[] tileBox(int tx, int tz) {
        int x0 = tx * SitePlan.TILE, z0 = tz * SitePlan.TILE;
        return new int[] {x0 - SitePlan.MARGIN, z0 - SitePlan.MARGIN, x0 + SitePlan.TILE - 1 + SitePlan.MARGIN,
                z0 + SitePlan.TILE - 1 + SitePlan.MARGIN};
    }

    /**
     * Boxes of the buildings the tile's stages and claims see (bbox within {@link SitePlan#MARGIN} of the tile), each
     * grown by {@link #BUILDING_REACH}.
     */
    static java.util.List<int[]> buildingBoxes(SwissCoords coords, Map<Long, SwdCell> cells, int tx, int tz) {
        int[] t = tileBox(tx, tz);
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (SwdCell cell : cells.values()) {
            if (cell.buildings() == null) continue;
            int cx = (int) ((long) cell.eKm() * 1000 - coords.e0()), cz = (int) (coords.n0() - (long) cell.nKm() * 1000 - 1000);
            for (SwdCell.Building b : cell.buildings()) {
                int ax = cx + b.minX(), bx = cx + b.maxX(), az = cz + b.minZ(), bz = cz + b.maxZ();
                if (bx < t[0] || ax > t[2] || bz < t[1] || az > t[3]) continue;
                int r = BUILDING_REACH;
                out.add(new int[] {ax - r, az - r, bx + r, bz + r});
            }
        }
        return out;
    }

    /** Requests the cells under the corners of box {x0, z0, x1, z1} (a box narrower than a cell: its corners cover it). */
    static Map<Long, CompletableFuture<CellCache.Result>> request(CellCache cache, SwissCoords coords, int[] box,
                                                                  Map<Long, CompletableFuture<CellCache.Result>> into) {
        for (int x = box[0]; ; x = Math.min(box[2], x + 1000)) {
            for (int z = box[1]; ; z = Math.min(box[3], z + 1000)) {
                int e = SwissCoords.cellKm(coords.east(x)), n = SwissCoords.cellKm(coords.north(z));
                into.computeIfAbsent(CellCache.key(e, n), k -> cache.get(e, n));
                if (z == box[3]) break;
            }
            if (x == box[2]) break;
        }
        return into;
    }

    private static CompletableFuture<Void> all(Map<Long, CompletableFuture<CellCache.Result>> m) {
        return CompletableFuture.allOf(m.values().toArray(CompletableFuture[]::new));
    }

    /** The cells of completed requests (ocean ones absent), or null if any was aborted. */
    static Map<Long, SwdCell> loaded(Map<Long, CompletableFuture<CellCache.Result>> done) {
        Map<Long, SwdCell> cells = new HashMap<>();
        for (var f : done.entrySet()) {
            CellCache.Result r = f.getValue().join();
            if (r.status() == CellCache.Status.ABORTED) return null;
            if (r.cell() != null) cells.put(f.getKey(), r.cell());
        }
        return cells;
    }

    /** Which requested cells exist, as a hash of their keys (order independent). */
    private static long presence(Map<Long, CompletableFuture<CellCache.Result>> done) {
        long h = 0;
        for (var f : done.entrySet()) {
            if (f.getValue().join().cell() != null) h += f.getKey() * 0x9E3779B97F4A7C15L + 1;
        }
        return h;
    }

    /** Tiles cached now (for stats). */
    public static int cached() {
        synchronized (PLANS) {
            return PLANS.size();
        }
    }

    /** Drops every plan (after an origin change, or for a clean measurement). */
    public static void clear() {
        synchronized (PLANS) {
            PLANS.clear();
        }
    }
}
