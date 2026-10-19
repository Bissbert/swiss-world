package ch.bissbert.swissworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerrainHoldTest {
    private static final int MIN_Y = -2032;

    @Test
    void belowTheGroundGoesOntoIt() {
        assertEquals(-1430, TerrainHold.groundedY(-1500.3, -1430, MIN_Y));
    }

    @Test
    void aboveTheGroundStaysWhereItIs() {
        assertEquals(-718.5, TerrainHold.groundedY(-718.5, -1430, MIN_Y));
    }

    @Test
    void columnWithoutTerrainKeepsThePlayerInsideTheWorld() {
        // empty column: the heightmap is min y
        assertEquals(MIN_Y + 1, TerrainHold.groundedY(-2100, MIN_Y, MIN_Y));
    }

    @Test
    void landedOnlyOnTheGroundOrInWater() {
        org.junit.jupiter.api.Assertions.assertTrue(TerrainHold.landed(true, false, -1148, -1148));
        org.junit.jupiter.api.Assertions.assertTrue(TerrainHold.landed(false, true, -1300, -1148));
        // on ground reported in mid-air (held player), or still falling
        org.junit.jupiter.api.Assertions.assertFalse(TerrainHold.landed(true, false, -1000, -1148));
        org.junit.jupiter.api.Assertions.assertFalse(TerrainHold.landed(false, false, -1148, -1148));
    }
}
