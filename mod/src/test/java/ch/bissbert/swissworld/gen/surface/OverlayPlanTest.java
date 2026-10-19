package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.gen.surface.OverlayPlan.Rail;
import org.junit.jupiter.api.Test;

class OverlayPlanTest {
    @Test
    void fixtureRailRunsNorthSouthAndClimbsInSingleSteps() throws Exception {
        SwdCell cell = Cells.fixture();
        ColumnGrid g = Cells.grid(cell, -200, -390);
        assertTrue(OverlayPlan.railCentre(g, -200, -390));
        assertFalse(OverlayPlan.railCentre(g, -199, -390)); // dx 701: bed, no rail
        assertEquals(-1365, g.deck(-200, -390));
        int ascending = 0;
        for (int z = -400; z < 600; z += 16) {
            ColumnGrid c = Cells.grid(cell, -200, z);
            for (int k = z; k < z + 16; k++) {
                Rail s = OverlayPlan.railShape(c, -200, k);
                assertTrue(s == Rail.NORTH_SOUTH || s == Rail.ASCENDING_NORTH || s == Rail.ASCENDING_SOUTH, s::toString);
                if (s.ascending()) {
                    ascending++;
                    int up = s == Rail.ASCENDING_NORTH ? k - 1 : k + 1;
                    assertEquals(c.deck(-200, k) + 1, c.deck(-200, up));
                }
            }
        }
        // 0.05 m per 2 m along dz over 1000 columns: about 25 one-block steps
        assertTrue(ascending >= 20 && ascending <= 30, "ascending " + ascending);
    }

    @Test
    void fixtureBridgeHasRailingsOnItsSidesAndPiersEvery12m() throws Exception {
        SwdCell cell = Cells.fixture();
        ColumnGrid g = Cells.grid(cell, -350, -100);
        assertEquals(-1360, g.deck(-350, -100));
        assertTrue(OverlayPlan.bridge(g, -350, -100));
        assertTrue(OverlayPlan.railing(g, -350, -102)); // dz 298
        assertTrue(OverlayPlan.railing(g, -350, -98)); // dz 302
        assertFalse(OverlayPlan.railing(g, -350, -100));
        // E = 2,632,900 + x; 2,632,548 is a multiple of 12
        for (int z = -102; z <= -98; z++) assertTrue(OverlayPlan.pier(g, -352, z));
        assertFalse(OverlayPlan.pier(g, -350, -100));
    }

    @Test
    void railsCurveAndEndTowardsTheirNeighbours() {
        SwdCell c = Cells.flat(600);
        // an L: (0,0) -> (0,1) -> (1,1)
        Cells.overlay(c, 0, 0, OverlayPlan.RAIL_BED, ColumnGrid.RAIL, Double.NaN);
        Cells.overlay(c, 0, 1, OverlayPlan.RAIL_BED, ColumnGrid.RAIL, Double.NaN);
        Cells.overlay(c, 1, 1, OverlayPlan.RAIL_BED, ColumnGrid.RAIL, Double.NaN);
        Cells.overlay(c, 5, 5, OverlayPlan.RAIL_BED, ColumnGrid.RAIL, Double.NaN);
        ColumnGrid g = Cells.grid(c, 0, 0);
        assertEquals(Rail.NORTH_SOUTH, OverlayPlan.railShape(g, 0, 0));
        assertEquals(Rail.NORTH_EAST, OverlayPlan.railShape(g, 0, 1));
        assertEquals(Rail.EAST_WEST, OverlayPlan.railShape(g, 1, 1));
        assertEquals(Rail.NORTH_SOUTH, OverlayPlan.railShape(g, 5, 5));
    }

    @Test
    void railAscendsOnlyToADeckOneHigher() {
        SwdCell c = Cells.flat(600);
        Cells.overlay(c, 0, 0, OverlayPlan.RAIL_BED, ColumnGrid.RAIL, 600.5);
        Cells.overlay(c, 1, 0, OverlayPlan.RAIL_BED, ColumnGrid.RAIL, 601.5);
        Cells.overlay(c, 2, 0, OverlayPlan.RAIL_BED, ColumnGrid.RAIL, 603.5);
        ColumnGrid g = Cells.grid(c, 0, 0);
        assertEquals(Rail.ASCENDING_EAST, OverlayPlan.railShape(g, 0, 0));
        assertEquals(Rail.EAST_WEST, OverlayPlan.railShape(g, 1, 0));
        assertEquals(Rail.EAST_WEST, OverlayPlan.railShape(g, 2, 0));
    }
}
