package ch.bissbert.swissworld.command;

import ch.bissbert.swissworld.net.SidecarClient;
import ch.bissbert.swissworld.net.SidecarClient.Place;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Place names for {@code /swiss goto <place>}: the sidecar's swissNAMES3D gazetteer ({@code GET /place}). Nothing here
 * blocks: lookups return futures, tab completion gives up after {@link #SUGGEST_WAIT} and answers from a small TTL
 * cache next time. Owner: mod-core.
 */
public final class PlaceLookup {
    /** HTTP timeout for a /swiss goto lookup (the command answers asynchronously). */
    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    /** Tab completion: HTTP timeout, and how long the client's suggestion request may wait for it. */
    static final Duration SUGGEST_TIMEOUT = Duration.ofMillis(1500);
    static final long SUGGEST_WAIT_MS = 400;
    static final long TTL_MS = 5 * 60_000;
    static final int CACHE_SIZE = 256, SUGGEST_LIMIT = 8;

    private static final Pattern TWO_NUMBERS =
            Pattern.compile("\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*[,;\\s]\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*");

    private final Supplier<SidecarClient> client;
    private final LongSupplier clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private record Cached(long at, List<Place> places) {}

    PlaceLookup(Supplier<SidecarClient> client, LongSupplier clockMs) {
        this.client = client;
        this.clock = clockMs;
    }

    /** "2638356 1237642", "46.68, 7.87": the coordinate form typed into the place slot; null for anything else. */
    static double[] twoNumbers(String s) {
        Matcher m = TWO_NUMBERS.matcher(s);
        return m.matches() ? new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))} : null;
    }

    /** Cache key: case and accents folded like the sidecar does, so "zur" and "Zür" share an entry. */
    static String key(String q) {
        String d = Normalizer.normalize(q, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return d.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** A /swiss goto lookup: best first, an empty list when nothing matches. */
    CompletableFuture<List<Place>> find(String query, int limit) {
        return client.get().places(query.strip(), limit, LOOKUP_TIMEOUT);
    }

    /**
     * Names for tab completion. Never fails and completes within {@link #SUGGEST_WAIT_MS} (empty when the sidecar is
     * slower; the answer still lands in the cache for the next keystroke). Numbers and single letters are skipped.
     */
    CompletableFuture<List<Place>> suggest(String prefix) {
        String k = key(prefix);
        if (k.length() < 2 || Character.isDigit(k.charAt(0)) || k.charAt(0) == '-') {
            return CompletableFuture.completedFuture(List.of());
        }
        long now = clock.getAsLong();
        synchronized (cache) {
            Cached c = cache.get(k);
            if (c != null && now - c.at() < TTL_MS) return CompletableFuture.completedFuture(c.places());
        }
        CompletableFuture<List<Place>> f;
        try {
            f = client.get().places(prefix.strip(), SUGGEST_LIMIT, SUGGEST_TIMEOUT);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(List.of());
        }
        return f.thenApply(list -> {
                    synchronized (cache) {
                        cache.put(k, new Cached(clock.getAsLong(), List.copyOf(list)));
                    }
                    return list;
                })
                .exceptionally(e -> List.of())
                .completeOnTimeout(List.of(), SUGGEST_WAIT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * The client sorts suggestions alphabetically, so the sidecar's ranking is lost there; keep only names that start
     * with what was typed ("Zofingen", not "Galgen Zofingen"), unless that leaves nothing.
     */
    static List<Place> forCompletion(List<Place> list, String typed) {
        String k = key(typed);
        List<Place> starts = list.stream()
                .filter(p -> key(p.name()).startsWith(k) || p.matched() != null && key(p.matched()).startsWith(k))
                .toList();
        return starts.isEmpty() ? list : starts;
    }

    int cachedCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** "Zofingen (town)", "Genève (city, as Genf)". */
    static String label(Place p) {
        String as = p.matched() != null && !p.matched().equals(p.name()) ? ", as " + p.matched() : "";
        return p.name() + " (" + p.kind() + as + ")";
    }

    /** "Going to Zofingen (town), LV95 E 2638356 N 1237642". */
    static String going(Place p) {
        return String.format(Locale.ROOT, "Going to %s, LV95 E %.0f N %.0f%s", label(p), p.e(), p.n(),
                Double.isNaN(p.h()) ? "" : String.format(Locale.ROOT, ", %.0f m", p.h()));
    }

    static String notFound(String query) {
        return "No place called \"" + query.strip() + "\" found. Try a town, village, peak, pass or lake name "
                + "(tab completes), or coordinates: LV95 E N, LV03 or WGS84 lat lon.";
    }

    /** Message for a failed lookup: index still building, or sidecar unreachable. */
    static String failure(Throwable err) {
        String why = null;
        for (Throwable t = err; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SidecarClient.IndexNotReady) {
                return "The place index is still being prepared (the first use downloads swissNAMES3D). Try again in a minute.";
            }
            if (t instanceof UnsupportedOperationException) return "Place lookup is not available.";
            if (why == null && t instanceof java.net.ConnectException) why = "connection refused";
            if (why == null && t instanceof java.net.http.HttpTimeoutException) why = "timed out";
            if (t.getCause() == null && why == null) why = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        }
        return "Place lookup failed: the data sidecar is not reachable (" + why + ").";
    }
}
