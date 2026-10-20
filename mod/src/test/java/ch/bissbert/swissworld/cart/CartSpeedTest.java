package ch.bissbert.swissworld.cart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cart.RailWalk.Ahead;
import ch.bissbert.swissworld.cart.RailWalk.Stop;
import org.junit.jupiter.api.Test;

class CartSpeedTest {
    static final double CAP = 1.0, ACCEL = 0.02;

    @Test
    void substepsNeverMoveMoreThanVanilla() {
        assertEquals(1, CartSpeed.substeps(0.0));
        assertEquals(1, CartSpeed.substeps(0.35));
        assertEquals(2, CartSpeed.substeps(0.4));
        assertEquals(3, CartSpeed.substeps(1.0));
        assertEquals(5, CartSpeed.substeps(1.5));
        for (double v = 0; v <= 1.5; v += 0.01) assertTrue(v / CartSpeed.substeps(v) <= CartSpeed.SUBSTEP + 1e-9);
    }

    @Test
    void clearTrackAllowsTheCap() {
        assertEquals(CAP, CartSpeed.limit(new Ahead(30, 30, Stop.CLEAR), CAP));
    }

    @Test
    void endOfTrackIsReachedAtVanillaSpeed() {
        assertEquals(CartSpeed.VANILLA, CartSpeed.limit(new Ahead(0, 0, Stop.END), CAP));
        assertEquals(CartSpeed.VANILLA, CartSpeed.limit(new Ahead(1, 1, Stop.END), CAP));
        assertEquals(CAP, CartSpeed.limit(new Ahead(10, 10, Stop.END), CAP));
    }

    @Test
    void unreadyChunkStopsTheCartShortOfIt() {
        assertEquals(0, CartSpeed.limit(new Ahead(CartSpeed.MARGIN, 2, Stop.UNREADY), CAP));
        assertEquals(0, CartSpeed.limit(new Ahead(0, 0, Stop.UNREADY), CAP));
        assertTrue(CartSpeed.limit(new Ahead(5, 5, Stop.UNREADY), CAP) < 0.6);
    }

    /** A cart that brakes along the limit while it approaches a stop never decelerates harder than BRAKE. */
    @Test
    void brakingCurveIsGentleAndStopsInTime() {
        for (Stop stop : new Stop[] {Stop.END, Stop.UNREADY}) {
            double cap = 1.5, v = cap, d = CartSpeed.lookahead(cap) - 1;
            double floor = stop == Stop.END ? CartSpeed.VANILLA : 0;
            int ticks = 0;
            while (d > 0 && v > floor && ticks++ < 1000) {
                double next = CartSpeed.approach(v, cap, CartSpeed.limit(new Ahead(d, 0, stop), cap), ACCEL);
                // the last step to a standstill may drop what is left of a crawl
                assertTrue(v - next <= CartSpeed.BRAKE + 0.02 || (next == 0 && v < 0.1), stop + ": braked " + (v - next) + " at d " + d);
                v = next;
                d -= v;
            }
            assertTrue(v <= floor + 1e-9, stop + ": still " + v);
            assertTrue(d >= (stop == Stop.UNREADY ? CartSpeed.MARGIN - 0.5 : -1e-9), stop + ": overran to " + d);
        }
    }

    @Test
    void lookaheadCoversTheBrakingDistance() {
        for (double cap = 0.4; cap <= 1.5; cap += 0.1) {
            assertEquals(cap, CartSpeed.limit(new Ahead(CartSpeed.lookahead(cap), 0, Stop.UNREADY), cap), 1e-9);
        }
    }

    @Test
    void throttleAndBrake() {
        assertEquals(0.52, CartSpeed.target(0.3, 0.5, 1, CAP, ACCEL, true), 1e-9);
        assertEquals(CAP, CartSpeed.target(CAP, CAP, 1, CAP, ACCEL, true), 1e-9);
        assertEquals(0.46, CartSpeed.target(0.8, 0.5, -1, CAP, ACCEL, true), 1e-9);
        assertEquals(0, CartSpeed.target(0.01, 0.01, -1, CAP, ACCEL, true), 1e-9);
        // cruise holds the target; no cruise: the target follows the cart
        assertEquals(0.8, CartSpeed.target(0.8, 0.5, 0, CAP, ACCEL, true), 1e-9);
        assertEquals(0.5, CartSpeed.target(0.8, 0.5, 0, CAP, ACCEL, false), 1e-9);
    }

    @Test
    void afterAHazardTheCartPicksUpGently() {
        assertEquals(0.42, CartSpeed.approach(0.4, CAP, CAP, ACCEL), 1e-9);
        assertEquals(0.4, CartSpeed.approach(1.0, CAP, 0.4, ACCEL), 1e-9);    // at once down to the limit
        assertEquals(0.96, CartSpeed.approach(1.0, 0.5, CAP, ACCEL), 1e-9);   // brake key: 2 accel per tick
        double v = 0;
        int ticks = 0;
        while (v < CAP) {
            v = CartSpeed.approach(v, CAP, CAP, ACCEL);
            ticks++;
        }
        assertTrue(ticks == 50 || ticks == 51, "ticks " + ticks);
    }

    @Test
    void poweredRailsBoostUpToTheCapAndUnpoweredOnesBrake() {
        assertEquals(0.56, CartSpeed.effects(0.5, 1, 0, 0, true, 0.997, CAP), 1e-9);
        assertEquals(CAP, CartSpeed.effects(0.98, 2, 0, 0, true, 0.997, CAP), 1e-9);
        assertEquals(0.25, CartSpeed.effects(1.0, 0, 2, 0, true, 0.997, CAP), 1e-9);
        assertEquals(0, CartSpeed.effects(0.05, 0, 1, 0, true, 0.997, CAP), 1e-9);
    }

    @Test
    void motorHoldsSpeedCoastingDoesNot() {
        assertEquals(0.8, CartSpeed.effects(0.8, 0, 0, 1, true, 0.997, CAP), 1e-9);
        // coasting: friction, and a block downhill adds vanilla's slope term
        assertEquals(0.8 * 0.997 + CartSpeed.SLOPE, CartSpeed.effects(0.8, 0, 0, 1, false, 0.997, CAP), 1e-9);
        assertEquals(CAP, CartSpeed.effects(0.99, 0, 0, 3, false, 0.997, CAP), 1e-9);
    }
}
