package ch.bissbert.swissworld.cart;

import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Follows the track ahead of a cart the way a minecart would, rail by rail, without touching the world directly. Owner:
 * mod-cart.
 *
 * <p>From a rail, the cart leaves through the exit that points most along its heading. The next rail sits one block
 * further that way: one block up if the current rail ascends that way, else at the same height or one lower (a slope
 * going down ascends towards us). It must have an exit pointing back, or the cart would not run onto it the way it
 * runs on a joined track: that is the end of the track (a dead end, a buffer, a rail that points elsewhere). A column
 * whose chunk is not ready (unloaded, an empty placeholder, not yet on the rider's client) stops the walk too.
 */
public final class RailWalk {
    private RailWalk() {}

    /** The world as the walk sees it. */
    public interface RailView {
        /** The rail's shape at this block, null if there is no rail (or the chunk is not readable). */
        RailShape shape(int x, int y, int z);

        /** Whether the chunk holding this column is real terrain the cart may enter. */
        boolean ready(int x, int z);
    }

    public enum Stop { CLEAR, END, UNREADY }

    /**
     * What lies ahead: {@code distance} blocks of joined track (the path length a cart runs: 1 per straight or sloped
     * rail, 1/√2 per curve piece), then {@code stop}; CLEAR if the walk got as far as it was asked to.
     */
    public record Ahead(double distance, int rails, Stop stop) {}

    static final double CURVE_LENGTH = Math.sqrt(0.5);

    /** Horizontal exits {dx, dz} of a shape (north is -z), in vanilla's order. */
    static int[][] exits(RailShape s) {
        return switch (s) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> new int[][] {{0, -1}, {0, 1}};
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> new int[][] {{-1, 0}, {1, 0}};
            case NORTH_EAST -> new int[][] {{0, -1}, {1, 0}};
            case NORTH_WEST -> new int[][] {{0, -1}, {-1, 0}};
            case SOUTH_EAST -> new int[][] {{0, 1}, {1, 0}};
            case SOUTH_WEST -> new int[][] {{0, 1}, {-1, 0}};
        };
    }

    /** Whether the shape climbs one block when left through exit (dx, dz). */
    static boolean ascendsTowards(RailShape s, int dx, int dz) {
        return switch (s) {
            case ASCENDING_EAST -> dx == 1;
            case ASCENDING_WEST -> dx == -1;
            case ASCENDING_NORTH -> dz == -1;
            case ASCENDING_SOUTH -> dz == 1;
            default -> false;
        };
    }

    static boolean isCurve(RailShape s) {
        return s == RailShape.NORTH_EAST || s == RailShape.NORTH_WEST || s == RailShape.SOUTH_EAST
                || s == RailShape.SOUTH_WEST;
    }

    /** The exit of {@code s} that points most along heading (hx, hz). */
    public static int[] exitAlong(RailShape s, double hx, double hz) {
        int[][] e = exits(s);
        return e[0][0] * hx + e[0][1] * hz >= e[1][0] * hx + e[1][1] * hz ? e[0] : e[1];
    }

    static boolean hasExit(RailShape s, int dx, int dz) {
        for (int[] e : exits(s)) if (e[0] == dx && e[1] == dz) return true;
        return false;
    }

    /**
     * Walks from the rail at (x, y, z) with shape {@code shape}, heading (hx, hz), until {@code maxDistance} blocks of
     * track are known or the walk stops.
     */
    public static Ahead scan(RailView view, int x, int y, int z, RailShape shape, double hx, double hz,
            double maxDistance) {
        int[] d = exitAlong(shape, hx, hz);
        double dist = 0;
        int rails = 0;
        while (dist < maxDistance) {
            int nx = x + d[0], nz = z + d[1];
            if (!view.ready(nx, nz)) return new Ahead(dist, rails, Stop.UNREADY);
            int ny = Integer.MIN_VALUE;
            RailShape next = null;
            int[] ys = ascendsTowards(shape, d[0], d[1]) ? new int[] {y + 1} : new int[] {y, y - 1};
            for (int cy : ys) {
                RailShape s = view.shape(nx, cy, nz);
                if (s != null && hasExit(s, -d[0], -d[1])) {
                    next = s;
                    ny = cy;
                    break;
                }
            }
            if (next == null) return new Ahead(dist, rails, Stop.END);
            // leave the next rail through its other exit
            int[][] e = exits(next);
            d = e[0][0] == -d[0] && e[0][1] == -d[1] ? e[1] : e[0];
            x = nx;
            y = ny;
            z = nz;
            shape = next;
            rails++;
            dist += isCurve(next) ? CURVE_LENGTH : 1.0;
        }
        return new Ahead(dist, rails, Stop.CLEAR);
    }
}
