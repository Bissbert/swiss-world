package ch.bissbert.swissworld.net;

import ch.bissbert.swissworld.SwissConfig;
import ch.bissbert.swissworld.SwissRegistries;
import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.Coverage;
import ch.bissbert.swissworld.cell.SwissCoords;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Asks the sidecar to build cells ahead of moving players. Owner: mod-core.
 *
 * <p>Once a second the speed and heading of every player in Switzerland are taken from how far they moved; at most
 * every {@code prefetchIntervalSeconds} per player a {@code /prefetch} goes out with the view radius plus a corridor
 * of {@code lookaheadSeconds × speed}. The sidecar's corridor is 60 s × the speed it gets, so the speed is scaled.
 *
 * <p>On top of that the cells of the one-kilometre ring just ahead of the footprint ({@link #aheadCells}) are requested
 * with priority "prefetch" (below the "now" of waiting chunks), at most {@code prefetchMaxInFlight} requests per player
 * unanswered at once, and decoded into the cell cache as soon as their file exists. The heading comes from the last
 * {@link #HISTORY} one-second samples, which smooths out turns and elytra jitter.
 * Nothing is sent while the sidecar is known to be down (the cell cache backs off and retries on its own).
 */
public final class PlayerPrefetch {
    private static final int PERIOD_TICKS = 20;
    /** Faster than this (m/s) is a teleport, not movement. */
    private static final double MAX_SPEED = 120;
    /** One-second samples the velocity is taken over. */
    static final int HISTORY = 5;
    /** Slower than this (m/s) there is no direction of travel. */
    static final double MIN_AHEAD_SPEED = 2;
    /** Extra metres around the view radius the generator reads (water margin, overlay, vegetation reach). */
    static final int GEN_MARGIN_M = 36;
    static final int MAX_AHEAD_CELLS = 8;
    /** A cell asked for once is not asked for again for this many ticks. */
    private static final int REASK_TICKS = 30 * 20;

    private record Sample(double e, double n, int tick) {}

    /** Main thread, except {@code inFlight}, which request callbacks decrement. */
    private static final class State {
        final Deque<Sample> samples = new ArrayDeque<>();
        final Map<Long, Integer> asked = new HashMap<>();
        final AtomicInteger inFlight = new AtomicInteger();
        List<int[]> ahead = List.of();
        int lastPrefetch = Integer.MIN_VALUE / 2;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private PlayerPrefetch() {}

    /** Mean velocity (E, N) in m/s over the samples, or null with fewer than two or a jump above {@link #MAX_SPEED}. */
    private static double[] velocity(Deque<Sample> samples) {
        if (samples.size() < 2) return null;
        Sample a = samples.peekFirst(), b = samples.peekLast();
        double dt = (b.tick() - a.tick()) / 20.0;
        if (dt <= 0) return null;
        return new double[] {(b.e() - a.e()) / dt, (b.n() - a.n()) / dt};
    }

    /**
     * Cells (LV95 km indices) of the ring around the player's footprint (a square of half-size {@code reachM}) that lie
     * ahead: within 60 degrees of the direction of travel. Nearest first, at most {@link #MAX_AHEAD_CELLS}. Empty if the
     * speed is below {@link #MIN_AHEAD_SPEED}. Pure, so the same position and velocity always give the same list.
     */
    public static List<int[]> aheadCells(double e, double n, double vE, double vN, double reachM) {
        double speed = Math.hypot(vE, vN);
        if (speed < MIN_AHEAD_SPEED) return List.of();
        double dE = vE / speed, dN = vN / speed;
        int e0 = (int) Math.floor((e - reachM) / 1000), e1 = (int) Math.floor((e + reachM) / 1000);
        int n0 = (int) Math.floor((n - reachM) / 1000), n1 = (int) Math.floor((n + reachM) / 1000);
        List<double[]> found = new ArrayList<>();
        for (int ce = e0 - 1; ce <= e1 + 1; ce++) {
            for (int cn = n0 - 1; cn <= n1 + 1; cn++) {
                if (ce >= e0 && ce <= e1 && cn >= n0 && cn <= n1) continue;
                double rE = ce * 1000 + 500 - e, rN = cn * 1000 + 500 - n, len = Math.hypot(rE, rN);
                double along = rE * dE + rN * dN;
                if (along <= 0 || along < 0.5 * len) continue;
                found.add(new double[] {ce, cn, along, len});
            }
        }
        found.sort(Comparator.<double[]>comparingDouble(c -> c[2]).thenComparingDouble(c -> c[3]).thenComparingDouble(c -> c[0]).thenComparingDouble(c -> c[1]));
        List<int[]> out = new ArrayList<>();
        for (double[] c : found) {
            if (out.size() == MAX_AHEAD_CELLS) break;
            out.add(new int[] {(int) c[0], (int) c[1]});
        }
        return out;
    }

    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (now % PERIOD_TICKS != 0) return;
        ServerLevel level = server.getLevel(SwissRegistries.SWITZERLAND);
        if (level == null || !SwissConfig.PREFETCH.get() || SwissRuntime.cells().sidecarDown()) {
            STATES.clear();
            return;
        }
        SwissCoords c = SwissRuntime.coords();
        int interval = SwissConfig.PREFETCH_INTERVAL_S.get() * 20;
        int viewM = server.getPlayerList().getViewDistance() * 16;
        int radius = viewM + 256;
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer p : level.players()) {
            UUID id = p.getUUID();
            seen.add(id);
            double e = c.e0() + p.getX(), n = c.n0() - p.getZ();
            State st = STATES.computeIfAbsent(id, k -> new State());
            Sample prev = st.samples.peekLast();
            double speed = 0, heading = 0;
            boolean teleported = false;
            if (prev != null) {
                double dt = (now - prev.tick()) / 20.0, vE = (e - prev.e()) / dt, vN = (n - prev.n()) / dt;
                if (Math.hypot(vE, vN) > MAX_SPEED) teleported = true; // prefetch around the new position right away
            }
            if (teleported) {
                st.samples.clear();
                st.lastPrefetch = Integer.MIN_VALUE / 2;
                st.ahead = List.of();
            }
            st.samples.addLast(new Sample(e, n, now));
            while (st.samples.size() > HISTORY) st.samples.pollFirst();
            double[] v = velocity(st.samples);
            if (v != null) {
                speed = Math.hypot(v[0], v[1]);
                heading = (Math.toDegrees(Math.atan2(v[0], v[1])) + 360) % 360;
            }
            if (now - st.lastPrefetch >= interval) {
                double sent = speed * SwissConfig.LOOKAHEAD_S.get() / 60.0;
                SwissRuntime.sidecar().prefetch(e, n, radius, heading, sent).exceptionally(err -> 0);
                st.lastPrefetch = now;
            }
            if (v != null) {
                st.ahead = aheadCells(e, n, v[0], v[1], viewM + GEN_MARGIN_M);
            } else {
                st.ahead = List.of();
            }
            requestAhead(st, now);
        }
        STATES.keySet().retainAll(seen);
    }

    private static void requestAhead(State st, int now) {
        if (st.ahead.isEmpty()) return;
        CellCache cells = SwissRuntime.cells();
        st.asked.values().removeIf(t -> now - t >= REASK_TICKS);
        List<int[]> ask = new ArrayList<>();
        for (int[] cell : st.ahead) {
            if (cells.getIfPresent(cell[0], cell[1]) != null || cells.coverageState(cell[0], cell[1]) == Coverage.OUTSIDE) continue;
            if (cells.warm(cell[0], cell[1])) continue; // its file is there already: now it is being decoded
            if (st.asked.containsKey(CellCache.key(cell[0], cell[1]))) continue;
            ask.add(cell);
        }
        if (ask.isEmpty() || st.inFlight.get() >= SwissConfig.PREFETCH_MAX_IN_FLIGHT.get()) return;
        st.inFlight.incrementAndGet();
        for (int[] cell : ask) st.asked.put(CellCache.key(cell[0], cell[1]), now);
        SwissRuntime.sidecar().request(ask, "prefetch").whenComplete((r, err) -> st.inFlight.decrementAndGet());
    }
}
