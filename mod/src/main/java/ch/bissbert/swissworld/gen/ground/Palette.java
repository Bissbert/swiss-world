package ch.bissbert.swissworld.gen.ground;

import net.minecraft.world.level.block.Block;

/**
 * Weighted blocks of one ground palette. {@link #pick} maps a uniform value to a block, in list order, so neighbouring
 * values (and thus neighbouring patches of {@link Noise#patch}) get neighbouring entries: list materials in the order
 * they grade into each other (grass, earth, gravel, rock).
 */
public final class Palette {
    final Block[] blocks;
    final double[] upTo;

    private Palette(Block[] blocks, double[] upTo) {
        this.blocks = blocks;
        this.upTo = upTo;
    }

    /** Pairs of (block, weight in percent). */
    public static Palette of(Object... pairs) {
        int n = pairs.length / 2;
        Block[] b = new Block[n];
        double[] w = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            b[i] = (Block) pairs[2 * i];
            sum += ((Number) pairs[2 * i + 1]).doubleValue();
            w[i] = sum;
        }
        for (int i = 0; i < n; i++) w[i] /= sum;
        return new Palette(b, w);
    }

    public Block pick(double v) {
        for (int i = 0; i < blocks.length - 1; i++) if (v < upTo[i]) return blocks[i];
        return blocks[blocks.length - 1];
    }

    /** Share of {@code b} in this palette, 0..1. */
    public double share(Block b) {
        double prev = 0, s = 0;
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == b) s += upTo[i] - prev;
            prev = upTo[i];
        }
        return s;
    }

    public int size() {
        return blocks.length;
    }

    public Block block(int i) {
        return blocks[i];
    }

    public double weight(int i) {
        return upTo[i] - (i == 0 ? 0 : upTo[i - 1]);
    }
}
