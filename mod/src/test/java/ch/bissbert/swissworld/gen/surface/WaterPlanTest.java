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
        // where the step meets the shore the corner stays rock (the shore is too low for a reed): check mid-river
        List<String> stones = v.stonesInWater(6), unstable = v.unstableWater(1);
        assertTrue(stones.isEmpty(), () -> "stone in the river: " + stones);
        assertTrue(unstable.isEmpty(), () -> "water that would move: " + unstable);
    }
}
