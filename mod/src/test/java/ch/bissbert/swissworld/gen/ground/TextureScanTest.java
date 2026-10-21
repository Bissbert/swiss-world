package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic (needs {@code SWISSWORLD_STORE} and {@code SWISSWORLD_SCAN=e0,n0,e1,n1} in km): samples a 64 x 64 m
 * patch in the middle of every 500 m tile of the box and prints the tiles with the most bare rock on soft ground.
 */
class TextureScanTest {
    static ColumnGrid.Cells lruCells(Path dir, int cap) {
        Map<Long, SwdCell> cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, SwdCell> e) {
                return size() > cap;
            }
        };
        return (x, z) -> {
            int e = SwissCoords.cellKm(TestWorld.ORIGIN.east(x)), n = SwissCoords.cellKm(TestWorld.ORIGIN.north(z));
            long key = (long) e << 32 | n;
            SwdCell c = cache.get(key);
            if (c != null || cache.containsKey(key)) return c;
            Path f = dir.resolve(e + "_" + n + ".swd");
            try {
                c = Files.exists(f) ? SwdReader.read(f) : null;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            cache.put(key, c);
            return c;
        };
    }

    static boolean rock(Block b) {
        return b == Blocks.STONE || b == Blocks.ANDESITE || b == Blocks.GRANITE || b == Blocks.DIORITE || b == Blocks.CALCITE
                || b == Blocks.SMOOTH_STONE || b == Blocks.COBBLESTONE || b == Blocks.TUFF || b == Blocks.DEEPSLATE
                || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.SANDSTONE || b == Blocks.DRIPSTONE_BLOCK;
    }

    @Test
    void scan() {
        Path store = TestWorld.store();
        String box = System.getenv("SWISSWORLD_SCAN");
        Assumptions.assumeTrue(store != null && box != null, "no SWISSWORLD_STORE / SWISSWORLD_SCAN");
        String[] p = box.split(",");
        int e0 = Integer.parseInt(p[0]), n0 = Integer.parseInt(p[1]), e1 = Integer.parseInt(p[2]), n1 = Integer.parseInt(p[3]);
        ColumnGrid.Cells cells = lruCells(store, 9);
        List<String> rows = new ArrayList<>();
        for (int n = n1 * 2 - 1; n >= n0 * 2; n--) {
            for (int e = e0 * 2; e < e1 * 2; e++) {
                long ce = e * 500L + 250, cn = n * 500L + 250;
                int x = (int) (ce - TestWorld.ORIGIN.east(0)), z = (int) (TestWorld.ORIGIN.north(0) - cn);
                int cx0 = Math.floorDiv(x - 32, 16) * 16, cz0 = Math.floorDiv(z - 32, 16) * 16;
                TestWorld w = new TestWorld(cells);
                int soft = 0, softRock = 0, granite = 0, all = 0, forest = 0, open = 0;
                double slope = 0;
                for (int cz = cz0; cz < cz0 + 64; cz += 16) {
                    for (int cx = cx0; cx < cx0 + 64; cx += 16) {
                        ZoneMap zm = w.chunk(cx, cz);
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                int xx = cx + lx, zz = cz + lz;
                                Zone zone = zm.zone(xx, zz);
                                if (zone.group == Zone.Group.NONE || zone.group == Zone.Group.WATER) continue;
                                all++;
                                slope += zm.slope(xx, zz);
                                Block b = w.get(xx, w.top(xx, zz), zz).getBlock();
                                if (b == Blocks.GRANITE) granite++;
                                if (zone.group == Zone.Group.FOREST) forest++;
                                if (zone.group == Zone.Group.OPEN) open++;
                                if (zone.group == Zone.Group.OPEN || zone.group == Zone.Group.FOREST) {
                                    soft++;
                                    if (rock(b)) softRock++;
                                }
                            }
                        }
                    }
                }
                if (all == 0) continue;
                rows.add(String.format("%7.1f %7.1f  x %6d z %6d  soft %3d%% rockOnSoft %3d%% granite %3d%% forest %3d%% open %3d%% slope %4.1f",
                        ce / 1000.0, cn / 1000.0, x, z, 100 * soft / all, 100 * softRock / Math.max(1, soft), 100 * granite / all,
                        100 * forest / all, 100 * open / all, slope / all));
            }
        }
        rows.forEach(System.out::println);
    }
}
