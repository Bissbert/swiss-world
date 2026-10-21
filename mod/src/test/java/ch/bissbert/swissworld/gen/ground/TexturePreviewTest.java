package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Visual acceptance (needs {@code SWISSWORLD_STORE} and {@code SWISSWORLD_PREVIEWS}): per area a top-down and an
 * oblique preview, named {@code <area>-<SWISSWORLD_PREVIEW_TAG>-top.png} / {@code -oblique.png}, and a summary line:
 * bare rock on soft (open/forest) ground, off-family blocks, stone in the step faces of soil columns.
 */
class TexturePreviewTest {
    /** Centre in LV95 metres, side in metres, the side the oblique view looks from. */
    record Area(String name, long e, long n, int size, char from) {}

    static final Area[] AREAS = {
        new Area("screenshot-grindelwald", 2_648_300, 1_160_800, 384, 'N'),
        new Area("harder-kulm", 2_632_500, 1_171_800, 384, 'S'),
        new Area("innertkirchen-flank", 2_657_000, 1_172_900, 512, 'S'),
        new Area("faulhorn", 2_645_800, 1_167_500, 384, 'N'),
        new Area("grimsel", 2_668_500, 1_157_500, 384, 'S'),
        new Area("oberaar", 2_659_800, 1_155_300, 384, 'N'),
    };

    @Test
    void previews() throws IOException {
        Path store = TestWorld.store();
        String prev = System.getProperty("swissworld.previews", "");
        Assumptions.assumeTrue(store != null && !prev.isEmpty(), "no SWISSWORLD_STORE / SWISSWORLD_PREVIEWS");
        String tag = System.getenv().getOrDefault("SWISSWORLD_PREVIEW_TAG", "after");
        String only = System.getenv().getOrDefault("SWISSWORLD_PREVIEW_ONLY", "");
        ColumnGrid.Cells cells = TextureScanTest.lruCells(store, 12);
        StringBuilder report = new StringBuilder();
        for (Area a : AREAS) {
            if (!only.isEmpty() && !a.name.startsWith(only)) continue;
            report.append(render(a, cells, Path.of(prev), tag));
        }
        System.out.println(report);
        Files.writeString(Path.of(prev, "summary-" + tag + ".txt"), report);
    }

    static String render(Area a, ColumnGrid.Cells cells, Path out, String tag) throws IOException {
        int cx = (int) (a.e - TestWorld.ORIGIN.east(0)), cz = (int) (TestWorld.ORIGIN.north(0) - a.n);
        int n = a.size, x0 = Math.floorDiv(cx - n / 2, 16) * 16, z0 = Math.floorDiv(cz - n / 2, 16) * 16;
        TestWorld w = new TestWorld(cells);
        int soft = 0, softRock = 0, all = 0, rockAll = 0, soilCols = 0, riserStone = 0;
        Map<Block, Integer> mix = new HashMap<>();
        Map<Long, ZoneMap> maps = new HashMap<>();
        Map<String, Integer> cause = new java.util.TreeMap<>(), lithCount = new java.util.TreeMap<>();
        for (int z = z0; z < z0 + n; z += 16) {
            for (int x = x0; x < x0 + n; x += 16) {
                ZoneMap zm = w.chunk(x, z);
                maps.put((long) x << 32 | (z & 0xffffffffL), zm);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        Zone zone = zm.zone(x + lx, z + lz);
                        if (zone.group == Zone.Group.NONE || zone.group == Zone.Group.WATER) continue;
                        Block b = w.get(x + lx, w.top(x + lx, z + lz), z + lz).getBlock();
                        all++;
                        mix.merge(b, 1, Integer::sum);
                        boolean rock = TextureScanTest.rock(b);
                        lithCount.merge(ZoneId.lith(zm.id(x + lx, z + lz)).name(), 1, Integer::sum);
                        if (rock || b == net.minecraft.world.level.block.Blocks.GRAVEL || b == net.minecraft.world.level.block.Blocks.COBBLESTONE) {
                            String why = zone.group + " " + zone;
                            cause.merge(why, 1, Integer::sum);
                        }
                        if (rock) rockAll++;
                        if (zone.group == Zone.Group.OPEN || zone.group == Zone.Group.FOREST) {
                            soft++;
                            if (rock) softRock++;
                        }
                    }
                }
            }
        }
        // step faces of soil columns (inner columns only, neighbours all rendered)
        for (int z = z0 + 1; z < z0 + n - 1; z++) {
            for (int x = x0 + 1; x < x0 + n - 1; x++) {
                int y = w.top(x, z);
                if (!GroundTexture.soil(w.get(x, y, z).getBlock())) continue;
                soilCols++;
                int low = Math.min(Math.min(w.top(x - 1, z), w.top(x + 1, z)), Math.min(w.top(x, z - 1), w.top(x, z + 1)));
                for (int b = y - 1; b > low; b--) {
                    if (TextureScanTest.rock(w.get(x, b, z).getBlock())) {
                        riserStone++;
                        break;
                    }
                }
            }
        }
        // isolated rock tops (all four neighbours soil) on gentle ground
        int gentle = 0, lone = 0;
        for (int z = z0 + 1; z < z0 + n - 1; z++) {
            for (int x = x0 + 1; x < x0 + n - 1; x++) {
                ZoneMap zm = maps.get((long) Math.floorDiv(x, 16) * 16 << 32 | (Math.floorDiv(z, 16) * 16 & 0xffffffffL));
                if (zm.smoothSlope(x, z) >= 25) continue;
                gentle++;
                if (!TextureScanTest.rock(w.get(x, w.top(x, z), z).getBlock())) continue;
                boolean ring = true;
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    ring &= GroundTexture.soil(w.get(x + d[0], w.top(x + d[0], z + d[1]), z + d[1]).getBlock());
                }
                if (ring) lone++;
            }
        }
        Preview.topDown(w, x0, z0, n, 2, out.resolve(a.name + "-" + tag + "-top.png"));
        Preview.oblique(w, x0, z0, n, 3, a.from, out.resolve(a.name + "-" + tag + "-oblique.png"));
        int total = all;
        String top = mix.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(12)
                .map(e -> String.format("%s %.1f", BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath(), 100.0 * e.getValue() / total))
                .collect(Collectors.joining(", "));
        String why = cause.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(8)
                .map(e -> String.format("%s %.1f", e.getKey(), 100.0 * e.getValue() / total)).collect(Collectors.joining(", "));
        String liths = lithCount.entrySet().stream().map(e -> String.format("%s %.0f", e.getKey(), 100.0 * e.getValue() / total))
                .collect(Collectors.joining(", "));
        return String.format("== %s [%s]: rock tops %.1f%% of ground, %.1f%% of soft ground; soil columns with stone in a step face %.2f%%; lone rock on < 25 deg %d of %d%n   %s%n",
                a.name, tag, 100.0 * rockAll / Math.max(1, all), 100.0 * softRock / Math.max(1, soft),
                100.0 * riserStone / Math.max(1, soilCols), lone, gentle, top)
                + String.format("   rock/gravel/cobble tops by cause (%% of ground): %s%n   lith: %s%n", why, liths);
    }
}
