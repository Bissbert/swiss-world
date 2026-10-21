package ch.bissbert.swissworld.gen.ground;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Preview renders of a block dump ({@link TestWorld}): a top-down colour map with hillshade, and an oblique view
 * (painter's algorithm, one block = {@code s} pixels) that shows step faces (risers) in the colours of the blocks that
 * really sit there. Colours are the average colours of the vanilla textures (grass tinted as in the Swiss biomes), not
 * the coarse map colours, so granite reads pink and calcite white.
 */
final class Preview {
    private Preview() {}

    private static final Map<Block, Integer> TOP = new HashMap<>(), SIDE = new HashMap<>();

    static {
        top(Blocks.GRASS_BLOCK, 0x6f9a43, 0x7a6a3c);
        top(Blocks.STONE, 0x7d7d7d, -1);
        top(Blocks.ANDESITE, 0x888889, -1);
        top(Blocks.DIORITE, 0xbcbcbc, -1);
        top(Blocks.GRANITE, 0x956755, -1);
        top(Blocks.CALCITE, 0xdfe0dc, -1);
        top(Blocks.SMOOTH_STONE, 0x9e9e9e, -1);
        top(Blocks.COBBLESTONE, 0x7f7f7f, -1);
        top(Blocks.MOSSY_COBBLESTONE, 0x6e775f, -1);
        top(Blocks.TUFF, 0x6c6d66, -1);
        top(Blocks.DEEPSLATE, 0x505053, -1);
        top(Blocks.COBBLED_DEEPSLATE, 0x4d4d50, -1);
        top(Blocks.GRAVEL, 0x837f7e, -1);
        top(Blocks.COARSE_DIRT, 0x77553b, -1);
        top(Blocks.DIRT, 0x866043, -1);
        top(Blocks.ROOTED_DIRT, 0x90674c, -1);
        top(Blocks.PODZOL, 0x5c3f18, 0x7a5a3a);
        top(Blocks.MOSS_BLOCK, 0x596d2d, -1);
        top(Blocks.MUD, 0x3c393d, -1);
        top(Blocks.PACKED_MUD, 0x8e6b50, -1);
        top(Blocks.CLAY, 0xa0a6b3, -1);
        top(Blocks.SAND, 0xdbcfa3, -1);
        top(Blocks.SANDSTONE, 0xd8cb9b, -1);
        top(Blocks.SNOW, 0xf9fefe, -1);
        top(Blocks.SNOW_BLOCK, 0xf9fefe, -1);
        top(Blocks.PACKED_ICE, 0x8db4fa, -1);
        top(Blocks.BLUE_ICE, 0x74a8fd, -1);
        top(Blocks.ICE, 0x91b7fd, -1);
        top(Blocks.DIRT_PATH, 0x947a41, 0x866043);
        top(Blocks.DRIPSTONE_BLOCK, 0x866b5c, -1);
        top(Blocks.WATER, 0x3f76e4, -1);
    }

    private static void top(Block b, int top, int side) {
        TOP.put(b, top);
        SIDE.put(b, side < 0 ? top : side);
    }

    static int colour(BlockState s, boolean side) {
        Integer c = (side ? SIDE : TOP).get(s.getBlock());
        if (c != null) return c;
        if (!s.getFluidState().isEmpty()) return 0x3f76e4;
        return s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
    }

    static int mul(int rgb, double f) {
        int r = clamp((int) (((rgb >> 16) & 255) * f)), g = clamp((int) (((rgb >> 8) & 255) * f)), b = clamp((int) ((rgb & 255) * f));
        return r << 16 | g << 8 | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Top-down: blocks by texture colour, hillshade from the north-west. */
    static void topDown(TestWorld w, int x0, int z0, int n, int s, Path file) throws IOException {
        BufferedImage img = new BufferedImage(n * s, n * s, BufferedImage.TYPE_INT_RGB);
        int[] h = heights(w, x0, z0, n);
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int hx = h[z * n + Math.min(n - 1, x + 1)] - h[z * n + Math.max(0, x - 1)];
                int hz = h[Math.min(n - 1, z + 1) * n + x] - h[Math.max(0, z - 1) * n + x];
                double shade = Math.max(0.55, Math.min(1.25, 1 - 0.06 * (hx + hz)));
                int rgb = mul(colour(w.get(x0 + x, h[z * n + x], z0 + z), false), shade);
                for (int dz = 0; dz < s; dz++) for (int dx = 0; dx < s; dx++) img.setRGB(x * s + dx, z * s + dz, rgb);
            }
        }
        write(img, file);
    }

    private static int[] heights(TestWorld w, int x0, int z0, int n) {
        int[] h = new int[n * n];
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) h[z * n + x] = w.top(x0 + x, z0 + z);
        return h;
    }

    /**
     * Oblique view from {@code from} ('S': standing south, looking north; 'N', 'E', 'W' likewise), ground depth
     * foreshortened to 0.55, one block {@code s} pixels high. Top faces are lit by their slope, faces towards the viewer
     * show every block of the step.
     */
    static void oblique(TestWorld w, int x0, int z0, int n, int s, char from, Path file) throws IOException {
        int[] h = heights(w, x0, z0, n);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int v : h) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double fore = 0.55;
        int width = n * s, height = (int) Math.ceil(n * fore * s) + (max - min + 2) * s;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            int sky = mul(0x9ec5ff, 1 - 0.25 * y / height);
            for (int x = 0; x < width; x++) img.setRGB(x, y, sky);
        }
        int base = (max - min + 1) * s;
        for (int v = 0; v < n; v++) {
            for (int u = 0; u < n; u++) {
                int[] xz = xz(u, v, n, from), front = xz(u, Math.min(n - 1, v + 1), n, from);
                int x = xz[0], z = xz[1];
                int y = h[z * n + x], yf = v + 1 < n ? h[front[1] * n + front[0]] : y - 4;
                int sy = base + (int) (v * fore * s) - (y - min) * s;
                // top face: lit from the far side's left, darker where it falls towards the viewer
                int[] behind = xz(u, Math.max(0, v - 1), n, from), left = xz(Math.max(0, u - 1), v, n, from);
                int dv = y - h[behind[1] * n + behind[0]], du = y - h[left[1] * n + left[0]];
                double shade = Math.max(0.6, Math.min(1.2, 1 + 0.05 * dv + 0.04 * du));
                int topRgb = mul(colour(w.get(x0 + x, y, z0 + z), false), shade);
                int faceH = Math.max(1, (int) Math.ceil(fore * s));
                fill(img, u * s, sy, s, faceH, topRgb);
                // the face towards the viewer: blocks y down to the front neighbour's top
                for (int b = y; b > yf; b--) {
                    int py = sy + faceH + (y - b) * s;
                    BlockState st = w.get(x0 + x, b, z0 + z);
                    int rgb = mul(colour(st, b == y), 0.72);
                    fill(img, u * s, py, s, s, rgb);
                }
            }
        }
        write(img, file);
    }

    private static int[] xz(int u, int v, int n, char from) {
        return switch (from) {
            case 'N' -> new int[] {n - 1 - u, n - 1 - v};
            case 'E' -> new int[] {v, n - 1 - u};
            case 'W' -> new int[] {n - 1 - v, u};
            default -> new int[] {u, v};
        };
    }

    private static void fill(BufferedImage img, int x, int y, int w, int h, int rgb) {
        for (int j = Math.max(0, y); j < Math.min(img.getHeight(), y + h); j++) {
            for (int i = Math.max(0, x); i < Math.min(img.getWidth(), x + w); i++) img.setRGB(i, j, rgb);
        }
    }

    private static void write(BufferedImage img, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }
}
