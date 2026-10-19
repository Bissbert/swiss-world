package ch.bissbert.swissworld.net;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Control plane of the swissdata sidecar (docs/sidecar-api.md). Owner: mod-core.
 * All calls are async; a sidecar that is down fails the future, it never blocks the caller.
 */
public interface SidecarClient {
    /** POST /request. {@code priority} is "now" (a chunk is waiting) or "prefetch". */
    CompletableFuture<List<CellState>> request(Collection<int[]> cells, String priority);

    /** POST /prefetch around (e, n), plus a corridor along the heading when speed > 0. */
    CompletableFuture<Integer> prefetch(double e, double n, int radiusM, double headingDeg, double speedMps);

    /** GET /status, passed through as JSON. */
    CompletableFuture<JsonObject> status();

    /**
     * GET /place: swissNAMES3D lookup, best first (exact before prefix, settlements before other kinds). Fails with
     * {@link IndexNotReady} while the sidecar is still building its place index.
     */
    default CompletableFuture<List<Place>> places(String query, int limit, Duration timeout) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("place lookup"));
    }

    /** One /place result. {@code h} is NaN when unknown; {@code matched} is the alias that matched (e.g. "Genf"), or null. */
    record Place(String name, String kind, double e, double n, double h, String matched) {}

    /** The sidecar answered 503: its place index is being built (first use downloads swissNAMES3D). */
    final class IndexNotReady extends IOException {
        public IndexNotReady(String message) {
            super(message);
        }
    }

    /** One entry of a /request or /wait response. {@code retryAfterS} is 0 when not given. */
    record CellState(int eKm, int nKm, String state, String error, int retryAfterS) {
        public static final String READY = "ready", OUTSIDE = "outside", ERROR = "error";
    }
}
