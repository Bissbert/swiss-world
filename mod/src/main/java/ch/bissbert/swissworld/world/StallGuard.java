package ch.bissbert.swissworld.world;

import ch.bissbert.swissworld.SwissConfig;
import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.SwissWorld;
import java.util.List;
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
 * downgraded on save ({@link AbortedChunks}), so they generate properly once they are loaded again. Until that tick
 * ends ({@link #tickEnded}), cells that are not loaded yet are aborted at once, so a large forceload finishes in
 * one go instead of stalling again on every new cell.
 *
 * <p>A chunk an entity moves into is loaded the same way (NeoForge's {@code Entity.setPosRaw} makes sure the target
 * chunk is loaded, and a player's own tick reads the blocks around it): a {@code /tp}, an ender pearl or elytra flight into terrain whose cell is still being built. For
 * those the much shorter {@code playerStallAbortSeconds} applies; the player then stands in an empty placeholder and
 * is held there ({@link TerrainHold}) until {@link PlaceholderRepair} has filled it in. The trade-off: aborting gives
 * up <em>every</em> pending cell, so other chunks waiting at that moment become placeholders too (repaired the same
 * way, or regenerated when next loaded).
 */
public final class StallGuard {
    static final long CHECK_MS = 250;

    /** What the server thread waits for, from its stack. */
    enum Wait { NONE, CHUNK, ENTITY_CHUNK }

    private static ScheduledExecutorService timer;
    private static volatile boolean holding;

    private StallGuard() {}

    public static synchronized void start(MinecraftServer server) {
        stop();
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "swissworld-stall-guard");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> check(server), CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS);
    }

    public static synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    private static void check(MinecraftServer server) {
        if (holding || !server.isRunning() || SwissRuntime.PENDING_CHUNKS.get() == 0) return;
        long stalledMs = (Util.getNanos() - server.getNextTickTime()) / 1_000_000;
        long entityLimitMs = Math.round(SwissConfig.PLAYER_STALL_ABORT_S.get() * 1000);
        long limitMs = SwissConfig.STALL_ABORT_S.get() * 1000L;
        if (stalledMs < Math.min(limitMs <= 0 ? Long.MAX_VALUE : limitMs, entityLimitMs <= 0 ? Long.MAX_VALUE : entityLimitMs)) return;
        Wait wait = classify(server.getRunningThread().getStackTrace());
        long limit = limitMs(wait, limitMs, entityLimitMs);
        if (limit <= 0 || stalledMs < limit) return;
        holding = true;
        int cells = SwissRuntime.cells().abortWaiting();
        SwissWorld.LOGGER.warn("Server thread waited {} s for a {} whose cells are missing (sidecar {}); gave up {} "
                        + "pending cell(s): those chunks are empty placeholders until their cells arrive, never saved",
                String.format(java.util.Locale.ROOT, "%.1f", stalledMs / 1000.0),
                wait == Wait.ENTITY_CHUNK ? "chunk a player or entity needs" : "chunk",
                SwissRuntime.cells().sidecarDown() ? "down" : "slow", cells);
    }

    /** The limit for this kind of wait, ms; 0 = never abort. */
    static long limitMs(Wait wait, long chunkLimitMs, long entityLimitMs) {
        return switch (wait) {
            case NONE -> 0;
            case CHUNK -> chunkLimitMs;
            case ENTITY_CHUNK -> entityLimitMs > 0 ? entityLimitMs : chunkLimitMs;
        };
    }

    /** Server tick end: the stuck tick is over, cells are waited for again. */
    public static void tickEnded() {
        if (!holding) return;
        holding = false;
        SwissRuntime.cells().resumeWaiting();
    }

    /** Callers that load a chunk for an entity or a player: a move, a teleport, an entity's own tick, a login. */
    private static final List<String> ENTITY_CALLERS = List.of(
            "net.minecraft.world.entity.", // Entity.setPosRaw (NeoForge loads the target chunk), Entity.baseTick, ...
            "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.server.network.ServerGamePacketListenerImpl",
            "net.minecraft.server.players.PlayerList");

    /** Whether the stack sits in {@link ServerChunkCache#getChunk}, and whether an entity or player asked for it. */
    static Wait classify(StackTraceElement[] stack) {
        String chunkCache = ServerChunkCache.class.getName();
        boolean chunk = false;
        for (StackTraceElement f : stack) {
            if (!chunk) {
                chunk = f.getClassName().equals(chunkCache) && f.getMethodName().equals("getChunk");
            } else {
                for (String caller : ENTITY_CALLERS) {
                    if (f.getClassName().startsWith(caller)) return Wait.ENTITY_CHUNK;
                }
            }
        }
        return chunk ? Wait.CHUNK : Wait.NONE;
    }
}
