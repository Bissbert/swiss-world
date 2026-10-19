package ch.bissbert.swissworld.net;

import ch.bissbert.swissworld.SwissConfig;
import ch.bissbert.swissworld.SwissRegistries;
import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.cell.SwissCoords;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Asks the sidecar to build cells ahead of moving players. Owner: mod-core.
 *
 * <p>Once a second the speed and heading of every player in Switzerland are taken from how far they moved; at most
 * every {@code prefetchIntervalSeconds} per player a {@code /prefetch} goes out with the view radius plus a corridor
 * of {@code lookaheadSeconds × speed}. The sidecar's corridor is 60 s × the speed it gets, so the speed is scaled.
 * Nothing is sent while the sidecar is known to be down (the cell cache backs off and retries on its own).
 */
public final class PlayerPrefetch {
    private static final int PERIOD_TICKS = 20;
    /** Faster than this (m/s) is a teleport, not movement. */
    private static final double MAX_SPEED = 120;

    private record Track(double e, double n, int tick, int lastPrefetch) {}

    /** Main thread only. */
    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private PlayerPrefetch() {}

    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (now % PERIOD_TICKS != 0) return;
        ServerLevel level = server.getLevel(SwissRegistries.SWITZERLAND);
        if (level == null || !SwissConfig.PREFETCH.get() || SwissRuntime.cells().sidecarDown()) {
            TRACKS.clear();
            return;
        }
        SwissCoords c = SwissRuntime.coords();
        int interval = SwissConfig.PREFETCH_INTERVAL_S.get() * 20;
        int radius = server.getPlayerList().getViewDistance() * 16 + 256;
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer p : level.players()) {
            UUID id = p.getUUID();
            seen.add(id);
            double e = c.e0() + p.getX(), n = c.n0() - p.getZ();
            Track t = TRACKS.get(id);
            double speed = 0, heading = 0;
            int last = Integer.MIN_VALUE / 2;
            if (t != null) {
                double dt = (now - t.tick()) / 20.0, vE = (e - t.e()) / dt, vN = (n - t.n()) / dt;
                speed = Math.hypot(vE, vN);
                if (speed > MAX_SPEED) speed = 0; // teleported: prefetch around the new position right away
                else last = t.lastPrefetch();
                heading = (Math.toDegrees(Math.atan2(vE, vN)) + 360) % 360;
            }
            if (now - last >= interval) {
                double sent = speed * SwissConfig.LOOKAHEAD_S.get() / 60.0;
                SwissRuntime.sidecar().prefetch(e, n, radius, heading, sent).exceptionally(err -> 0);
                last = now;
            }
            TRACKS.put(id, new Track(e, n, now, last));
        }
        TRACKS.keySet().retainAll(seen);
    }
}
