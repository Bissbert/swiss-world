package ch.bissbert.swissworld.gen.ground;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The ground stages over real cells (a private store copy, {@code SWISSWORLD_STORE}; skipped without one): block mix
 * per zone group and zone shares per area, timing, and with {@code SWISSWORLD_PREVIEWS} set a top-down preview per area
 * (left: blocks by map colour with hillshade; right: zones, snow hatched white, the snow-line contour red).
 */
class GroundSweepTest {
    record Area(String name, int cx, int cz, int size) {}

    static final Area[] AREAS = {
        new Area("forest-slope", -1400, -900, 256),
        new Area("alpine-grimsel", 35600, 13099, 256),
        new Area("river-bank-aare", 1028, -865, 256),
        new Area("interlaken-meadow", 0, 0, 256),
        new Area("oberaar-snowline", 26900, 15299, 512),
    };

    @Test
    void sweep() throws IOException {
        Path store = TestWorld.store();
        Assumptions.assumeTrue(store != null, "no SWISSWORLD_STORE");
        String prev = System.getProperty("swissworld.previews", "");
        StringBuilder report = new StringBuilder();
        for (Area a : AREAS) report.append(run(a, store, prev.isEmpty() ? null : Path.of(prev)));
        System.out.println(report);
        if (!prev.isEmpty()) Files.writeString(Path.of(prev, "texture-summary.txt"), report);
    }

    private static String run(Area a, Path store, Path out) throws IOException {
        TestWorld w = new TestWorld(TestWorld.cells(store));
        int x0 = Math.floorDiv(a.cx - a.size / 2, 16) * 16, z0 = Math.floorDiv(a.cz - a.size / 2, 16) * 16, n = a.size;
        Zone[] zones = new Zone[n * n];
        int[] snow = new int[n * n];
        double[] line = new double[n * n];
        long nanos = 0;
        int chunks = 0;
        Map<Zone.Group, Map<Block, Integer>> mix = new EnumMap<>(Zone.Group.class);
        Map<Zone, Integer> zoneCount = new EnumMap<>(Zone.class);
        for (int cz = z0; cz < z0 + n; cz += 16) {
            for (int cx = x0; cx < x0 + n; cx += 16) {
                for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) w.baseTop(cx + lx, cz + lz);
                long t0 = System.nanoTime();
                ZoneMap zm = w.chunk(cx, cz);
                nanos += System.nanoTime() - t0;
                chunks++;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx + lx, z = cz + lz, k = (z - z0) * n + (x - x0);
                        Zone zone = zm.zone(x, z);
                        zones[k] = zone;
                        snow[k] = zm.snow(x, z);
                        line[k] = zm.metres(x, z) - ClimateLines.snowLine(zm.east(x), zm.north(z));
                        if (zone == Zone.NONE) continue;
                        zoneCount.merge(zone, 1, Integer::sum);
                        int y = w.top(x, z);
                        Block top = w.get(x, y, z).getBlock();
                        // what lies under the snow counts as the zone's ground
                        if (snow[k] > 0 && !zone.glacier()) {
                            for (int d = 1; d <= 4 && isSnow(top); d++) top = w.get(x, y - d, z).getBlock();
                        }
                        mix.computeIfAbsent(zone.group, g -> new HashMap<>()).merge(top, 1, Integer::sum);
                    }
                }
            }
        }
        int total = zoneCount.values().stream().mapToInt(Integer::intValue).sum();
        int snowy = 0;
        for (int s : snow) if (s > 0) snowy++;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("== %s (%d x %d m at %d,%d): %.3f ms/chunk zones+texture+snow, snow on %.1f%%%n", a.name, n, n,
                a.cx, a.cz, nanos / 1e6 / chunks, 100.0 * snowy / Math.max(1, total)));
        sb.append("zones: ").append(top(zoneCount, total, 12)).append('\n');
        for (Map.Entry<Zone.Group, Map<Block, Integer>> e : mix.entrySet()) {
            int t = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            sb.append(String.format("  %-6s %5.1f%%: %s%n", e.getKey(), 100.0 * t / total, top(e.getValue(), t, 8)));
        }
        assertTrue(total > 0, a.name + " has no ground");
        if (out != null) image(a, w, x0, z0, zones, snow, line, out);
        return sb.toString();
    }

    private static boolean isSnow(Block b) {
        return b == net.minecraft.world.level.block.Blocks.SNOW || b == net.minecraft.world.level.block.Blocks.SNOW_BLOCK;
    }

    private static <K> String top(Map<K, Integer> m, int total, int limit) {
        return m.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(limit)
                .map(e -> String.format("%s %.1f%%", name(e.getKey()), 100.0 * e.getValue() / total))
                .collect(Collectors.joining(", "));
    }

    private static String name(Object k) {
        return k instanceof Block b ? BuiltInRegistries.BLOCK.getKey(b).getPath() : k.toString().toLowerCase();
    }

    private static final Map<Zone, Integer> ZONE_RGB = new LinkedHashMap<>();

    static {
        for (Zone z : Zone.values()) {
            int rgb = switch (z.group) {
                case OPEN -> 0x9ccc65;
                case URBAN -> 0x9e9e9e;
                case FOREST -> 0x2e7d32;
                case ROCK -> 0x8d6e63;
                case LOOSE -> 0xbcaaa4;
                case SNOW -> 0xe3f2fd;
                case ICE -> 0x81d4fa;
                case WATER -> 0x1565c0;
                default -> 0x000000;
            };
            // shade the zones of a group apart
            int v = (z.ordinal() * 37) % 5 - 2;
            int r = clamp(((rgb >> 16) & 255) + v * 14), g = clamp(((rgb >> 8) & 255) + v * 14), b = clamp((rgb & 255) + v * 14);
            ZONE_RGB.put(z, r << 16 | g << 8 | b);
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static void image(Area a, TestWorld w, int x0, int z0, Zone[] zones, int[] snow, double[] line, Path out)
            throws IOException {
        int n = a.size, s = n <= 256 ? 3 : 2;
        BufferedImage img = new BufferedImage(2 * n * s + 8, n * s, BufferedImage.TYPE_INT_RGB);
        int[] h = new int[n * n];
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) h[z * n + x] = w.top(x0 + x, z0 + z);
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int k = z * n + x;
                int hx = h[z * n + Math.min(n - 1, x + 1)] - h[z * n + Math.max(0, x - 1)];
                int hz = h[Math.min(n - 1, z + 1) * n + x] - h[Math.max(0, z - 1) * n + x];
                double shade = Math.max(0.55, Math.min(1.25, 1 - 0.06 * (hx + hz)));
                BlockState st = w.get(x0 + x, h[k], z0 + z);
                int rgb = st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
                if (!st.getFluidState().isEmpty()) rgb = 0x3f76e4;
                int left = mul(rgb, shade);
                int right = mul(ZONE_RGB.get(zones[k]), shade);
                if (snow[k] > 0 && ((x + z) & 3) == 0) right = 0xffffff;
                // the regional snow line (flat ground), as a contour
                if (x > 0 && Math.signum(line[k]) != Math.signum(line[k - 1]) || z > 0 && Math.signum(line[k]) != Math.signum(line[k - n])) {
                    right = 0xe53935;
                }
                for (int dz = 0; dz < s; dz++) {
                    for (int dx = 0; dx < s; dx++) {
                        img.setRGB(x * s + dx, z * s + dz, left);
                        img.setRGB(n * s + 8 + x * s + dx, z * s + dz, right);
                    }
                }
            }
        }
        Files.createDirectories(out);
        ImageIO.write(img, "png", out.resolve("texture-" + a.name + ".png").toFile());
    }

    private static int mul(int rgb, double f) {
        int r = clamp((int) (((rgb >> 16) & 255) * f)), g = clamp((int) (((rgb >> 8) & 255) * f)), b = clamp((int) ((rgb & 255) * f));
        return r << 16 | g << 8 | b;
    }
}
