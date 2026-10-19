package ch.bissbert.swissworld.gen.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.cell.SwissCoords;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildingPlanTest {
    private static final Path FIX = Path.of(System.getProperty("swissworld.fixtures", "../fixtures"));
    private static final SwissCoords ORIGIN = new SwissCoords(2_632_900, 1_170_600);
    private static SwdCell cell;
    private static BuildingPlan plan;

    @BeforeAll
    static void load() throws Exception {
        cell = SwdReader.read(FIX.resolve("synthetic_2632_1170.swd"));
        plan = BuildingPlan.of(view(cell), cell, cell.buildings().get(0));
    }

    static CellView view(SwdCell... cells) {
        return new CellView(ORIGIN, (e, n) -> {
            for (SwdCell c : cells) if (c.eKm() == e && c.nKm() == n) return c;
            return null;
        });
    }

    @Test
    void fixtureStoreysAndDoor() {
        // building dx 100..109, dz 100..107 -> x -800..-791, z -300..-293; base 610 m -> y -1390, GASTW 2
        Columns c = plan.cols();
        assertEquals(List.of(-800, -300, -791, -293), List.of(c.minX, c.minZ, c.maxX, c.maxZ));
        assertEquals(4, plan.storey());
        assertEquals(List.of(-1390, -1386), plan.floors());
        assertEquals(new BuildingPlan.Door(-796, -293, Dir.S), plan.door());
        assertEquals(Style.TOWN_HOUSE, plan.style());
    }

    @Test
    void roofFollowsRidge() {
        Columns c = plan.cols();
        int[] tops = new int[8];
        for (int i = 0; i < 8; i++) tops[i] = c.topFull(-795, -300 + i);
        // ridge along dz 103/104 (z -297/-296), symmetric, descending outward
        for (int i = 0; i < 4; i++) assertEquals(tops[i], tops[7 - i]);
        assertTrue(tops[3] > tops[0], "ridge above eaves: " + java.util.Arrays.toString(tops));
        for (int i = 0; i < 3; i++) assertTrue(tops[i] <= tops[i + 1]);
        for (int x = c.minX; x <= c.maxX; x++) {
            assertEquals(Dir.S, c.uphill(x, -300), "north eave stairs face the ridge");
            assertEquals(Dir.N, c.uphill(x, -293), "south eave stairs face the ridge");
            assertNull(c.uphill(x, -297));
            assertNull(c.uphill(x, -296));
        }
    }

    @Test
    void interiorHasStairsRoomsLightsAndLoot() {
        assertNotNull(plan.stairs());
        assertFalse(plan.stairs().ladder());
        assertFalse(plan.rooms().isEmpty());
        for (BuildingPlan.Room r : plan.rooms()) {
            assertTrue(plan.lights().stream().anyMatch(l -> l.x() >= r.x0() && l.x() <= r.x1() && l.z() >= r.z0()
                    && l.z() <= r.z1()), "light in " + r);
            assertTrue(plan.props().stream().anyMatch(p -> p.kind().container() && p.room() == r.type()
                    && p.x() >= r.x0() && p.x() <= r.x1() && p.z() >= r.z0() && p.z() <= r.z1()), "container in " + r);
        }
        for (BuildingPlan.Prop p : plan.props()) {
            assertFalse(plan.reserved(p.x(), p.z()), "prop on stairs " + p);
            assertTrue(plan.cols().interior(p.x(), p.z()));
        }
        // flights rise exactly one storey and the landing is solid floor
        BuildingPlan.Stairs s = plan.stairs();
        int lx = s.x() + s.dir().dx * plan.storey(), lz = s.z() + s.dir().dz * plan.storey();
        assertTrue(plan.floorAt(1, lx, lz) && !plan.cut(1, lx, lz));
    }

    @Test
    void deterministic() {
        BuildingPlan again = BuildingPlan.of(view(cell), cell, cell.buildings().get(0));
        assertSame(plan, again);
    }

    /** A copy of the fixture building straddling the cell border at E 2633000 plans the same from either cell. */
    @Test
    void sameAcrossCellBorder() {
        SwdCell.Building b = cell.buildings().get(0);
        int shift = 895; // dx 100..109 -> 995..1004
        Map<Integer, SwdCell.Span[]> wSpans = new HashMap<>(), eSpans = new HashMap<>();
        for (int dz = b.minZ(); dz <= b.maxZ(); dz++) {
            for (int dx = b.minX(); dx <= b.maxX(); dx++) {
                SwdCell.Span[] s = cell.spansAt(dx, dz);
                if (s == null) continue;
                int nx = dx + shift;
                if (nx < 1000) wSpans.put(SwdCell.key(nx, dz), s);
                else eSpans.put(SwdCell.key(nx - 1000, dz), s);
            }
        }
        SwdCell west = copy(cell, cell.eKm(), shifted(b, shift), wSpans);
        SwdCell east = copy(cell, cell.eKm() + 1, shifted(b, shift - 1000), eSpans);
        CellView both = view(west, east);
        BuildingPlan fromWest = BuildingPlan.of(both, west, west.buildings().get(0));
        BuildingPlan fromEast = BuildingPlan.of(both, east, east.buildings().get(0));
        assertSame(fromWest, fromEast);
        assertEquals(plan.floors(), fromWest.floors());
        assertEquals(plan.rooms().size(), fromWest.rooms().size());
    }

    static void assertSame(BuildingPlan a, BuildingPlan b) {
        assertEquals(a.style(), b.style());
        assertEquals(a.floors(), b.floors());
        assertEquals(a.door(), b.door());
        assertEquals(a.stairs(), b.stairs());
        assertEquals(a.rooms(), b.rooms());
        assertEquals(a.walls(), b.walls());
        assertEquals(a.props(), b.props());
        assertEquals(a.lights(), b.lights());
        for (int z = a.cols().minZ; z <= a.cols().maxZ; z++) {
            for (int x = a.cols().minX; x <= a.cols().maxX; x++) {
                assertEquals(a.cols().state(x, z), b.cols().state(x, z));
                if (a.cols().body(x, z)) assertEquals(a.cols().top2(x, z), b.cols().top2(x, z));
            }
        }
    }

    static SwdCell.Building shifted(SwdCell.Building b, int dx) {
        return new SwdCell.Building(b.gid(), b.egid(), b.objektart(), b.gwrCategory(), b.gwrClass(), b.gwrPeriod(),
                b.floors(), b.entranceSide(), b.baseH(), b.topH(), b.minX() + dx, b.minZ(), b.maxX() + dx, b.maxZ());
    }

    static SwdCell copy(SwdCell c, int eKm, SwdCell.Building b, Map<Integer, SwdCell.Span[]> spans) {
        return new SwdCell(eKm, c.nKm(), c.terr(), c.surf(), c.wlvl(), c.wknd(), c.ocls(), c.oelv(), c.oflg(), c.insd(),
                List.of(b), spans);
    }
}
