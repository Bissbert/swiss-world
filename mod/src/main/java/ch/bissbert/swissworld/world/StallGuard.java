package ch.bissbert.swissworld.world;

import ch.bissbert.swissworld.SwissConfig;
import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.SwissWorld;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;

/**
 * Keeps a synchronous chunk load from hanging the server. Owner: mod-core.
 *
 * <p>Generation never blocks a thread, but vanilla code may: {@code forceload add}, for one, loads the chunk on the
 * server thread and waits. If that chunk's cells are missing and the sidecar is down, the wait never ends and the
 * watchdog kills the server after max-tick-time. Once a second this checks whether the current tick has run longer
 * than {@code stallAbortSeconds} while the server thread sits in {@link ServerChunkCache#getChunk} and chunks are
 * pending; then the pending cells are aborted, exactly like at shutdown: those chunks complete empty and are
 * downgraded on save ({@link AbortedChunks}), so they generate properly once they are loaded again.
 */
public final class StallGuard {
    private static ScheduledExecutorService timer;

    private StallGuard() {}

    public static synchronized void start(MinecraftServer server) {
        stop();
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "swissworld-stall-guard");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> check(server), 1, 1, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    private static void check(MinecraftServer server) {
        int limitS = SwissConfig.STALL_ABORT_S.get();
        if (limitS <= 0 || !server.isRunning() || SwissRuntime.PENDING_CHUNKS.get() == 0) return;
        long stalledMs = (Util.getNanos() - server.getNextTickTime()) / 1_000_000;
        if (stalledMs < limitS * 1000L || !waitsForChunk(server.getRunningThread())) return;
        int cells = SwissRuntime.cells().abortWaiting();
        SwissWorld.LOGGER.warn("Server thread waited {} s for a chunk whose cells are missing (sidecar {}); gave up {} "
                        + "pending cell(s): those chunks stay empty until reloaded and are not saved as generated",
                stalledMs / 1000, SwissRuntime.cells().sidecarDown() ? "down" : "slow", cells);
    }

    private static boolean waitsForChunk(Thread thread) {
        String chunkCache = ServerChunkCache.class.getName();
        for (StackTraceElement f : thread.getStackTrace()) {
            if (f.getClassName().equals(chunkCache) && f.getMethodName().equals("getChunk")) return true;
        }
        return false;
    }
}
