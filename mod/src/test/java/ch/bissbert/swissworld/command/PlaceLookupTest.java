package ch.bissbert.swissworld.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.net.SidecarClient;
import ch.bissbert.swissworld.net.SidecarClient.Place;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PlaceLookupTest {
    static final Place ZOFINGEN = new Place("Zofingen", "town", 2638356, 1237642, 439, null);
    static final Place GENEVE = new Place("Genève", "city", 2500226, 1118241, 376, "Genf");

    /** Answers /place from a function; records the queries. */
    static final class Fake implements SidecarClient {
        final List<String> queries = new ArrayList<>();
        java.util.function.Function<String, CompletableFuture<List<Place>>> answer =
                q -> CompletableFuture.completedFuture(List.of(ZOFINGEN));

        @Override
        public CompletableFuture<List<Place>> places(String query, int limit, Duration timeout) {
            queries.add(query);
            return answer.apply(query);
        }

        @Override
        public CompletableFuture<List<CellState>> request(Collection<int[]> cells, String priority) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Integer> prefetch(double e, double n, int radiusM, double headingDeg, double speedMps) {
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public CompletableFuture<com.google.gson.JsonObject> status() {
            return CompletableFuture.completedFuture(new com.google.gson.JsonObject());
        }
    }

    @Test
    void twoNumbersAreCoordinatesEverythingElseAName() {
        assertArrayEquals(new double[] {2638356, 1237642}, PlaceLookup.twoNumbers("2638356 1237642"));
        assertArrayEquals(new double[] {46.68, 7.87}, PlaceLookup.twoNumbers(" 46.68, 7.87 "));
        assertArrayEquals(new double[] {-1, 2}, PlaceLookup.twoNumbers("-1 2"));
        assertNull(PlaceLookup.twoNumbers("Zofingen"));
        assertNull(PlaceLookup.twoNumbers("2638356"));
        assertNull(PlaceLookup.twoNumbers("1 2 3"));
        assertNull(PlaceLookup.twoNumbers("St. Moritz"));
    }

    @Test
    void keyFoldsCaseAndAccents() {
        assertEquals("zurich", PlaceLookup.key("Zürich"));
        assertEquals("geneve", PlaceLookup.key(" GENÈVE "));
        assertEquals("biel bienne", PlaceLookup.key("Biel/Bienne"));
    }

    @Test
    void messages() {
        assertEquals("Going to Zofingen (town), LV95 E 2638356 N 1237642, 439 m", PlaceLookup.going(ZOFINGEN));
        assertEquals("Genève (city, as Genf)", PlaceLookup.label(GENEVE));
        assertEquals("Grimselpass (pass)", PlaceLookup.label(new Place("Grimselpass", "pass", 1, 2, Double.NaN, "Grimselpass")));
        assertTrue(PlaceLookup.notFound(" Xyzzy ").contains("\"Xyzzy\""));
        assertTrue(PlaceLookup.failure(new CompletionException(new SidecarClient.IndexNotReady("building")))
                .contains("still being prepared"));
        assertTrue(PlaceLookup.failure(new CompletionException(new ConnectException("Connection refused")))
                .contains("not reachable (connection refused)"));
        ConnectException ce = new ConnectException(null);
        ce.initCause(new java.nio.channels.ClosedChannelException());
        assertTrue(PlaceLookup.failure(new CompletionException(ce)).endsWith("(connection refused)."));
        assertTrue(PlaceLookup.failure(new IOException("sidecar /place -> HTTP 500")).contains("(sidecar /place -> HTTP 500)"));
    }

    @Test
    void completionKeepsNamesStartingWithTheInput() {
        Place galgen = new Place("Galgen Zofingen", "historic site", 1, 2, Double.NaN, null);
        assertEquals(List.of(ZOFINGEN), PlaceLookup.forCompletion(List.of(galgen, ZOFINGEN), "zof"));
        assertEquals(List.of(GENEVE), PlaceLookup.forCompletion(List.of(GENEVE), "Genf"));
        assertEquals(List.of(galgen), PlaceLookup.forCompletion(List.of(galgen), "Galgen"));
        assertEquals(List.of(galgen), PlaceLookup.forCompletion(List.of(galgen), "richt")); // nothing starts with it
    }

    @Test
    void suggestionsAreCachedAndSkipNumbersAndSingleLetters() throws Exception {
        Fake fake = new Fake();
        AtomicLong now = new AtomicLong(1000);
        PlaceLookup l = new PlaceLookup(() -> fake, now::get);
        assertEquals(List.of(), l.suggest("Z").get());
        assertEquals(List.of(), l.suggest("2638").get());
        assertEquals(List.of(), l.suggest("-12").get());
        assertTrue(fake.queries.isEmpty());

        assertEquals(List.of(ZOFINGEN), l.suggest("Zof").get());
        assertEquals(List.of(ZOFINGEN), l.suggest("ZOF").get()); // same key, from the cache
        assertEquals(1, fake.queries.size());
        now.addAndGet(PlaceLookup.TTL_MS + 1);
        l.suggest("zof").get();
        assertEquals(2, fake.queries.size()); // expired
    }

    @Test
    void suggestionsNeverFailAndNeverWaitLong() throws Exception {
        Fake fake = new Fake();
        PlaceLookup l = new PlaceLookup(() -> fake, System::currentTimeMillis);
        fake.answer = q -> CompletableFuture.failedFuture(new IOException("down"));
        assertEquals(List.of(), l.suggest("Zofingen").get(1, TimeUnit.SECONDS));
        assertEquals(0, l.cachedCount());

        CompletableFuture<List<Place>> slow = new CompletableFuture<>();
        fake.answer = q -> slow;
        long t0 = System.nanoTime();
        assertEquals(List.of(), l.suggest("Interlaken").get(2, TimeUnit.SECONDS));
        assertTrue(System.nanoTime() - t0 < 1_500_000_000L);
        slow.complete(List.of(ZOFINGEN)); // the late answer still feeds the cache
        assertEquals(1, l.cachedCount());
        assertEquals(List.of(ZOFINGEN), l.suggest("interlaken").get());
    }
}
