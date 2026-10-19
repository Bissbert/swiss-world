package ch.bissbert.swissworld.gen.building;

/** Stateless splitmix64 hashing: every random choice in a building derives from its gid plus a salt. */
public final class Hash {
    private Hash() {}

    public static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b + 0x632BE59BD9B4E019L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform int in [0, n). */
    public static int pick(long seed, long salt, int n) {
        return (int) Math.floorMod(mix(seed, salt), (long) n);
    }

    /** Uniform double in [0, 1). */
    public static double unit(long seed, long salt) {
        return (mix(seed, salt) >>> 11) * 0x1.0p-53;
    }

    public static long pos(int x, int y, int z, long gid) {
        return mix(mix(gid, x), ((long) y << 32) ^ (z & 0xFFFFFFFFL));
    }
}
