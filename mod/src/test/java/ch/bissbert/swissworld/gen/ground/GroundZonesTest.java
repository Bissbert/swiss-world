package ch.bissbert.swissworld.gen.ground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Zone rules, packed ids, the climate lines and the snow model: pure functions, no Minecraft. */
class GroundZonesTest {
    private static GroundZones.Input in(int surf, double h, double slope) {
        GroundZones.Input c = new GroundZones.Input();
        c.surf = surf;
        c.h = h;
        c.slope = slope;
        c.region = Region.NORTH_FLANK;
        c.treeline = 1850;
        c.snowLine = 2750;
        c.belt = Belt.of(h, c.treeline, c.snowLine, 0);
        return c;
    }

    @Test
    void packedIdRoundTrips() {
        for (Region r : Region.values()) {
            for (Belt b : Belt.values()) {
                for (Zone z : Zone.values()) {
                    for (Lith l : Lith.values()) {
                        short id = ZoneId.pack(r, b, z, l);
                        assertEquals(r, ZoneId.region(id));
                        assertEquals(b, ZoneId.belt(id));
                        assertEquals(z, ZoneId.zone(id));
                        assertEquals(l, ZoneId.lith(id));
                        assertEquals(0, id & 1, "spare bit");
                    }
                }
            }
        }
        assertTrue(Zone.values().length <= 64);
    }

    @Test
    void openLand() {
        assertEquals(Zone.HAY_MEADOW, GroundZones.zone(in(0, 600, 3)));
        assertEquals(Zone.PASTURE, GroundZones.zone(in(0, 900, 15)));
        assertEquals(Zone.ALPINE_PASTURE, GroundZones.zone(in(0, 2100, 10)));
        assertEquals(Zone.ALPINE_TURF, GroundZones.zone(in(0, 2500, 10)));
        assertEquals(Zone.ARABLE, GroundZones.zone(in(16, 500, 2)));
        assertEquals(Zone.ORCHARD, GroundZones.zone(in(12, 500, 2)));
        assertEquals(Zone.VINEYARD, GroundZones.zone(in(13, 500, 20)));
        assertEquals(Zone.GARDEN, GroundZones.zone(in(11, 560, 2)));
        assertEquals(Zone.URBAN_SEALED, GroundZones.zone(in(10, 560, 2)));
        GroundZones.Input dry = in(0, 700, 28);
        dry.northness = -1;
        assertEquals(Zone.DRY_GRASSLAND, GroundZones.zone(dry));
        GroundZones.Input wet = in(9, 1100, 2);
        assertEquals(Zone.RAISED_BOG, GroundZones.zone(wet));
        wet.waterDist = 1;
        assertEquals(Zone.WET_MEADOW, GroundZones.zone(wet));
        GroundZones.Input aue = in(0, 560, 2);
        aue.waterDist = 4;
        aue.nearWaterKind = 2;
        assertEquals(Zone.FLOODPLAIN, GroundZones.zone(aue));
        GroundZones.Input reeds = in(0, 560, 2);
        reeds.waterDist = 1;
        reeds.nearWaterKind = 1;
        assertEquals(Zone.REED_SHORE, GroundZones.zone(reeds));
        reeds.h = 1900;
        reeds.belt = Belt.of(1900, 1850, 2750, 0);
        assertEquals(Zone.ALPINE_LAKE_SHORE, GroundZones.zone(reeds));
    }

    @Test
    void forests() {
        assertEquals(Zone.BEECH, GroundZones.zone(in(1, 600, 10)));
        assertEquals(Zone.FIR_SPRUCE, GroundZones.zone(in(2, 1200, 10)));
        assertEquals(Zone.SPRUCE, GroundZones.zone(in(2, 1500, 10)));
        assertEquals(Zone.LARCH_PINE, GroundZones.zone(in(2, 1750, 10)));
        assertEquals(Zone.KRUMMHOLZ, GroundZones.zone(in(14, 2000, 10)));
        GroundZones.Input edge = in(2, 1200, 10);
        edge.openDist = 1;
        assertEquals(Zone.FOREST_EDGE, GroundZones.zone(edge));
        GroundZones.Input alder = in(1, 600, 3);
        alder.waterDist = 2;
        alder.nearWaterKind = 3;
        assertEquals(Zone.RIPARIAN, GroundZones.zone(alder));
        GroundZones.Input ticino = in(1, 400, 10);
        ticino.region = Region.SOUTH_FLANK;
        assertEquals(Zone.CHESTNUT, GroundZones.zone(ticino));
    }

    @Test
    void rockLooseIce() {
        GroundZones.Input wall = in(4, 2200, 60);
        wall.lith = Lith.CRYSTALLINE;
        assertEquals(Zone.CRYSTALLINE_CLIFF, GroundZones.zone(wall));
        wall.lith = Lith.LIMESTONE;
        assertEquals(Zone.LIMESTONE_CLIFF, GroundZones.zone(wall));
        GroundZones.Input karst = in(4, 1900, 10);
        karst.lith = Lith.LIMESTONE;
        assertEquals(Zone.KARST, GroundZones.zone(karst));
        GroundZones.Input cone = in(5, 2000, 30);
        assertEquals(Zone.SCREE_CONE, GroundZones.zone(cone));
        GroundZones.Input fore = in(5, 2300, 8);
        fore.glacierDist = 5;
        assertEquals(Zone.FOREFIELD, GroundZones.zone(fore));
        fore.tpi = 4;
        assertEquals(Zone.MORAINE, GroundZones.zone(fore));
        assertEquals(Zone.GLACIER, GroundZones.zone(withSnow(in(6, 3000, 10), 20)));
        assertEquals(Zone.CREVASSED, GroundZones.zone(in(6, 2600, 30)));
        GroundZones.Input debris = in(6, 2300, 8);
        debris.openDist = 2;
        assertEquals(Zone.DEBRIS_GLACIER, GroundZones.zone(debris));
        assertEquals(Zone.FIRN, GroundZones.zone(withSnow(in(0, 3000, 10), 16)));
        GroundZones.Input wet = in(8, 500, 0);
        wet.wet = true;
        assertEquals(Zone.WATER, GroundZones.zone(wet));
    }

    private static GroundZones.Input withSnow(GroundZones.Input c, int snow) {
        c.snow = snow;
        return c;
    }

    @Test
    void beltsFollowTreelineAndAspect() {
        assertEquals(Belt.COLLINE, Belt.of(600, 1850, 2750, 0));
        assertEquals(Belt.MONTANE, Belt.of(1200, 1850, 2750, 0));
        assertEquals(Belt.SUBALPINE, Belt.of(1700, 1850, 2750, 0));
        assertEquals(Belt.ALPINE, Belt.of(2200, 1850, 2750, 0));
        assertEquals(Belt.NIVAL, Belt.of(2900, 1850, 2750, 0));
        // a south slope lifts the bounds
        assertEquals(Belt.SUBALPINE, Belt.of(1900, 1850, 2750, 1));
    }

    @Test
    void climateLinesByRegion() {
        // Valais (Zermatt) above the north flank (Interlaken), the northern Alpine edge (Santis) lowest
        double zermatt = ClimateLines.snowLine(2_624_000, 1_097_000);
        double interlaken = ClimateLines.snowLine(2_632_900, 1_170_600);
        double santis = ClimateLines.snowLine(2_744_000, 1_234_000);
        assertTrue(zermatt > interlaken + 150, zermatt + " vs " + interlaken);
        assertTrue(interlaken > santis, interlaken + " vs " + santis);
        assertTrue(zermatt >= 2900 && zermatt <= 3100, "Valais " + zermatt);
        assertTrue(santis >= 2550 && santis <= 2750, "Santis " + santis);
        double tz = ClimateLines.treeline(2_624_000, 1_097_000), ti = ClimateLines.treeline(2_632_900, 1_170_600);
        assertTrue(tz > 2200 && ti < 1950, tz + " / " + ti);
    }

    @Test
    void snowGrowsWithHeightAndOnNorthSlopes() {
        for (double slope : new double[] {0, 15, 30, 40}) {
            for (double hollow : new double[] {0, 2, 5}) {
                double prev = -1;
                for (double h = 2000; h <= 3800; h += 10) {
                    double lineN = Snow.line(2750, slope, 1, hollow, 0), lineS = Snow.line(2750, slope, -1, hollow, 0);
                    double n = Snow.depth(h, lineN, slope, hollow), s = Snow.depth(h, lineS, slope, hollow);
                    assertTrue(n >= s, "north >= south at " + h + " m, " + slope + " deg");
                    double flat = Snow.depth(h, Snow.line(2750, slope, 0, hollow, 0), slope, hollow);
                    assertTrue(flat >= prev, "monotonic in height at " + h);
                    prev = flat;
                    double deeper = Snow.depth(h, Snow.line(2750, slope, 0, hollow + 1, 0), slope, hollow + 1);
                    assertTrue(deeper >= flat, "hollows hold more");
                }
            }
        }
        // the band: nothing well below the line, a full block well above, steep walls shed
        assertEquals(0, Snow.depth(2500, 2750, 5, 0));
        assertTrue(Snow.depth(3000, 2750, 5, 0) >= 8);
        assertEquals(0, Snow.depth(3500, 2750, 55, 0));
        assertTrue(Snow.depth(3500, 2750, 44, 0) < Snow.depth(3500, 2750, 20, 0));
    }

    @Test
    void lithologyGuess() {
        assertEquals(Lith.CRYSTALLINE, Lith.guess(2_668_500, 1_157_500, 2100, Region.of(2_668_500, 1_157_500)));
        assertEquals(Lith.LIMESTONE, Lith.guess(2_560_000, 1_220_000, 900, Region.JURA));
        assertEquals(Lith.MOLASSE, Lith.guess(2_600_000, 1_200_000, 550, Region.MITTELLAND));
    }
}
