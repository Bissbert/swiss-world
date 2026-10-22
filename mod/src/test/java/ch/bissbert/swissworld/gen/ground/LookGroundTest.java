package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic for the ground look at given spots (needs {@code SWISSWORLD_STORE}, {@code SWISSWORLD_PREVIEWS},
 * {@code SWISSWORLD_LOOK=name:E:N:size:from,...}, optional {@code SWISSWORLD_PREVIEW_TAG}): per spot a top-down view
 * (blocks), a GMAT class map, an oblique view and a block / GMAT / slope breakdown in {@code look-<tag>.txt}.
 */
class LookGroundTest {
    private static final int[] GM = {0x202020, 0x1e5a1e, 0x66cc44, 0xd8c040, 0xa0603c, 0xe0e0e0, 0x505058, 0xb0a090, 0xffffff, 0x3060e0, 0xff40ff};

    @Test
    void look() throws IOException {
        Path store = TestWorld.store();
        String prev = System.getProperty("swissworld.previews", "");
        String spec = System.getenv("SWISSWORLD_LOOK");
        Assumptions.assumeTrue(store != null && !prev.isEmpty() && spec != null, "no SWISSWORLD_STORE / PREVIEWS / LOOK");
        String tag = System.getenv().getOrDefault("SWISSWORLD_PREVIEW_TAG", "after");
        ColumnGrid.Cells cells = TextureScanTest.lruCells(store, 12);
        StringBuilder rep = new StringBuilder();
        for (String s : spec.split(",")) {
            String[] p = s.split(":");
            rep.append(one(p[0], Long.parseLong(p[1]), Long.parseLong(p[2]), Integer.parseInt(p[3]), p[4].charAt(0), cells, Path.of(prev), tag));
        }
        System.out.println(rep);
        Files.writeString(Path.of(prev, "look-" + tag + ".txt"), rep.toString());
    }

    static String one(String name, long e, long n, int size, char from, ColumnGrid.Cells cells, Path out, String tag) throws IOException {
        int cx = (int) (e - TestWorld.ORIGIN.east(0)), cz = (int) (TestWorld.ORIGIN.north(0) - n);
        int x0 = Math.floorDiv(cx - size / 2, 16) * 16, z0 = Math.floorDiv(cz - size / 2, 16) * 16;
        TestWorld w = new TestWorld(cells);
        Map<Long, ZoneMap> maps = new HashMap<>();
        for (int z = z0; z < z0 + size; z += 16) {
            for (int x = x0; x < x0 + size; x += 16) maps.put((long) x << 32 | (z & 0xffffffffL), w.chunk(x, z));
        }
        Map<String, Integer> byBlock = new TreeMap<>(), byGmat = new TreeMap<>(), gmatBlock = new TreeMap<>(), bySlope = new TreeMap<>();
        int all = 0;
        BufferedImage gm = new BufferedImage(size * 6, size * 6, BufferedImage.TYPE_INT_RGB);
        for (int z = z0; z < z0 + size; z++) {
            for (int x = x0; x < x0 + size; x++) {
                ZoneMap zm = maps.get((long) Math.floorDiv(x, 16) * 16 << 32 | (Math.floorDiv(z, 16) * 16 & 0xffffffffL));
                Zone zone = zm.zone(x, z);
                if (zone.group == Zone.Group.NONE || zone.group == Zone.Group.WATER) continue;
                int cls = zm.gmat(x, z) & 15;
                Block b = w.get(x, w.top(x, z), z).getBlock();
                String bn = BuiltInRegistries.BLOCK.getKey(b).getPath();
                all++;
                byBlock.merge(bn, 1, Integer::sum);
                byGmat.merge("gmat" + cls, 1, Integer::sum);
                if (b == Blocks.COARSE_DIRT || b == Blocks.DIRT || b == Blocks.ROOTED_DIRT) {
                    gmatBlock.merge("gmat" + cls + "+" + bn, 1, Integer::sum);
                    String why = zm.railDist(x, z) <= 2 ? "rail" : zm.pathDist(x, z) <= 2 ? "pathEdge" : zm.waterDist(x, z) <= 2 ? "shore" : zone.name();
                    gmatBlock.merge("cause:" + why + "/gmat" + cls, 1, Integer::sum);
                    bySlope.merge(String.format("%02d-deg dirt", (int) (zm.smoothSlope(x, z) / 10) * 10), 1, Integer::sum);
                }
                int c = GM[Math.min(cls, 10)];
                for (int dz = 0; dz < 6; dz++) for (int dx = 0; dx < 6; dx++) gm.setRGB((x - x0) * 6 + dx, (z - z0) * 6 + dz, c);
            }
        }
        Files.createDirectories(out);
        ImageIO.write(gm, "png", out.resolve(name + "-" + tag + "-gmat.png").toFile());
        Preview.topDown(w, x0, z0, size, 6, out.resolve(name + "-" + tag + "-top.png"));
        Preview.oblique(w, x0, z0, size, 8, from, out.resolve(name + "-" + tag + "-oblique.png"));
        int total = all;
        StringBuilder sb = new StringBuilder("== " + name + " [" + tag + "] " + all + " ground columns\n");
        for (var m : new Map[] {byBlock, byGmat, gmatBlock, bySlope}) {
            StringBuilder l = new StringBuilder("  ");
            ((Map<String, Integer>) m).entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(18)
                    .forEach(en -> l.append(String.format("%s %.1f, ", en.getKey(), 100.0 * en.getValue() / total)));
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
