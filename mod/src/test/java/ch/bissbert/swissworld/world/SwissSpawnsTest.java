package ch.bissbert.swissworld.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SwissSpawnsTest {
    /** Water column with {@code n} water blocks above the spawn position. */
    private static boolean column(int n, boolean below) {
        return SwissSpawns.inWater(dy -> dy <= n, below);
    }

    @Test
    void shallowAndMidWaterAllowed() {
        assertTrue(column(1, true));
        assertTrue(column(5, true));
        assertTrue(column(SwissSpawns.MAX_DEPTH - 1, true));
    }

    @Test
    void deepWaterRejected() {
        assertFalse(column(SwissSpawns.MAX_DEPTH, true));
        assertFalse(column(60, true));
    }

    @Test
    void needsWaterAboveAndBelow() {
        assertFalse(column(0, true));
        assertFalse(column(5, false));
    }
}
