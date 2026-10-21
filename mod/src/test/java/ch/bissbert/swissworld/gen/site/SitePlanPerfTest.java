package ch.bissbert.swissworld.gen.site;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.gen.GenContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Times the site plan of the tiles around the four flight spots (Interlaken Ost, Innertkirchen, forest Rugen, Faulhorn)
 * from real store cells (SWISSWORLD_SWEEP_STORE). Prints ms per tile; SWISSWORLD_PERF_ROUNDS rounds (default 3).
 */
class SitePlanPerfTest {
    /** Tile (tx, tz) of each flight spot, from PipelineGoldenTest's chunk boxes. */
    static final int[][] SPOTS = {{0, -5}, {215, -20}, {-5, 6}, {70, 18}};
    static final String[] NAMES = {"interlaken-ost", "innertkirchen", "forest-rugen", "mountain-faulhorn"};

    @Test
    void planCost() {
        String dir = System.getenv("SWISSWORLD_SWEEP_STORE");
        assumeTrue(dir != null && System.getenv("SWISSWORLD_PERF") != null, "SWISSWORLD_SWEEP_STORE and SWISSWORLD_PERF not set");
        int rounds = Integer.parseInt(System.getenv().getOrDefault("SWISSWORLD_PERF_ROUNDS", "3"));
        Map<Long, Optional<SwdCell>> cache = new ConcurrentHashMap<>();
        GenContext.CellLookup cells = (e, n) -> cache.computeIfAbsent(((long) e << 32) | (n & 0xFFFFFFFFL), k -> {
            Path p = Path.of(dir, "cells", e + "_" + n + ".swd");
            if (!Files.isRegularFile(p)) return Optional.empty();
            try {
                return Optional.of(SwdReader.read(p));
            } catch (IOException x) {
                throw new UncheckedIOException(x);
            }
        }).orElse(null);
        for (int r = 0; r < rounds; r++) {
            double all = 0;
            int n = 0;
            for (int s = 0; s < SPOTS.length; s++) {
                double sum = 0, max = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        SitePlan p = new SitePlan(Pipeline.ORIGIN, cells, SPOTS[s][0] + dx, SPOTS[s][1] + dz);
                        double ms = p.nanos() / 1e6;
                        sum += ms;
                        max = Math.max(max, ms);
                    }
                }
                all += sum;
                n += 9;
                System.out.printf(Locale.ROOT, "round %d %-18s mean %6.1f max %6.1f ms/tile%n", r, NAMES[s], sum / 9, max);
            }
            System.out.printf(Locale.ROOT, "round %d all mean %.1f ms/tile%n", r, all / n);
        }
    }
}
