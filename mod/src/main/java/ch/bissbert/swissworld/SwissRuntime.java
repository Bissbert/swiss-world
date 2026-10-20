package ch.bissbert.swissworld;

import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.net.HttpSidecarClient;
import ch.bissbert.swissworld.net.SidecarClient;
import ch.bissbert.swissworld.world.SwissOrigin;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.ChunkPos;

/**
 * Process-wide state for the generator. Owner: mod-core.
 * Lifecycle: {@link #start} on server about-to-start (config is loaded), {@link #stopping} aborts pending cells so
 * shutdown can't hang, {@link #stopped} closes the cache. Store path: config {@code store}, else system property
 * {@code swissworld.store}, else env {@code SWISSWORLD_STORE}, else {@code ./swissworld-store}.
 */
public final class SwissRuntime {
    public static final SwissCoords DEFAULT_ORIGIN = new SwissCoords(2_632_900, 1_170_600);

    /** Lazily replaced by {@link #start} with a pool sized from the config. */
    private static volatile ExecutorService io;
    private static volatile CellCache cache;
    private static volatile SidecarClient sidecar;
    private static volatile SwissOrigin origin;
    private static volatile SwissCoords coords = DEFAULT_ORIGIN;

    /** Chunks whose noise step waits for cells right now. */
    public static final AtomicInteger PENDING_CHUNKS = new AtomicInteger();
    private static final AtomicLong CHUNKS = new AtomicLong(), CHUNK_NANOS = new AtomicLong();
    private static final Set<Long> ABORTED = ConcurrentHashMap.newKeySet();

    private SwissRuntime() {}

    /** The default for {@code ioThreads = 0}: half the cores, at least 2, at most 6. */
    public static int defaultIoThreads(int cores) {
        return Math.max(2, Math.min(6, cores / 2));
    }

    /** A fixed pool of daemon threads named {@code swissworld-io-1..n}. */
    public static ExecutorService newIoPool(int threads) {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "swissworld-io-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    private static synchronized ExecutorService io() {
        if (io == null) io = newIoPool(defaultIoThreads(Runtime.getRuntime().availableProcessors()));
        return io;
    }

    public static Path storePath() {
        String p = SwissConfig.SPEC.isLoaded() ? SwissConfig.STORE.get() : "";
        if (p == null || p.isBlank()) p = System.getProperty("swissworld.store");
        if (p == null || p.isBlank()) p = System.getenv("SWISSWORLD_STORE");
        return Path.of(p == null || p.isBlank() ? "swissworld-store" : p).toAbsolutePath();
    }

    static synchronized void start() {
        stopped();
        String url = SwissConfig.SIDECAR_URL.get();
        int threads = SwissConfig.IO_THREADS.get();
        if (threads <= 0) threads = defaultIoThreads(Runtime.getRuntime().availableProcessors());
        io = newIoPool(threads);
        sidecar = new HttpSidecarClient(url, io);
        cache = new CellCache(storePath(), SwissConfig.CELL_CACHE.get(), io, sidecar, System::currentTimeMillis);
        cache.start();
        ABORTED.clear();
        CHUNKS.set(0);
        CHUNK_NANOS.set(0);
        SwissWorld.LOGGER.info("Swiss World store: {}, sidecar: {}, io threads: {}, coverage: {}", storePath(), url, threads,
                cache.hasCoverage() ? "loaded" : "missing (nothing is ocean until coverage.bin exists)");
    }

    /** Server stopping: pending chunks complete empty and are marked for {@link ch.bissbert.swissworld.world.AbortedChunks}. */
    static void stopping() {
        CellCache c = cache;
        if (c != null) c.abortPending();
    }

    static synchronized void stopped() {
        if (cache != null) cache.close();
        if (io != null) io.shutdownNow();
        io = null;
        cache = null;
        sidecar = null;
        origin = null;
        coords = DEFAULT_ORIGIN;
    }

    public static CellCache cells() {
        CellCache c = cache;
        if (c == null) {
            synchronized (SwissRuntime.class) {
                if (cache == null) {
                    // no server lifecycle (e.g. a tool or test): defaults, sidecar on localhost
                    sidecar = new HttpSidecarClient("http://127.0.0.1:8080", io());
                    cache = new CellCache(storePath(), 64, io(), sidecar, System::currentTimeMillis);
                    cache.start();
                }
                c = cache;
            }
        }
        return c;
    }

    public static SidecarClient sidecar() {
        cells();
        return sidecar;
    }

    public static SwissCoords coords() {
        return coords;
    }

    public static SwissOrigin origin() {
        return origin;
    }

    static void setOrigin(SwissOrigin data) {
        origin = data;
        coords = data.coords();
    }

    /**
     * Called whenever a chunk starts generating: locks the origin (first time only) and returns it. Locking and
     * reading under the origin's monitor means no chunk can see an origin that {@link #changeOrigin} replaces later.
     */
    public static SwissCoords lockOrigin() {
        SwissOrigin o = origin;
        if (o == null) return coords;
        synchronized (o) {
            if (!o.locked()) {
                o.lock();
                SwissWorld.LOGGER.info("Swiss World origin locked at E {} / N {}", coords.e0(), coords.n0());
            }
            return coords;
        }
    }

    /** @return false if the origin is locked */
    public static boolean changeOrigin(long e, long n) {
        SwissOrigin o = origin;
        if (o == null) return false;
        synchronized (o) {
            if (!o.set(e, n)) return false;
            coords = o.coords();
            return true;
        }
    }

    public static void recordChunk(long nanos) {
        CHUNKS.incrementAndGet();
        CHUNK_NANOS.addAndGet(nanos);
    }

    public static long chunksGenerated() {
        return CHUNKS.get();
    }

    /** Mean pipeline time per chunk since start, ms. */
    public static double meanChunkMs() {
        long n = CHUNKS.get();
        return n == 0 ? 0 : CHUNK_NANOS.get() / 1e6 / n;
    }

    public static void markAborted(ChunkPos pos) {
        ABORTED.add(pos.toLong());
    }

    /**
     * The chunk generated for real (it was unloaded and loaded again, or {@link
     * ch.bissbert.swissworld.world.PlaceholderRepair} filled it in place), so it may be saved as generated again.
     */
    public static void clearAborted(ChunkPos pos) {
        ABORTED.remove(pos.toLong());
    }

    public static boolean wasAborted(ChunkPos pos) {
        return ABORTED.contains(pos.toLong());
    }

    public static int abortedCount() {
        return ABORTED.size();
    }

    /** Positions ({@link ChunkPos#toLong}) of the empty placeholders; a live, thread-safe view. */
    public static Set<Long> abortedChunks() {
        return java.util.Collections.unmodifiableSet(ABORTED);
    }
}
