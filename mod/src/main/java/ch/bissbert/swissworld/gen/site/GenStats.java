package ch.bissbert.swissworld.gen.site;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generation timers: running mean and p95 per bucket (each pipeline stage by name, biomes, the site plan, the whole
 * chunk). Lock-free, since chunks generate in parallel on the worldgen executor. p95 comes from a histogram with four
 * buckets per octave from 1 µs (±10 %). Shown in F3 ({@code addDebugScreenInfo}), {@code /swiss admin stats} and the
 * log every {@code swissworld.stats.every} chunks (default 1000, 0 = never). Owner: mod-siteplan.
 */
public final class GenStats {
    public static final String CHUNK = "chunk", BIOMES = "biomes", PLAN = "plan", PLAN_WAIT = "plan-wait";
    private static final Logger LOG = LoggerFactory.getLogger("swissworld/genstats");
    private static final int BUCKETS = 120;
    private static final long EVERY = Long.getLong("swissworld.stats.every", 1000);
    private static final Map<String, Timer> TIMERS = new ConcurrentHashMap<>();
    /** Buckets in the order they were first recorded (pipeline order). */
    private static final List<String> ORDER = new ArrayList<>();
    private static final AtomicLong CHUNKS = new AtomicLong();

    private GenStats() {}

    /** One timer: count, sum and a log histogram of nanoseconds. */
    public static final class Timer {
        final LongAdder count = new LongAdder(), sum = new LongAdder();
        final AtomicLongArray hist = new AtomicLongArray(BUCKETS);

        void add(long nanos) {
            count.increment();
            sum.add(nanos);
            hist.incrementAndGet(bucket(nanos));
        }

        public long count() {
            return count.sum();
        }

        public double meanMs() {
            long n = count.sum();
            return n == 0 ? 0 : sum.sum() / 1e6 / n;
        }

        /** Upper edge of the bucket holding quantile q, in ms. */
        public double quantileMs(double q) {
            long n = 0;
            long[] h = new long[BUCKETS];
            for (int i = 0; i < BUCKETS; i++) n += h[i] = hist.get(i);
            if (n == 0) return 0;
            long want = (long) Math.ceil(q * n), seen = 0;
            for (int i = 0; i < BUCKETS; i++) {
                seen += h[i];
                if (seen >= want) return edge(i + 1) / 1e6;
            }
            return edge(BUCKETS) / 1e6;
        }

        /** Sum in ms. */
        public double totalMs() {
            return sum.sum() / 1e6;
        }
    }

    static int bucket(long nanos) {
        double us = Math.max(1, nanos / 1000.0);
        return Math.min(BUCKETS - 1, (int) (4 * Math.log(us) / Math.log(2)));
    }

    static double edge(int b) {
        return 1000 * Math.pow(2, b / 4.0);
    }

    public static void record(String name, long nanos) {
        Timer t = TIMERS.get(name);
        if (t == null) {
            synchronized (ORDER) {
                t = TIMERS.computeIfAbsent(name, k -> new Timer());
                if (!ORDER.contains(name)) ORDER.add(name);
            }
        }
        t.add(nanos);
    }

    /** A whole chunk is done; logs the summary every {@code swissworld.stats.every} chunks. */
    public static void chunk(long nanos) {
        record(CHUNK, nanos);
        long n = CHUNKS.incrementAndGet();
        if (EVERY > 0 && n % EVERY == 0) LOG.info("gen stats after {} chunks: {}", n, line());
    }

    public static Timer timer(String name) {
        return TIMERS.get(name);
    }

    public static List<String> names() {
        synchronized (ORDER) {
            return List.copyOf(ORDER);
        }
    }

    public static void reset() {
        synchronized (ORDER) {
            TIMERS.clear();
            ORDER.clear();
            CHUNKS.set(0);
        }
    }

    /** "chunk 4.1/9.3 ms · TerrainStage 1.0/2.2 · ..." (mean/p95 ms per bucket, in pipeline order). */
    public static String line() {
        StringBuilder sb = new StringBuilder();
        Timer c = TIMERS.get(CHUNK);
        if (c != null) sb.append(String.format(Locale.ROOT, "%d chunks %.2f/%.2f ms", c.count(), c.meanMs(), c.quantileMs(0.95)));
        for (String n : names()) {
            if (n.equals(CHUNK)) continue;
            Timer t = TIMERS.get(n);
            sb.append(String.format(Locale.ROOT, " · %s %.2f/%.2f", shortName(n), t.meanMs(), t.quantileMs(0.95)));
            if (n.startsWith(PLAN)) sb.append(" (n ").append(t.count()).append(')');
        }
        return sb.isEmpty() ? "no chunks generated" : sb.toString();
    }

    /** Short form for F3: "gen 4.1/9.3 ms/chunk: Te 1.0 Wa 0.3 ..." (means, ms). */
    public static String compact() {
        Timer c = TIMERS.get(CHUNK);
        if (c == null) return "gen: no chunks yet";
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "gen %.1f/%.1f ms/chunk:", c.meanMs(), c.quantileMs(0.95)));
        for (String n : names()) {
            if (n.equals(CHUNK)) continue;
            String s = shortName(n);
            sb.append(String.format(Locale.ROOT, " %s %.1f", s.substring(0, Math.min(2, s.length())), TIMERS.get(n).meanMs()));
        }
        return sb.toString();
    }

    /** One line per bucket: name, count, mean, p95, total. */
    public static List<String> table() {
        List<String> out = new ArrayList<>();
        out.add("bucket            count    mean ms   p95 ms   total s");
        List<String> order = new ArrayList<>(names());
        order.remove(CHUNK);
        if (TIMERS.containsKey(CHUNK)) order.add(CHUNK);
        for (String n : order) {
            Timer t = TIMERS.get(n);
            out.add(String.format(Locale.ROOT, "%-16s %7d %9.3f %8.3f %9.2f", shortName(n), t.count(), t.meanMs(),
                    t.quantileMs(0.95), t.totalMs() / 1000));
        }
        return out;
    }

    static String shortName(String n) {
        return n.endsWith("Stage") ? n.substring(0, n.length() - 5) : n;
    }
}
