package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
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
        assertTrue(down.fedTop());
        // banks only up to each column's own source level
        assertEquals(-1400, WaterPlan.bankTop(g, -1, 0));
        assertEquals(-1403, WaterPlan.bankTop(g, 1, 1));
        assertEquals(-1400, WaterPlan.bankTop(g, 0, 1));
        assertEquals(ColumnGrid.NONE, WaterPlan.bankTop(g, 0, 2));
    }
}
