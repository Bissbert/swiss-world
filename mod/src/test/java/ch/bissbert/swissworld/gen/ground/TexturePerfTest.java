package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Ground stage cost over real cells (needs {@code SWISSWORLD_STORE} and {@code SWISSWORLD_PERF}): zones plus texture
 * plus snow per chunk at four places, 256 x 256 m each, after a warm-up, best of five rounds. Prints PERF lines.
 */
class TexturePerfTest {
    record Spot(String name, long e, long n) {}

    static final Spot[] SPOTS = {
        new Spot("interlaken", 2_632_900, 1_170_600),
        new Spot("innertkirchen", 2_657_000, 1_172_900),
        new Spot("faulhorn", 2_645_800, 1_167_500),
        new Spot("grimsel", 2_668_500, 1_157_500),
    };

    @Test
    void perf() {
        Path store = TestWorld.store();
        Assumptions.assumeTrue(store != null && System.getenv("SWISSWORLD_PERF") != null, "no SWISSWORLD_STORE / SWISSWORLD_PERF");
        ColumnGrid.Cells cells = TestWorld.cells(store);
        for (Spot s : SPOTS) {
            int cx = (int) (s.e - TestWorld.ORIGIN.east(0)), cz = (int) (TestWorld.ORIGIN.north(0) - s.n);
            int x0 = Math.floorDiv(cx - 128, 16) * 16, z0 = Math.floorDiv(cz - 128, 16) * 16;
            double best = Double.MAX_VALUE, bestZones = 0;
            for (int round = 0; round < 6; round++) {
                TestWorld w = new TestWorld(cells);
                long zones = 0, stages = 0;
                int chunks = 0;
                for (int z = z0; z < z0 + 256; z += 16) {
                    for (int x = x0; x < x0 + 256; x += 16) {
                        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) w.baseTop(x + lx, z + lz);
                        long t0 = System.nanoTime();
                        ZoneMap zm = ZoneMap.of(TestWorld.ORIGIN, cells, x, z);
                        long t1 = System.nanoTime();
                        GroundTextureStage.apply(zm, w);
                        SnowStage.apply(zm, w);
                        long t2 = System.nanoTime();
                        zones += t1 - t0;
                        stages += t2 - t1;
                        chunks++;
                    }
                }
                double ms = (zones + stages) / 1e6 / chunks;
                if (round > 0 && ms < best) {
                    best = ms;
                    bestZones = zones / 1e6 / chunks;
                }
            }
            System.out.printf("PERF %-14s %.3f ms/chunk (zones %.3f, texture+snow %.3f)%n", s.name, best, bestZones, best - bestZones);
        }
    }
}
