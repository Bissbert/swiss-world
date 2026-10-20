package ch.bissbert.swissworld.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.net.SidecarClient;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CellCacheTest {
    private static final Path FIX = Path.of(System.getProperty("swissworld.fixtures", "../fixtures"));
    private static final int E = 2632, N = 1170;

    @TempDir
    Path store;
    long now = 1_000_000;
    final FakeSidecar sidecar = new FakeSidecar();

    private CellCache cache() {
        return new CellCache(store, 9, Runnable::run, sidecar, () -> now);
    }

    private void putFixture() throws IOException {
        Files.createDirectories(store.resolve("cells"));
        Files.copy(FIX.resolve("synthetic_2632_1170.swd"), store.resolve("cells/" + E + "_" + N + ".swd"));
    }

    /** One-cell coverage grid at (E, N) with the given state. */
    private void putCoverage(int state, boolean truncated) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
        b.put("SWCV".getBytes()).putShort((short) 1).putShort((short) 0).putInt(E).putInt(N).putInt(1).putInt(1).put((byte) state);
        byte[] d = b.array();
        Files.write(store.resolve("coverage.bin"), truncated ? java.util.Arrays.copyOf(d, 24) : d);
    }

    @Test
    void existingFileLoadsImmediately() throws IOException {
        putFixture();
        CompletableFuture<CellCache.Result> f = cache().get(E, N);
        assertTrue(f.isDone());
        assertEquals(CellCache.Status.READY, f.join().status());
        assertNotNull(f.join().cell());
    }

    @Test
    void missingCellStaysPendingUntilTheFileAppears() throws IOException {
        CellCache c = cache();
        CompletableFuture<CellCache.Result> f = c.get(E, N);
        c.tick();
        assertFalse(f.isDone());
        assertEquals(1, sidecar.requests.size());
        assertEquals(1, c.pendingCount());
        putFixture();
        c.tick();
        assertEquals(CellCache.Status.READY, f.join().status());
        assertEquals(0, c.pendingCount());
        assertNotNull(c.getIfPresent(E, N));
    }

    @Test
    void coverageOutsideIsOceanWithoutAskingTheSidecar() throws IOException {
        putCoverage(Coverage.OUTSIDE, false);
        CellCache c = cache();
        assertEquals(CellCache.Status.OUTSIDE, c.get(E, N).join().status());
        assertEquals(CellCache.Status.OUTSIDE, c.get(E + 5, N).join().status()); // beyond the grid
        c.tick();
        assertTrue(sidecar.requests.isEmpty());
    }

    @Test
    void sidecarOutsideIsTrustedOnlyWithoutCoverage() throws IOException {
        sidecar.answer = ids -> CompletableFuture.completedFuture(List.of(new SidecarClient.CellState(E, N, "outside", null, 0)));
        CellCache c = cache();
        CompletableFuture<CellCache.Result> f = c.get(E, N);
        c.tick();
        assertEquals(CellCache.Status.OUTSIDE, f.join().status());

        putCoverage(Coverage.INSIDE, false);
        now += CellCache.COVERAGE_CHECK_MS;
        CellCache c2 = cache();
        CompletableFuture<CellCache.Result> f2 = c2.get(E, N);
        c2.tick();
        assertFalse(f2.isDone());
    }

    @Test
    void truncatedCoverageIsUnknownNotOcean() throws IOException {
        putCoverage(Coverage.OUTSIDE, true);
        CellCache c = cache();
        assertFalse(c.hasCoverage());
        assertEquals(CellCache.UNKNOWN, c.coverageState(E, N));
        assertFalse(c.get(E, N).isDone());
    }

    @Test
    void sidecarDownKeepsCellsPendingAndBacksOff() {
        sidecar.answer = ids -> CompletableFuture.failedFuture(new ConnectException("refused"));
        CellCache c = cache();
        CompletableFuture<CellCache.Result> f = c.get(E, N);
        c.tick();
        assertTrue(c.sidecarDown());
        assertFalse(f.isDone());
        c.tick(); // inside the backoff window: no new request
        assertEquals(1, sidecar.requests.size());
        now += CellCache.BACKOFF_MAX_MS;
        c.tick();
        assertEquals(2, sidecar.requests.size());
        assertFalse(f.isDone());
    }

    @Test
    void unreadableFileStaysPending() throws IOException {
        Files.createDirectories(store.resolve("cells"));
        Files.write(store.resolve("cells/" + E + "_" + N + ".swd"), new byte[] {1, 2, 3});
        CellCache c = cache();
        CompletableFuture<CellCache.Result> f = c.get(E, N);
        c.tick();
        assertFalse(f.isDone());
    }

    @Test
    void abortCompletesPendingAndLaterRequests() {
        CellCache c = cache();
        CompletableFuture<CellCache.Result> f = c.get(E, N);
        c.abortPending();
        assertEquals(CellCache.Status.ABORTED, f.join().status());
        assertEquals(CellCache.Status.ABORTED, c.get(E + 1, N).join().status());
        assertEquals(0, c.pendingCount());
    }

    @Test
    void statsCountHitsMissesAndDecodes() throws IOException {
        putFixture();
        CellCache c = cache();
        c.get(E, N); // miss: decoded from the file
        c.get(E, N);
        c.get(E, N);
        assertEquals(2, c.hits());
        assertEquals(1, c.misses());
        assertEquals(1, c.decodedCells());
        assertEquals(2.0 / 3, c.hitRate(), 1e-9);
        assertTrue(c.meanDecodeMs() >= 0);
    }

    @Test
    void warmDecodesAFileThatIsThereWithoutPendingAnything() throws IOException {
        CellCache c = cache();
        assertFalse(c.warm(E, N), "no file yet");
        putFixture();
        assertTrue(c.warm(E, N));
        assertNotNull(c.getIfPresent(E, N));
        assertEquals(0, c.pendingCount());
        assertFalse(c.warm(E, N), "already cached");
    }

    @Test
    void abortWaitingHoldsUntilResumed() {
        CellCache c = cache();
        CompletableFuture<CellCache.Result> f = c.get(E, N);
        assertEquals(1, c.abortWaiting());
        assertEquals(CellCache.Status.ABORTED, f.join().status());
        assertEquals(CellCache.Status.ABORTED, c.get(E + 1, N).join().status());
        c.resumeWaiting();
        CompletableFuture<CellCache.Result> later = c.get(E, N);
        assertFalse(later.isDone());
        assertEquals(1, c.pendingCount());
    }

    static final class FakeSidecar implements SidecarClient {
        final List<Collection<int[]>> requests = new ArrayList<>();
        Function<Collection<int[]>, CompletableFuture<List<CellState>>> answer =
                ids -> CompletableFuture.completedFuture(List.of());

        @Override
        public CompletableFuture<List<CellState>> request(Collection<int[]> cells, String priority) {
            requests.add(cells);
            return answer.apply(cells);
        }

        @Override
        public CompletableFuture<Integer> prefetch(double e, double n, int radiusM, double headingDeg, double speedMps) {
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public CompletableFuture<JsonObject> status() {
            return CompletableFuture.completedFuture(new JsonObject());
        }
    }
}
