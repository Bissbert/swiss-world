package ch.bissbert.swissworld.gen.site;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/**
 * Dev tool (skipped unless SWISSWORLD_LOOK_VIEWS is set): generates the chunks around each view with the real pipeline
 * (all stages, building included) and ray-casts a first-person picture like the game's screenshot, plus a top-down map.
 * View file: one "name x y z yaw pitch radius" per line (x y z = F3 block position of the player, feet; yaw/pitch as F3).
 * Output: SWISSWORLD_LOOK_OUT/<name>.png and <name>-top.png.
 */
class LookViewTest {
    private final Map<Long, ProtoChunk> chunks = new HashMap<>();
    private Pipeline pipe;
    private final Map<Long, SitePlan> sites = new HashMap<>();

    private ProtoChunk chunk(int cx, int cz) {
        return chunks.computeIfAbsent(((long) cx << 32) | (cz & 0xffffffffL), k -> {
            ChunkPos pos = new ChunkPos(cx, cz);
            ProtoChunk c = Pipeline.chunk(pos);
            int tx = SitePlan.tile(pos.getMinBlockX()), tz = SitePlan.tile(pos.getMinBlockZ());
            SitePlan s = sites.computeIfAbsent(((long) tx << 32) | (tz & 0xffffffffL),
                    kk -> SitePlans.compute(pipe::cell, Pipeline.ORIGIN, tx, tz));
            Pipeline.run(pipe.context(c, s));
            return c;
        });
    }

    BlockState at(int x, int y, int z) {
        ProtoChunk c = chunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        return c.getBlockState(new BlockPos(x, y, z));
    }

    @Test
    void views() throws Exception {
        String views = System.getenv("SWISSWORLD_LOOK_VIEWS"), out = System.getenv("SWISSWORLD_LOOK_OUT"),
                store = System.getenv("SWISSWORLD_LOOK_STORE");
        assumeTrue(views != null && out != null && store != null, "SWISSWORLD_LOOK_* not set");
        pipe = new Pipeline(Path.of(store));
        Files.createDirectories(Path.of(out));
        List<String> lines = Files.readAllLines(Path.of(views));
        for (String l : lines) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] a = l.split("\\s+");
            String name = a[0];
            if (name.equals("slice")) { // slice name axis fixed from to ylo yhi   (axis x: varies x at z=fixed)
                slice(Path.of(out, "slice-" + a[1] + ".txt"), a[2].equals("x"), Integer.parseInt(a[3]), Integer.parseInt(a[4]),
                        Integer.parseInt(a[5]), Integer.parseInt(a[6]), Integer.parseInt(a[7]));
                continue;
            }
            if (name.equals("dump")) {
                dump(Path.of(out, "dump-" + a[1] + ".txt"), Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]),
                        Integer.parseInt(a[5]));
                continue;
            }
            double x = Double.parseDouble(a[1]), y = Double.parseDouble(a[2]), z = Double.parseDouble(a[3]);
            double yaw = Double.parseDouble(a[4]), pitch = Double.parseDouble(a[5]);
            int radius = Integer.parseInt(a[6]);
            int w = a.length > 7 ? Integer.parseInt(a[7]) : 1200, h = (int) (w * 0.54);
            double fov = a.length > 8 ? Double.parseDouble(a[8]) : 70;
            render(Path.of(out, name + ".png"), x, y + 1.62, z, yaw, pitch, radius, w, h, fov);
            top(Path.of(out, name + "-top.png"), (int) Math.floor(x), (int) Math.floor(z), Math.min(radius, 70));
            System.out.println("look: " + name + " done, chunks " + chunks.size());
        }
    }

    void slice(Path file, boolean alongX, int fixed, int from, int to, int ylo, int yhi) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.util.List<String> legend = new java.util.ArrayList<>();
        for (int y = yhi; y >= ylo; y--) {
            sb.append(String.format("%4d ", y));
            for (int i = from; i <= to; i++) {
                BlockState st = alongX ? at(i, y, fixed) : at(fixed, y, i);
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                char c = st.isAir() ? '.' : switch (id) {
                    case "smooth_stone" -> 'S'; case "smooth_stone_slab" -> 's'; case "stone" -> 't'; case "grass_block" -> 'g';
                    case "gray_concrete" -> 'G'; case "light_gray_concrete" -> 'L'; case "spruce_planks" -> 'P';
                    case "dark_oak_planks" -> 'D'; case "andesite" -> 'a'; case "polished_andesite" -> 'A'; case "gravel" -> 'v';
                    case "coarse_dirt" -> 'c'; case "dirt" -> 'd'; case "stone_bricks" -> 'B'; case "granite" -> 'r';
                    case "water" -> 'w'; case "cobblestone" -> 'o'; case "glass_pane" -> '|'; case "polished_andesite_stairs" -> '<';
                    default -> {
                        int ix = legend.indexOf(id);
                        if (ix < 0) { legend.add(id); ix = legend.size() - 1; }
                        yield (char) ('0' + ix);
                    }
                };
                sb.append(c);
            }
            sb.append('\n');
        }
        for (int i = 0; i < legend.size(); i++) sb.append(i).append(" = ").append(legend.get(i)).append('\n');
        Files.writeString(file, sb.toString());
    }

    /** Text map of x0..x1 by z0..z1: top y as base-36 offset from the lowest, then the top block's initial. */
    void dump(Path file, int x0, int z0, int x1, int z1) throws Exception {
        StringBuilder sb = new StringBuilder();
        int[][] ys = new int[z1 - z0 + 1][x1 - x0 + 1];
        int min = Integer.MAX_VALUE;
        int base = topY((x0 + x1) / 2, (z0 + z1) / 2, 1500);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                ys[z - z0][x - x0] = topY(x, z, base + 60);
                min = Math.min(min, ys[z - z0][x - x0]);
            }
        }
        sb.append("min y ").append(min).append("; rows z, cols x from ").append(x0).append("\n");
        for (int z = z0; z <= z1; z++) {
            sb.append(String.format("%5d ", z));
            for (int x = x0; x <= x1; x++) {
                int y = ys[z - z0][x - x0];
                BlockState st = at(x, y, z);
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                char c = switch (id) {
                    case "smooth_stone" -> 'S'; case "smooth_stone_slab" -> 's'; case "stone" -> 't'; case "grass_block" -> 'g';
                    case "gray_concrete" -> 'G'; case "light_gray_concrete" -> 'L'; case "spruce_planks" -> 'P';
                    case "dark_oak_planks" -> 'D'; case "andesite" -> 'a'; case "polished_andesite" -> 'A'; case "gravel" -> 'v';
                    case "coarse_dirt" -> 'c'; case "dirt" -> 'd'; case "stone_bricks" -> 'B'; case "granite" -> 'r';
                    case "water" -> 'w'; case "cobblestone" -> 'o'; default -> '?';
                };
                sb.append(c).append(Character.forDigit(Math.floorMod(y - min, 36), 36));
            }
            sb.append('\n');
        }
        Files.writeString(file, sb.toString());
    }

    static int colour(BlockState s) {
        if (s.is(Blocks.GRASS_BLOCK)) return 0x6f9a43;
        if (s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.SMOOTH_STONE_SLAB)) return 0xa4a4a4;
        if (s.is(Blocks.STONE)) return 0x7d7d7d;
        if (s.is(Blocks.WATER)) return 0x3f76e4;
        if (s.is(Blocks.SPRUCE_PLANKS) || s.is(Blocks.SPRUCE_SLAB) || s.is(Blocks.SPRUCE_STAIRS)) return 0x7a5a30;
        if (s.is(Blocks.DARK_OAK_PLANKS) || s.is(Blocks.DARK_OAK_SLAB) || s.is(Blocks.DARK_OAK_STAIRS)) return 0x4a2f12;
        if (s.is(Blocks.GRANITE)) return 0x956755;
        if (s.is(Blocks.COBBLESTONE)) return 0x7f7f7f;
        if (s.is(Blocks.COARSE_DIRT)) return 0x77553b;
        if (s.is(Blocks.DIRT)) return 0x866043;
        if (s.is(Blocks.GRAVEL)) return 0x837f7e;
        if (s.is(Blocks.SAND)) return 0xdbcfa3;
        if (s.is(Blocks.ANDESITE) || s.is(Blocks.POLISHED_ANDESITE)) return 0x888889;
        if (s.is(Blocks.LIGHT_GRAY_CONCRETE)) return 0x9d9d97;
        if (s.is(Blocks.GRAY_CONCRETE)) return 0x36393d;
        if (s.is(Blocks.WHITE_CONCRETE)) return 0xe0e4e4;
        if (s.is(Blocks.GLASS) || s.is(Blocks.GLASS_PANE)) return 0xb0d8e8;
        return s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
    }

    void render(Path file, double ex, double ey, double ez, double yaw, double pitch, int radius, int w, int h, double fovDeg)
            throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double fx = -Math.sin(yr) * Math.cos(pr), fy = -Math.sin(pr), fz = Math.cos(yr) * Math.cos(pr);
        // screen right in MC: (-cos yaw, 0, -sin yaw); up = right x forward
        double rx = -Math.cos(yr), rz = -Math.sin(yr);
        double upx = 0 * fz - rz * fy, upy = rz * fx - rx * fz, upz = rx * fy - 0 * fx;
        double len = Math.sqrt(upx * upx + upy * upy + upz * upz);
        upx /= len;
        upy /= len;
        upz /= len;
        double tanh = Math.tan(Math.toRadians(fovDeg) / 2), aspect = (double) w / h;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                double sx = ((px + 0.5) / w * 2 - 1) * tanh * aspect, sy = (1 - (py + 0.5) / h * 2) * tanh;
                double dx = fx + rx * sx + upx * sy, dy = fy + upy * sy, dz = fz + rz * sx + upz * sy;
                double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
                img.setRGB(px, py, cast(ex, ey, ez, dx / n, dy / n, dz / n, radius));
            }
        }
        ImageIO.write(img, "png", file.toFile());
    }

    int cast(double ox, double oy, double oz, double dx, double dy, double dz, int maxDist) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? 1e30 : Math.abs(1 / dx), tdy = dy == 0 ? 1e30 : Math.abs(1 / dy), tdz = dz == 0 ? 1e30 : Math.abs(1 / dz);
        double tmx = dx == 0 ? 1e30 : ((dx > 0 ? x + 1 - ox : ox - x)) * tdx;
        double tmy = dy == 0 ? 1e30 : ((dy > 0 ? y + 1 - oy : oy - y)) * tdy;
        double tmz = dz == 0 ? 1e30 : ((dz > 0 ? z + 1 - oz : oz - z)) * tdz;
        int face = 1;
        double t = 0;
        double waterT = -1;
        int waterCol = 0;
        for (int i = 0; i < maxDist * 3 && t < maxDist; i++) {
            if (tmx < tmy && tmx < tmz) {
                x += sx; t = tmx; tmx += tdx; face = 0;
            } else if (tmy < tmz) {
                y += sy; t = tmy; tmy += tdy; face = 1;
            } else {
                z += sz; t = tmz; tmz += tdz; face = 2;
            }
            if (y < -2000 || y > 2000) break;
            BlockState s = at(x, y, z);
            if (s.isAir() || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS)) continue;
            int c = colour(s);
            boolean water = s.is(Blocks.WATER);
            double shade = face == 1 ? (sy < 0 ? 1.0 : 0.5) : face == 0 ? 0.75 : 0.85;
            // texture noise + edges
            double hx = ox + dx * t, hy = oy + dy * t, hz = oz + dz * t;
            double fxp = hx - Math.floor(hx), fyp = hy - Math.floor(hy), fzp = hz - Math.floor(hz);
            double e = Math.min(Math.min(Math.min(fxp, 1 - fxp), Math.min(fyp, 1 - fyp)), Math.min(fzp, 1 - fzp));
            int hh = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
            shade *= 0.94 + ((hh >>> 8) & 15) / 16.0 * 0.1;
            if (face == 1 && (fxp < 0.03 || fxp > 0.97 || fzp < 0.03 || fzp > 0.97)) shade *= 0.8;
            else if (face != 1 && (e < 0.03)) shade *= 0.8;
            double fog = Math.min(1, Math.max(0, (t - maxDist * 0.6) / (maxDist * 0.4)));
            int r = (int) (((c >> 16) & 255) * shade), g = (int) (((c >> 8) & 255) * shade), b = (int) ((c & 255) * shade);
            r = (int) (r * (1 - fog) + 160 * fog);
            g = (int) (g * (1 - fog) + 195 * fog);
            b = (int) (b * (1 - fog) + 255 * fog);
            int col = (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            if (water) {
                if (waterT < 0) {
                    waterT = t;
                    waterCol = col;
                }
                continue;
            }
            if (waterT >= 0) {
                double a = Math.min(0.85, 0.35 + 0.1 * (t - waterT));
                col = mix(col, waterCol, a);
            }
            return col;
        }
        int sky = 0x9ec5ff;
        return waterT >= 0 ? mix(sky, waterCol, 0.9) : sky;
    }

    static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    int topY(int x, int z, int from) {
        for (int y = from; y > -1900; y--) {
            BlockState s = at(x, y, z);
            if (!s.isAir() && !s.is(Blocks.SHORT_GRASS) && !s.is(Blocks.TALL_GRASS) && !s.canBeReplaced()) return y;
        }
        return -1900;
    }

    void top(Path file, int cx, int cz, int r) throws Exception {
        int n = 2 * r + 1, s = 6;
        BufferedImage img = new BufferedImage(n * s, n * s, BufferedImage.TYPE_INT_RGB);
        int[] hs = new int[n * n];
        int base = topY(cx, cz, 1500);
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) hs[z * n + x] = topY(cx - r + x, cz - r + z, base + 80);
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int y = hs[z * n + x];
                BlockState st = at(cx - r + x, y, cz - r + z);
                int c = colour(st);
                int hx = hs[z * n + Math.min(n - 1, x + 1)] - hs[z * n + Math.max(0, x - 1)];
                int hz = hs[Math.min(n - 1, z + 1) * n + x] - hs[Math.max(0, z - 1) * n + x];
                double shade = Math.max(0.55, Math.min(1.25, 1 - 0.07 * (hx + hz)));
                int rgb = (clamp((int) (((c >> 16) & 255) * shade)) << 16) | (clamp((int) (((c >> 8) & 255) * shade)) << 8)
                        | clamp((int) ((c & 255) * shade));
                for (int j = 0; j < s; j++) for (int i = 0; i < s; i++) img.setRGB(x * s + i, z * s + j, rgb);
            }
        }
        ImageIO.write(img, "png", file.toFile());
    }
}
