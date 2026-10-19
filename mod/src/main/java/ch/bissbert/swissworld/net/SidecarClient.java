package ch.bissbert.swissworld.net;

import com.google.gson.JsonObject;
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

    /** One entry of a /request or /wait response. {@code retryAfterS} is 0 when not given. */
    record CellState(int eKm, int nKm, String state, String error, int retryAfterS) {
        public static final String READY = "ready", OUTSIDE = "outside", ERROR = "error";
    }
}
