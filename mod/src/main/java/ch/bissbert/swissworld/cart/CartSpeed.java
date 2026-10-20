package ch.bissbert.swissworld.cart;

/**
 * Speed rules of a fast cart, in blocks per tick of ground speed (1.0 = 20 m/s). Pure. Owner: mod-cart.
 *
 * <ul>
 *   <li>The rider's forward key raises the cart's target speed by the acceleration per tick, up to the cap; the back
 *       key lowers it twice as fast. With cruise control the cart holds its target without a key; without, it coasts
 *       like vanilla (friction, slopes) and the target follows it.</li>
 *   <li>The track ahead limits the speed: before the end of the track the cart brakes so it arrives at vanilla speed
 *       (0.4) and vanilla takes over; before a chunk that is not ready it brakes to a stop {@link #MARGIN} blocks short
 *       of it and waits. The braking curve is {@code v = sqrt(2·BRAKE·d)}, so a cart that follows it never needs more
 *       than {@link #BRAKE} per tick.</li>
 *   <li>The cart speeds up towards the lower of target and limit by the acceleration per tick, so after a hazard it
 *       picks up again gently.</li>
 *   <li>Powered rails add 0.06 per rail passed (vanilla: per tick on it, the same at 1 rail per tick) up to the cap;
 *       an unpowered one halves the speed, as in vanilla.</li>
 * </ul>
 */
public final class CartSpeed {
    private CartSpeed() {}

    public static final double VANILLA = 0.4;
    /** Longest move per substep; vanilla moves at most 0.4 per tick and follows every rail shape at that. */
    public static final double SUBSTEP = 0.35;
    /** Deceleration for planned stops, blocks per tick per tick (20 m/s²: from 20 m/s to a stop in 1 s, 10 blocks). */
    public static final double BRAKE = 0.05;
    /** Blocks kept between a stopped cart and a chunk that is not ready. */
    public static final double MARGIN = 2.0;
    /** Vanilla powered rail boost per tick. */
    public static final double BOOST = 0.06;
    /** Vanilla's height-to-speed factor on slopes (energy conservation term in moveAlongTrack). */
    public static final double SLOPE = 0.05;
    /** How far ahead the track is checked: the braking distance from the cap plus the margin, and some slack. */
    public static double lookahead(double cap) {
        return cap * cap / (2 * BRAKE) + MARGIN + 4;
    }

    /** Highest speed that can still slow down to {@code floor} within {@code dist} blocks. */
    public static double brakingLimit(double dist, double floor) {
        double d = dist - MARGIN;
        return Math.max(floor, d <= 0 ? 0 : Math.sqrt(2 * BRAKE * d));
    }

    /** Speed limit from the track ahead. */
    public static double limit(RailWalk.Ahead ahead, double cap) {
        return switch (ahead.stop()) {
            case CLEAR -> cap;
            case END -> Math.min(cap, brakingLimit(ahead.distance() + MARGIN, VANILLA));
            case UNREADY -> Math.min(cap, brakingLimit(ahead.distance(), 0));
        };
    }

    /** New target speed from the rider's input (+1 forward, -1 back, 0 none). */
    public static double target(double target, double v, int input, double cap, double accel, boolean cruise) {
        if (input > 0) return Math.min(cap, Math.max(target, v) + accel);
        if (input < 0) return Math.max(0, Math.min(target, v) - 2 * accel);
        return cruise ? Math.min(target, cap) : v;
    }

    /** The speed for this tick: at once down to the limit, otherwise towards min(target, limit) by accel (2·accel down). */
    public static double approach(double v, double target, double limit, double accel) {
        if (v > limit) return limit;
        double goal = Math.min(target, limit);
        if (v < goal) return Math.min(goal, v + accel);
        if (v > goal) return Math.max(goal, v - 2 * accel);
        return v;
    }

    /**
     * Once-per-tick rail effects after the move. {@code motor}: the speed is held (cruise or throttle), so friction and
     * slopes do not apply; otherwise vanilla friction and the slope's height change ({@code drop}, positive downhill).
     */
    public static double effects(double v, int poweredOn, int poweredOff, double drop, boolean motor, double friction,
            double cap) {
        if (!motor) v = v * friction + SLOPE * drop;
        if (poweredOn > 0 && v < cap) v = Math.min(cap, v + BOOST * poweredOn);
        for (int i = 0; i < poweredOff; i++) v *= 0.5;
        if (poweredOff > 0 && v < 0.03) v = 0;
        return Math.max(0, Math.min(v, Math.max(cap, VANILLA)));
    }

    /** Substeps for a tick at speed v, each at most {@link #SUBSTEP}. */
    public static int substeps(double v) {
        return Math.max(1, (int) Math.ceil(v / SUBSTEP - 1e-9));
    }
}
