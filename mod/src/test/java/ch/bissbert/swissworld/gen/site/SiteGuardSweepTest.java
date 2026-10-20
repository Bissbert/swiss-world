package ch.bissbert.swissworld.gen.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/**
 * Runs the whole pipeline over the playtest spots (and SWISSWORLD_SWEEP_CHUNKS more chunks around each) of real store
 * cells (SWISSWORLD_SWEEP_STORE) and asserts that no stage writes where the site plan does not give it room: every
 * stage adapts to the claims itself, the guard ({@link SiteWriter}) never has to drop a write.
 */
class SiteGuardSweepTest {
    @Test
    void noStageWritesOutsideItsClaims() {
        String dir = System.getenv("SWISSWORLD_SWEEP_STORE");
        assumeTrue(dir != null, "SWISSWORLD_SWEEP_STORE not set");
        int grow = Integer.parseInt(System.getenv().getOrDefault("SWISSWORLD_SWEEP_CHUNKS", "0"));
        Pipeline p = new Pipeline(Path.of(dir));
        Map<String, Integer> hist = new TreeMap<>();
        Map<String, String> example = new HashMap<>();
        SiteWriter.observer = (stage, x, y, z, claim, g) -> {
            String k = stage + " in " + claim + " at G" + bucket(y - g);
            synchronized (hist) {
                hist.merge(k, 1, Integer::sum);
                example.putIfAbsent(k, x + "," + y + "," + z);
            }
        };
        Map<Long, SitePlan> plans = new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, SitePlan> e) {
                return size() > 4; // a test JVM's heap holds a few plans, not a whole sweep's
            }
        };
        java.util.Set<Long> tiles = new java.util.HashSet<>();
        int chunks = 0;
        try {
            for (int[] a : PipelineGoldenTest.AREAS) {
                for (int cz = a[1] - grow; cz <= a[3] + grow; cz++) {
                    for (int cx = a[0] - grow; cx <= a[2] + grow; cx++) {
                        ChunkPos pos = new ChunkPos(cx, cz);
                        int tx = SitePlan.tile(pos.getMinBlockX()), tz = SitePlan.tile(pos.getMinBlockZ());
                        SitePlan site = plans.computeIfAbsent(((long) tx << 32) ^ (tz & 0xFFFFFFFFL),
                                k -> SitePlans.compute(p::cell, Pipeline.ORIGIN, tx, tz));
                        tiles.add(((long) tx << 32) ^ (tz & 0xFFFFFFFFL));
                        ProtoChunk c = Pipeline.chunk(pos);
                        Pipeline.run(p.context(c, site));
                        chunks++;
                    }
                }
            }
        } finally {
            SiteWriter.observer = null;
        }
        List<String> lines = new ArrayList<>();
        hist.forEach((k, v) -> lines.add(k + ": " + v + " (e.g. " + example.get(k) + ")"));
        System.out.println("guard sweep: " + chunks + " chunks, " + tiles.size() + " tiles");
        lines.forEach(System.out::println);
        assertEquals(List.of(), lines, "writes the site plan does not allow");
    }

    private static String bucket(int d) {
        if (d < -3) return "-4..";
        if (d > 6) return "+7..";
        return (d >= 0 ? "+" : "") + d;
    }
}
