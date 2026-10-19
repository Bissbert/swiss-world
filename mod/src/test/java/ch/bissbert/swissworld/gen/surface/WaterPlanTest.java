package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaterPlanTest {
    // fixture cell 2632_1170 at the default origin: x = dx - 900, z = dz - 400

    @Test
    void fixtureLakeIsFlatSourcesAt564() throws Exception {
        SwdCell cell = Cells.fixture();
        ColumnGrid g = Cells.grid(cell, -890, 500);
        WaterPlan.Fill f = WaterPlan.fill(g, -890, 500);
        assertEquals(-1436, f.sourceTop());
        assertFalse(f.falls());
        assertEquals(-1450, g.ground(-890, 500));
    }

    @Test
    void fixtureLakeIsWalledOffFromTheOceanAndFromLowerShores() throws Exception {
        SwdCell cell = Cells.fixture();
        // west of dx 0 is cell 2631_1170, which does not exist: ocean, so a rim up to the lake level
        ColumnGrid g = Cells.grid(cell, -901, 500);
        assertFalse(g.present(-901, 500));
        assertEquals(-1436, WaterPlan.bankTop(g, -901, 500));
        assertEquals(-1436, g.top(-901, 500));
        // south of dz 999 is cell 2632_1169, also missing
        g = Cells.grid(cell, -890, 600);
        assertEquals(-1436, g.top(-890, 600));
        // dx 200 is dry shore above the lake level (~570 m): left alone
        g = Cells.grid(cell, -700, 500);
        assertNull(WaterPlan.fill(g, -700, 500));
        assertEquals(ColumnGrid.NONE, WaterPlan.bankTop(g, -700, 500));
        assertEquals(g.ground(-700, 500), g.top(-700, 500));
    }

    @Test
    void stepGetsFallingWaterAndAFlowingTop() {
        SwdCell c = Cells.flat(590);
        // a river running east: 600 m on x 0, a 3 m step down to 597 m on x 1
        Cells.water(c, 0, 0, 600, 2);
        Cells.water(c, 1, 0, 597, 2);
        ColumnGrid g = Cells.grid(c, 0, 0);
        WaterPlan.Fill up = WaterPlan.fill(g, 0, 0), down = WaterPlan.fill(g, 1, 0);
        assertEquals(-1400, up.sourceTop());
        assertFalse(up.falls());
        assertEquals(-1403, down.sourceTop());
        assertEquals(-1400, down.fallTop());
        // banks only up to each column's own source level
        assertEquals(-1400, WaterPlan.bankTop(g, -1, 0));
        assertEquals(-1403, WaterPlan.bankTop(g, 1, 1));
        assertEquals(-1400, WaterPlan.bankTop(g, 0, 1));
        // a flat shore up to BANK_REACH out, instead of a ridge with a pit behind it; not beyond
        assertEquals(-1400, WaterPlan.bankTop(g, 0, 2));
        assertEquals(ColumnGrid.NONE, WaterPlan.bankTop(g, 0, 3));
    }

    @Test
    void waterfallFallsOnlyBelowAFeedingSource() {
        SwdCell c = Cells.flat(590);
        // a waterfall column whose WLVL is above every neighbour: just the pool, no unfed falling water
        Cells.water(c, 0, 0, 600, WaterPlan.WATERFALL);
        ColumnGrid g = Cells.grid(c, 0, 0);
        WaterPlan.Fill f = WaterPlan.fill(g, 0, 0);
        assertEquals(g.ground(0, 0) + 1, f.sourceTop());
        assertFalse(f.falls());
        // fed from a river above to the east: falls up to that river's level
        Cells.water(c, 1, 0, 600, 2);
        g = Cells.grid(c, 0, 0);
        f = WaterPlan.fill(g, 0, 0);
        assertEquals(-1400, f.fallTop());
        assertTrue(f.falls());
    }

    @Test
    void cornerOfADiagonalStepIsAReedNotWaterOrRock() {
        SwdCell c = Cells.flat(590);
        // upper river at 601 m west and north of (0, 0), lower river at 600 m on (0, 0) and east and south of it
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) Cells.water(c, x, z, x < 0 || z < 0 ? 601 : 600, 3);
        }
        ColumnGrid g = Cells.grid(c, 0, 0);
        WaterPlan.Fill corner = WaterPlan.fill(g, 0, 0);
        assertEquals(2, WaterPlan.sourcesAt(g, 0, 0, -1399));
        // no stone on the water surface: sand under sugar cane (no source, not solid), the flowing top above it
        assertTrue(corner.reed());
        assertFalse(corner.rock());
        assertEquals(-1399, corner.fallTop());
        // along the straight parts of the step one source feeds the flowing top: water, as before
        WaterPlan.Fill edge = WaterPlan.fill(g, 2, 0);
        assertFalse(edge.rock());
        assertTrue(edge.falls());
        assertEquals(-1399, edge.fallTop());
        // the reed is no source, so the columns beside the corner stay water
        assertFalse(WaterPlan.fill(g, 1, 0).rock());
        assertFalse(WaterPlan.fill(g, 0, 1).rock());
    }

    /** A river with a one-block step that jogs: upper level on {x >= 1, z <= 0} and {x >= 2, z >= 1}. */
    private static SwdCell joggedStep(double drop) {
        SwdCell c = Cells.flat(590);
        for (int x = -6; x <= 8; x++) {
            for (int z = -6; z <= 8; z++) {
                boolean upper = x >= 1 && z <= 0 || x >= 2 && z >= 1;
                Cells.water(c, x, z, upper ? 600 + drop : 600, 2);
            }
        }
        return c;
    }

    @Test
    void joggedRiverStepHasNoStoneAtTheSurfaceAndStaysPut() {
        Voxels v = new Voxels(joggedStep(1), -9, -9, 11, 11, -1415, -1390);
        List<String> stones = v.stonesInWater(1), unstable = v.unstableWater(1);
        assertTrue(stones.isEmpty(), () -> "stone in the river: " + stones);
        assertTrue(unstable.isEmpty(), () -> "water that would move: " + unstable);
        ColumnGrid g = Cells.grid(joggedStep(1), 0, 0);
        assertTrue(WaterPlan.fill(g, 1, 1).reed()); // the inner corner of the jog
        assertTrue(WaterPlan.fill(g, 0, 1).lip());
    }

    @Test
    void diagonalRiverStepHasNoStoneAtTheSurfaceAndStaysPut() {
        SwdCell c = Cells.flat(590);
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) Cells.water(c, x, z, x + z <= 0 ? 601 : 600, 2);
        }
        Voxels v = new Voxels(c, -15, -15, 15, 15, -1415, -1390);
        // playtest: "in a river going uphill there are stone blocks on the border of the two levels", also where
        // the step meets the shore
        List<String> stones = v.stonesInWater(1), rock = v.exposedRock(1), unstable = v.unstableWater(1);
        assertTrue(stones.isEmpty(), () -> "stone in the river: " + stones);
        assertTrue(rock.isEmpty(), () -> "stone showing at the step: " + rock);
        assertTrue(unstable.isEmpty(), () -> "water that would move: " + unstable);
    }

    /**
     * Playtest river step (14110, -6414): the bed's gravel is one block thick over stone, so where the bed steps down,
     * or the shore stands a block above it, the deeper water showed the stone. That face is the bed's to cover.
     */
    @Test
    void bedStepsAndLowShoresShowNoStoneToTheWater() {
        SwdCell c = Cells.flat(591);
        for (int z = -12; z <= 12; z++) {
            for (int x = -4; x <= 4; x++) {
                Cells.ground(c, x, z, Math.abs(x) <= 1 ? 587.5 : 588.5); // a deeper channel in the middle
                Cells.water(c, x, z, 590.2, 2);
            }
        }
        ColumnGrid g = Cells.grid(c, 0, 0);
        int checked = 0;
        for (int x = -6; x <= 6; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    WaterPlan.Fill n = WaterPlan.fill(g, x + d[0], z + d[1]);
                    int nGround = g.ground(x + d[0], z + d[1]);
                    if (n == null || nGround >= g.ground(x, z)) continue;
                    // this column's ground from the neighbour's floor up to its water: the face the water touches
                    int lo = nGround + 1, hi = Math.min(g.ground(x, z), n.sourceTop());
                    int[] face = WaterPlan.bedFace(g, x, z);
                    String at = x + "," + z + " beside " + (x + d[0]) + "," + (z + d[1]);
                    assertTrue(face != null && face[0] <= lo && face[1] >= hi, () -> "uncovered face at " + at);
                    checked++;
                }
                int[] face = WaterPlan.bedFace(g, x, z);
                if (face != null) assertTrue(face[1] <= g.ground(x, z), "face above the ground at " + x + "," + z);
            }
        }
        assertTrue(checked >= 18, "channel steps and shores checked: " + checked);
        // dry land well away from the water has none
        assertNull(WaterPlan.bedFace(g, 9, 0));
    }

    /** Playtest 17.16.32: a 1-wide stream in a wide stone bed showed staircase banks. */
    @Test
    void streamInAWideBedHasFlatShoresAndNoTerraces() {
        SwdCell c = Cells.flat(600);
        for (int z = -30; z <= 30; z++) {
            for (int x = -4; x <= 4; x++) Cells.ground(c, x, z, Math.abs(x) <= 3 ? 596 : 598);
            Cells.ground(c, 0, z, 595.5);
            Cells.water(c, 0, z, 597.2, 3);
        }
        Voxels v = new Voxels(c, -10, -10, 10, 10, -1430, -1380);
        for (int z = -8; z <= 8; z++) {
            int surface = top(v, 0, z);
            assertTrue(Voxels.water(v.at(0, surface, z)), "water at 0," + z);
            // across the bed (the fixture's own 2 m bank beyond it is terrain, not water work)
            for (int x = -4; x <= 3; x++) {
                int a = top(v, x, z), b = top(v, x + 1, z);
                assertTrue(Math.abs(a - b) <= 1, "terrace between " + x + " and " + (x + 1) + " at z " + z + ": " + a + " vs " + b);
            }
            // the shore beside the stream is level with it: no pit, no wall
            for (int x : new int[] {-2, -1, 1, 2}) assertEquals(surface, top(v, x, z), "shore " + x + "," + z);
        }
        assertTrue(v.stonesInWater(1).isEmpty());
        assertTrue(v.unstableWater(1).isEmpty(), () -> "water that would move: " + v.unstableWater(1));
    }

    private static int top(Voxels v, int x, int z) {
        for (int y = v.y0 + v.ny - 1; y >= v.y0; y--) if (v.at(x, y, z) != Voxels.B.AIR) return y;
        return v.y0 - 1;
    }
}
