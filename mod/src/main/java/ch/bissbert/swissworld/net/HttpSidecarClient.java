package ch.bissbert.swissworld.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** {@link SidecarClient} over {@code java.net.http}. Responses are parsed on {@code executor}, never on a game thread. */
public final class HttpSidecarClient implements SidecarClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final URI base;
    private final HttpClient http;

    public HttpSidecarClient(String baseUrl, Executor executor) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).executor(executor).build();
    }

    @Override
    public CompletableFuture<List<CellState>> request(Collection<int[]> cells, String priority) {
        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        for (int[] c : cells) {
            JsonArray p = new JsonArray();
            p.add(c[0]);
            p.add(c[1]);
            arr.add(p);
        }
        body.add("cells", arr);
        body.addProperty("priority", priority);
        return send(post("request", body)).thenApply(HttpSidecarClient::parseStates);
    }

    @Override
    public CompletableFuture<Integer> prefetch(double e, double n, int radiusM, double headingDeg, double speedMps) {
        JsonObject body = new JsonObject();
        body.addProperty("e", e);
        body.addProperty("n", n);
        body.addProperty("radiusM", radiusM);
        body.addProperty("headingDeg", headingDeg);
        body.addProperty("speedMps", speedMps);
        return send(post("prefetch", body)).thenApply(o -> o.has("queued") ? o.get("queued").getAsInt() : 0);
    }

    @Override
    public CompletableFuture<JsonObject> status() {
        return send(HttpRequest.newBuilder(base.resolve("status")).timeout(TIMEOUT).GET().build());
    }

    private HttpRequest post(String path, JsonObject body) {
        return HttpRequest.newBuilder(base.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private CompletableFuture<JsonObject> send(HttpRequest req) {
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() / 100 != 2) {
                throw new CompletionException(new IOException("sidecar " + req.uri().getPath() + " -> HTTP " + r.statusCode()));
            }
            String b = r.body();
            return b == null || b.isBlank() ? new JsonObject() : JsonParser.parseString(b).getAsJsonObject();
        });
    }

    static List<CellState> parseStates(JsonObject o) {
        List<CellState> out = new ArrayList<>();
        if (!o.has("cells")) return out;
        for (JsonElement el : o.getAsJsonArray("cells")) {
            JsonObject c = el.getAsJsonObject();
            JsonArray id = c.getAsJsonArray("cell");
            out.add(new CellState(id.get(0).getAsInt(), id.get(1).getAsInt(),
                    c.has("state") ? c.get("state").getAsString() : "",
                    c.has("error") && !c.get("error").isJsonNull() ? c.get("error").getAsString() : null,
                    c.has("retryAfterS") ? c.get("retryAfterS").getAsInt() : 0));
        }
        return out;
    }
}
