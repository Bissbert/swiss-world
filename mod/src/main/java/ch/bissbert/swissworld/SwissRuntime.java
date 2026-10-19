package ch.bissbert.swissworld;

import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.SwissCoords;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Process-wide state for the generator. Owner: mod-core.
 * Store path: system property {@code swissworld.store}, else env {@code SWISSWORLD_STORE}, else {@code ./swissworld-store}.
 * The origin is still a constant; M1 replaces it with persisted SavedData that locks on the first generated chunk.
 */
public final class SwissRuntime {
    public static final SwissCoords DEFAULT_ORIGIN = new SwissCoords(2_632_900, 1_170_600);

    private static final Executor IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "swissworld-io");
        t.setDaemon(true);
        return t;
    });
    private static volatile CellCache cache;

    private SwissRuntime() {}

    public static Path storePath() {
        String p = System.getProperty("swissworld.store");
        if (p == null || p.isBlank()) p = System.getenv("SWISSWORLD_STORE");
        return Path.of(p == null || p.isBlank() ? "swissworld-store" : p).toAbsolutePath();
    }

    public static CellCache cells() {
        CellCache c = cache;
        if (c == null) {
            synchronized (SwissRuntime.class) {
                if (cache == null) {
                    cache = new CellCache(storePath(), 64, IO);
                    SwissWorld.LOGGER.info("Swiss World store: {}", storePath());
                }
                c = cache;
            }
        }
        return c;
    }

    public static SwissCoords coords() {
        return DEFAULT_ORIGIN;
    }
}
