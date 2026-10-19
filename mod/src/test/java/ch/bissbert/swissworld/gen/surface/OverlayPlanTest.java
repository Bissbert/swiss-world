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

    @Test
    void railUnderARoadBridgeLiesOnTheTerrainAndKeepsPiersAway() {
        SwdCell c = Cells.flat(600);
        // a road bridge running east-west at 610 m, z -2..2, over a railway running north-south at x 0
        for (int x = -20; x <= 20; x++) {
            for (int z = -2; z <= 2; z++) Cells.overlay(c, x, z, 3, ColumnGrid.BRIDGE | (x == 0 ? ColumnGrid.RAIL : 0), 610);
        }
        for (int z = -10; z <= 10; z++) if (z < -2 || z > 2) Cells.overlay(c, 0, z, OverlayPlan.RAIL_BED, ColumnGrid.RAIL, Double.NaN);
        ColumnGrid g = Cells.grid(c, 0, 0);
        assertTrue(OverlayPlan.railUnderBridge(g, 0, 0));
        assertFalse(OverlayPlan.railUnderBridge(g, 1, 0));
        assertFalse(OverlayPlan.railUnderBridge(g, 0, 5)); // plain rail bed
        assertTrue(OverlayPlan.railCentre(g, 0, 0));
        assertEquals(-1390, g.deck(0, 0));
        assertEquals(-1400, OverlayPlan.railBed(g, 0, 0));
        assertEquals(OverlayPlan.railBed(g, 0, 5), OverlayPlan.railBed(g, 0, 0));
        assertEquals(Rail.NORTH_SOUTH, OverlayPlan.railShape(g, 0, 0));
        assertEquals(Rail.NORTH_SOUTH, OverlayPlan.railShape(g, 0, -3));
        // piers every 12 m of E, but none within 2 columns of the track
        int pierX = g.xAt(Math.floorDiv(g.east(0), 12) * 12);
        if (Math.abs(pierX) <= OverlayPlan.TRACK_CLEARANCE) {
            assertFalse(OverlayPlan.pier(g, pierX, 0));
            pierX += 12;
        }
        assertTrue(OverlayPlan.pier(g, pierX, 0));
    }

    @Test
    void narrowBridgesKeepAWalkableColumn() {
        SwdCell c = Cells.flat(600);
        // footbridges running north-south: 1 wide at x 0, 2 wide at x 10..11
        for (int z = -10; z <= 10; z++) {
            Cells.overlay(c, 0, z, 6, ColumnGrid.BRIDGE, 605);
            Cells.overlay(c, 10, z, 6, ColumnGrid.BRIDGE, 605);
            Cells.overlay(c, 11, z, 6, ColumnGrid.BRIDGE, 605);
        }
        ColumnGrid g = Cells.grid(c, 0, 0);
        assertFalse(OverlayPlan.railing(g, 0, 0));
        assertFalse(OverlayPlan.railing(g, 10, 0));
        assertTrue(OverlayPlan.railing(g, 11, 0));
    }

    @Test
    void aDiagonalBridgeHasNoRailingsAcrossItsSteppedEdge() {
        SwdCell c = Cells.flat(600);
        // a 2-wide deck stepping south-east: (i, i) and (i + 1, i)
        for (int i = -10; i <= 10; i++) {
            Cells.overlay(c, i, i, 6, ColumnGrid.BRIDGE, 605);
            Cells.overlay(c, i + 1, i, 6, ColumnGrid.BRIDGE, 605);
        }
        ColumnGrid g = Cells.grid(c, 0, 0);
        assertFalse(OverlayPlan.railing(g, 0, 0));
        assertFalse(OverlayPlan.railing(g, 1, 0));
    }
}
