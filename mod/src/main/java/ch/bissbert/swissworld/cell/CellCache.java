package ch.bissbert.swissworld.cell;

import ch.bissbert.swissworld.net.SidecarClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory LRU of decoded cells, backed by {@code <store>/cells/*.swd} and {@code <store>/coverage.bin}. Owner: mod-core.
 *
 * <p>{@link #get} completes only once the answer is final: the cell is loaded ({@link Status#READY}), or the cell
 * is known to be outside Switzerland ({@link Status#OUTSIDE}). A missing cell stays pending: it is requested from the
 * sidecar (priority "now"), re-requested every {@link #REREQUEST_MS}, and the store directory is polled until the file
 * appears. A sidecar that is down only delays this (with backoff); it never turns a cell into ocean. Only
 * {@link #abortPending()} (server shutdown) completes pending cells with {@link Status#ABORTED}.
 *
 * <p>OUTSIDE comes from coverage.bin. The sidecar's "outside" is trusted only while there is no coverage file;
 * if coverage says the cell exists, the disagreement is logged and the cell stays pending.
 */
public final class CellCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(CellCache.class);

    public static final long POLL_MS = 250;
    public static final long REREQUEST_MS = 5_000;
    public static final long COVERAGE_CHECK_MS = 2_000;
    public static final long BACKOFF_MIN_MS = 2_000, BACKOFF_MAX_MS = 30_000;
    /** Coverage state for "no coverage.bin (yet)". */
    public static final int UNKNOWN = -1;

    public enum Status { READY, OUTSIDE, ABORTED }

    public record Result(Status status, SwdCell cell) {
        static final Result OUTSIDE = new Result(Status.OUTSIDE, null);
        static final Result ABORTED = new Result(Status.ABORTED, null);
    }

    private final Path store;
    private final int capacity;
    private final Executor io;
    private final SidecarClient sidecar;
    private final LongSupplier clock;
    private final Map<Long, SwdCell> lru;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private volatile Coverage coverage;
    private long coverageMtime = Long.MIN_VALUE, coverageCheckedAt = Long.MIN_VALUE;
    private volatile boolean aborted;
    private volatile ScheduledExecutorService poller;

    // sidecar health, only touched by the poller and request callbacks (synchronized on this)
    private long sidecarRetryAt, backoffMs;
    private boolean sidecarDown;

    public CellCache(Path store, int capacity, Executor io, SidecarClient sidecar, LongSupplier clockMs) {
        this.store = store;
        this.capacity = capacity;
        this.io = io;
        this.sidecar = sidecar;
        this.clock = clockMs;
        this.lru = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, SwdCell> eldest) {
                return size() > CellCache.this.capacity;
            }
        };
        refreshCoverage();
    }

    public static long key(int eKm, int nKm) {
        return ((long) eKm << 32) | (nKm & 0xFFFFFFFFL);
    }

    public Path store() {
        return store;
    }

    public Path cellFile(int eKm, int nKm) {
        return store.resolve("cells").resolve(eKm + "_" + nKm + ".swd");
    }

    /** Starts the background poller (store directory, coverage.bin, sidecar re-requests). */
    public synchronized void start() {
        if (poller != null) return;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "swissworld-cells");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {
                LOGGER.error("Cell poller failed", e);
            }
        }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void close() {
        if (poller != null) poller.shutdownNow();
        poller = null;
    }

    /** Non-blocking: the cell if it is in memory. */
    public synchronized SwdCell getIfPresent(int eKm, int nKm) {
        return lru.get(key(eKm, nKm));
    }

    /** Non-blocking coverage lookup: {@link Coverage#OUTSIDE}/BORDER/INSIDE, or {@link #UNKNOWN} without coverage.bin. */
    public int coverageState(int eKm, int nKm) {
        Coverage c = coverage;
        return c == null ? UNKNOWN : c.state(eKm, nKm);
    }

    public boolean hasCoverage() {
        return coverage != null;
    }

    public int pendingCount() {
        return pending.size();
    }

    public synchronized int cachedCount() {
        return lru.size();
    }

    public int capacity() {
        return capacity;
    }

    public synchronized boolean sidecarDown() {
        return sidecarDown;
    }

    /** Completes with READY or OUTSIDE once that is certain; stays pending while the cell is being built. */
    public CompletableFuture<Result> get(int eKm, int nKm) {
        SwdCell hit = getIfPresent(eKm, nKm);
        if (hit != null) return CompletableFuture.completedFuture(new Result(Status.READY, hit));
        if (aborted) return CompletableFuture.completedFuture(Result.ABORTED);
        if (coverageState(eKm, nKm) == Coverage.OUTSIDE) return CompletableFuture.completedFuture(Result.OUTSIDE);
        Pending p = pending.computeIfAbsent(key(eKm, nKm), k -> new Pending(eKm, nKm));
        if (aborted) finish(p, Result.ABORTED); // raced with abortPending()
        else io.execute(() -> tryLoad(p));
        // a copy, so a caller cancelling or completing its future can't affect other chunks waiting for the same cell
        return p.future.copy();
    }

    /** Server shutdown: completes every pending cell with ABORTED, and all later requests too. */
    public void abortPending() {
        aborted = true;
        for (Pending p : pending.values()) finish(p, Result.ABORTED);
    }

    private void tryLoad(Pending p) {
        if (p.future.isDone() || !p.busy.compareAndSet(false, true)) return;
        try {
            if (coverageState(p.eKm, p.nKm) == Coverage.OUTSIDE) {
                finish(p, Result.OUTSIDE);
                return;
            }
            Path f = cellFile(p.eKm, p.nKm);
            if (clock.getAsLong() < p.nextLoadAt || !Files.isRegularFile(f)) return;
            try {
                SwdCell c = SwdReader.read(f);
                synchronized (this) {
                    lru.put(key(p.eKm, p.nKm), c);
                }
                finish(p, new Result(Status.READY, c));
            } catch (IOException | RuntimeException e) {
                // files are renamed into place atomically, so this is a broken file: keep the chunk pending, retry later
                p.nextLoadAt = clock.getAsLong() + REREQUEST_MS;
                if (!p.warnedUnreadable) LOGGER.warn("Unreadable cell {} (chunks stay pending, retrying): {}", f, e.toString());
                p.warnedUnreadable = true;
            }
        } finally {
            p.busy.set(false);
        }
    }

    private void finish(Pending p, Result r) {
        pending.remove(key(p.eKm, p.nKm), p);
        p.future.complete(r);
    }

    /** One poller round; package-private so tests can drive it without a thread. */
    void tick() {
        refreshCoverage();
        long now = clock.getAsLong();
        List<Pending> batch = new ArrayList<>();
        boolean mayRequest;
        synchronized (this) {
            mayRequest = now >= sidecarRetryAt;
        }
        for (Pending p : pending.values()) {
            if (coverageState(p.eKm, p.nKm) == Coverage.OUTSIDE || Files.isRegularFile(cellFile(p.eKm, p.nKm))) {
                io.execute(() -> tryLoad(p));
            } else if (mayRequest && now >= p.nextRequestAt) {
                p.nextRequestAt = now + REREQUEST_MS;
                batch.add(p);
            }
        }
        if (!batch.isEmpty()) {
            List<int[]> ids = new ArrayList<>(batch.size());
            for (Pending p : batch) ids.add(new int[] {p.eKm, p.nKm});
            sidecar.request(ids, "now").whenComplete((states, err) -> {
                if (err != null) sidecarFailed(err, batch);
                else sidecarAnswered(states);
            });
        }
    }

    private synchronized void sidecarFailed(Throwable err, List<Pending> batch) {
        backoffMs = Math.min(BACKOFF_MAX_MS, Math.max(BACKOFF_MIN_MS, backoffMs * 2));
        sidecarRetryAt = clock.getAsLong() + backoffMs;
        for (Pending p : batch) p.nextRequestAt = 0;
        if (!sidecarDown) {
            LOGGER.warn("Sidecar unreachable ({}); chunks that need missing cells stay pending, retrying with backoff",
                    rootMessage(err));
        }
        sidecarDown = true;
    }

    private void sidecarAnswered(List<SidecarClient.CellState> states) {
        synchronized (this) {
            if (sidecarDown) LOGGER.info("Sidecar reachable again");
            sidecarDown = false;
            backoffMs = 0;
            sidecarRetryAt = 0;
        }
        long now = clock.getAsLong();
        for (SidecarClient.CellState s : states) {
            Pending p = pending.get(key(s.eKm(), s.nKm()));
            if (p == null) continue;
            switch (s.state()) {
                case SidecarClient.CellState.READY -> io.execute(() -> tryLoad(p));
                case SidecarClient.CellState.OUTSIDE -> {
                    int cov = coverageState(p.eKm, p.nKm);
                    if (cov == UNKNOWN) {
                        finish(p, Result.OUTSIDE);
                    } else if (cov != Coverage.OUTSIDE && !p.warnedOutside) {
                        LOGGER.error("Sidecar says cell {}_{} is outside, coverage.bin says {}; keeping it pending",
                                p.eKm, p.nKm, cov);
                        p.warnedOutside = true;
                    }
                }
                case SidecarClient.CellState.ERROR -> {
                    p.nextRequestAt = now + Math.max(REREQUEST_MS, s.retryAfterS() * 1000L);
                    if (s.error() != null && !s.error().equals(p.lastError)) {
                        LOGGER.warn("Sidecar failed to build cell {}_{}: {} (retrying)", p.eKm, p.nKm, s.error());
                    }
                    p.lastError = s.error();
                }
                default -> {
                    // queued / building: the re-request interval is already set
                }
            }
        }
    }

    private void refreshCoverage() {
        long now = clock.getAsLong();
        synchronized (this) {
            if (coverage != null && now - coverageCheckedAt < COVERAGE_CHECK_MS) return;
            coverageCheckedAt = now;
        }
        Path f = store.resolve("coverage.bin");
        try {
            if (!Files.isRegularFile(f)) return;
            long mtime = Files.getLastModifiedTime(f).toMillis();
            synchronized (this) {
                if (coverage != null && mtime == coverageMtime) return;
            }
            Coverage c = Coverage.read(f);
            synchronized (this) {
                coverageMtime = mtime;
                coverage = c;
            }
            LOGGER.info("Loaded coverage {} ({}x{} km from {}/{})", f, c.width(), c.height(), c.eMinKm(), c.nMinKm());
        } catch (IOException | RuntimeException e) {
            // keep the previous coverage; without one, nothing is ever decided to be ocean
            LOGGER.warn("Unreadable coverage {}: {}", f, e.toString());
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.toString();
    }

    private static final class Pending {
        final int eKm, nKm;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        final AtomicBoolean busy = new AtomicBoolean();
        volatile long nextRequestAt, nextLoadAt;
        volatile boolean warnedUnreadable, warnedOutside;
        volatile String lastError;

        Pending(int eKm, int nKm) {
            this.eKm = eKm;
            this.nKm = nKm;
        }
    }
}
