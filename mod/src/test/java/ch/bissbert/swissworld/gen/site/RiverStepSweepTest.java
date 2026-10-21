package ch.bissbert.swissworld.gen.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import ch.bissbert.swissworld.gen.surface.WaterPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import ch.bissbert.swissworld.gen.SwissChunkGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/**
 * River steps over real cells (SWISSWORLD_SWEEP_STORE): every chunk of the cells in SWISSWORLD_RIVER_CELLS (default:
 * the Aare at Interlaken and Innertkirchen, the Lütschine from Zweilütschinen to the Brienzersee) that holds a river
 * column one or more blocks below a 4-neighbour's water runs through the whole pipeline, at most
 * SWISSWORLD_RIVER_CHUNKS (default 400) of them. In every column the site plan claims as WATER, nothing between its
 * ground G and the top of its water may be solid: the steps are water, not stray rock. Failures go to $RIVER_OUT.
 */
class RiverStepSweepTest {
    static final String CELLS = "2631_1170,2632_1170,2633_1170,2634_1170,2633_1169,2634_1169,2633_1168,2634_1168,"
            + "2633_1167,2634_1167,2634_1166,2635_1166,2635_1165,2636_1165,2635_1164,2636_1164,2657_1171,2658_1171,2659_1171,"
            + "2660_1171,2659_1170,2660_1170";

    interface Hit {
        void at(int x, int y, int z);
    }

    /** Every solid block between G and the top of the water of the chunk's WATER columns. */
    static void scan(SitePlan site, ColumnGrid g, ProtoChunk c, ChunkPos pos, Hit hit) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
            for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
                if (site.claim(x, z) != Claim.WATER || !g.present(x, z)) continue;
                WaterPlan.Fill f = WaterPlan.fill(g, x, z);
                if (f == null) continue;
                int top = Math.max(f.sourceTop(), f.fallTop());
                for (int y = Math.min(site.ground(x, z) + 1, f.floor()); y <= top; y++) {
                    if (c.getBlockState(m.set(x, y, z)).isSolid()) hit.at(x, y, z);
                }
            }
        }
    }

    @Test
    void noSolidBlocksInsideWaterClaims() throws Exception {
        String dir = System.getenv("SWISSWORLD_SWEEP_STORE");
        assumeTrue(dir != null, "SWISSWORLD_SWEEP_STORE not set");
        int max = Integer.parseInt(System.getenv().getOrDefault("SWISSWORLD_RIVER_CHUNKS", "400"));
        Pipeline p = new Pipeline(Path.of(dir));
        // chunks (sorted by tile, then position) with a river column below a neighbour's water
        TreeSet<long[]> steps = new TreeSet<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        int cells = 0;
        for (String k : System.getenv().getOrDefault("SWISSWORLD_RIVER_CELLS", CELLS).split(",")) {
            String[] en = k.trim().split("_");
            SwdCell c = p.cell(Integer.parseInt(en[0]), Integer.parseInt(en[1]));
            if (c == null) continue;
            cells++;
            int bx = (int) ((long) c.eKm() * 1000 - Pipeline.ORIGIN.e0()), bz = (int) (Pipeline.ORIGIN.n0() - (long) c.nKm() * 1000 - 1000);
            for (int dz = 1; dz < 999; dz++) {
                for (int dx = 1; dx < 999; dx++) {
                    if (c.waterKind(dx, dz) != 2) continue;
                    double w = c.water(dx, dz);
                    boolean step = false;
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int kk = c.waterKind(dx + d[0], dz + d[1]);
                        step |= kk != 0 && c.water(dx + d[0], dz + d[1]) >= w + 0.999;
                    }
                    if (!step) continue;
                    int x = bx + dx, z = bz + dz;
                    long tile = ((long) SitePlan.tile(x) << 32) ^ (SitePlan.tile(z) & 0xFFFFFFFFL);
                    steps.add(new long[] {tile, ChunkPos.asLong(x >> 4, z >> 4)});
                }
            }
        }
        Map<String, Integer> hist = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        SitePlan site = null;
        int chunks = 0, columns = 0;
        List<String> stepCols = new ArrayList<>();
        for (long[] s : steps) {
            if (chunks == max) break;
            ChunkPos pos = new ChunkPos(s[1]);
            int tx = SitePlan.tile(pos.getMinBlockX()), tz = SitePlan.tile(pos.getMinBlockZ());
            if (site == null || site.tx() != tx || site.tz() != tz) site = SitePlans.compute(p::cell, Pipeline.ORIGIN, tx, tz);
            ProtoChunk c = Pipeline.chunk(pos);
            GenContext ctx = p.context(c, site);
            ColumnGrid g = site.grid();
            Map<Long, String> by = new HashMap<>();
            for (GenStage st : SwissChunkGenerator.PIPELINE) {
                st.apply(ctx);
                scan(site, g, c, pos, (x, y, z) -> by.putIfAbsent(BlockPos.asLong(x, y, z), st.getClass().getSimpleName()));
            }
            chunks++;
            int[] n = {0};
            scan(site, g, c, pos, (x, y, z) -> {
                WaterPlan.Fill f = WaterPlan.fill(g, x, z);
                BlockState b = c.getBlockState(new BlockPos(x, y, z));
                String kind = (g.hasBuilding(x, z) ? "footprint-" : "") + (f.pour() ? "pour" : f.fallTop() > f.sourceTop() ? "step" : "flat");
                String key = by.get(BlockPos.asLong(x, y, z)) + " " + kind + (g.overlay(x, z) != 0 ? " overlay" + g.overlay(x, z) : "")
                        + " " + EntranceSweepTest.name(b);
                if (hist.merge(key, 1, Integer::sum) <= 3) bad.add(x + "," + y + "," + z + " " + key + " " + f);
            });
            for (int z = pos.getMinBlockZ(); z <= pos.getMaxBlockZ(); z++) {
                for (int x = pos.getMinBlockX(); x <= pos.getMaxBlockX(); x++) {
                    if (site.claim(x, z) == Claim.WATER && g.present(x, z) && WaterPlan.fill(g, x, z) != null) columns++;
                    WaterPlan.Fill f = site.claim(x, z) == Claim.WATER && g.present(x, z) ? WaterPlan.fill(g, x, z) : null;
                    // step columns (water from the floor up to a higher neighbour's level): $RIVER_COLS (in-game probes)
                    if (f != null && f.fallTop() > f.sourceTop()) stepCols.add(x + " " + Math.min(site.ground(x, z) + 1, f.floor()) + " " + Math.max(f.sourceTop(), f.fallTop()) + " " + z);
                }
            }
        }
        System.out.println("river steps: " + cells + " cells, " + steps.size() + " step chunks, " + chunks + " rendered, "
                + columns + " water columns; solid: " + hist);
        Path out = Path.of(System.getenv().getOrDefault("RIVER_OUT", "build/river-steps.txt")).toAbsolutePath();
        Files.createDirectories(out.getParent());
        Files.write(out, bad);
        if (System.getenv("RIVER_COLS") != null) Files.write(Path.of(System.getenv("RIVER_COLS")), stepCols);
        assertEquals(Map.of(), hist);
    }
}
