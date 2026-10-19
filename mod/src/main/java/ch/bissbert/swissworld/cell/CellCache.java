package ch.bissbert.swissworld.cell;

import ch.bissbert.swissworld.SwissWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * In-memory LRU of decoded cells, backed by {@code <store>/cells/*.swd}.
 * Owner: mod-core. Stub: only reads files already present; sidecar requests are added in M1.
 */
public final class CellCache {
    private final Path store;
    private final int capacity;
    private final Executor io;
    private final Map<Long, SwdCell> lru;
    private final Map<Long, CompletableFuture<Optional<SwdCell>>> loading = new ConcurrentHashMap<>();

    public CellCache(Path store, int capacity, Executor io) {
        this.store = store;
        this.capacity = capacity;
        this.io = io;
        this.lru = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, SwdCell> eldest) {
                return size() > CellCache.this.capacity;
            }
        };
    }

    public static long key(int eKm, int nKm) {
        return ((long) eKm << 32) | (nKm & 0xFFFFFFFFL);
    }

    public Path cellFile(int eKm, int nKm) {
        return store.resolve("cells").resolve(eKm + "_" + nKm + ".swd");
    }

    /** Non-blocking: the cell if it is in memory. */
    public synchronized SwdCell getIfPresent(int eKm, int nKm) {
        return lru.get(key(eKm, nKm));
    }

    /** Completes with empty if the file does not exist (yet). */
    public CompletableFuture<Optional<SwdCell>> get(int eKm, int nKm) {
        long k = key(eKm, nKm);
        SwdCell hit = getIfPresent(eKm, nKm);
        if (hit != null) return CompletableFuture.completedFuture(Optional.of(hit));
        return loading.computeIfAbsent(k, kk -> CompletableFuture.supplyAsync(() -> load(eKm, nKm), io)
                .whenComplete((r, t) -> loading.remove(kk)));
    }

    private Optional<SwdCell> load(int eKm, int nKm) {
        Path f = cellFile(eKm, nKm);
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            SwdCell c = SwdReader.read(f);
            synchronized (this) {
                lru.put(key(eKm, nKm), c);
            }
            return Optional.of(c);
        } catch (IOException e) {
            SwissWorld.LOGGER.warn("Unreadable cell {}: {}", f, e.toString());
            return Optional.empty();
        }
    }
}
