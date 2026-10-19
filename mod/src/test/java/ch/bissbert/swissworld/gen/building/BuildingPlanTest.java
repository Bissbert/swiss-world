package ch.bissbert.swissworld.gen.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;
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

    /** The rendered fixture is closed (roof, walls, panes), has a door from outside onto floor 0 and a walkable landing. */
    @Test
    void renderedFixtureIsClosedWithDoor() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        assertNotNull(plan.door());
        assertEquals(List.of(), BuildingRender.check(new BuildingRender(plan, view(cell))));
    }

    /** Panes only on straight runs: a column whose side neighbour is not a wall of the same face never gets one. */
    @Test
    void panesNeedStraightWall() {
        Columns c = plan.cols();
        int y = plan.floorY(0) + 2;
        // middle of the north facade: neighbours left and right are facade columns facing the same way
        assertTrue(BuildingStage.straight(c, c.minX + 3, y, c.minZ, Dir.N));
        // corner column: the neighbour towards the outside is not a facade of the same face
        assertFalse(BuildingStage.straight(c, c.minX, y, c.minZ, Dir.N));
    }

    @Test
    void everyFloorReachable() {
        assertNotNull(plan.stairs());
        assertTrue(plan.reachable());
    }

    /** Tank, large wall, covered bridge, open building and canopy have no door, interior or loot; a greenhouse is a hall. */
    @Test
    void specialTypes() {
        SwdCell.Building b = cell.buildings().get(0);
        for (int oa : new int[]{7, 8, 9, 10, 11, 12}) {
            SwdCell.Building t = new SwdCell.Building(b.gid(), b.egid(), oa, b.gwrCategory(), b.gwrClass(),
                    b.gwrPeriod(), b.floors(), b.entranceSide(), b.baseH(), b.topH(), b.minX(), b.minZ(), b.maxX(),
                    b.maxZ());
            SwdCell c = copy(cell, cell.eKm(), t, cell.spans());
            BuildingPlan p = BuildingPlan.of(view(c), c, t);
            assertEquals(1, p.floors().size(), "objektart " + oa);
            assertTrue(p.reachable());
            if (oa == 8) {
                assertEquals(Style.GREENHOUSE, p.style());
                assertFalse(p.rooms().isEmpty());
                assertTrue(p.props().stream().anyMatch(pr -> pr.kind().container()));
            } else {
                assertEquals(oa == 7 ? Style.TANK : oa == 9 ? Style.WALL : oa == 10 ? Style.BRIDGE : Style.CANOPY,
                        p.style());
                assertNull(p.door());
                assertTrue(p.rooms().isEmpty() && p.props().isEmpty() && p.lights().isEmpty(), "objektart " + oa);
            }
        }
    }

    /** A building of overhang spans only (free-standing roof) is a canopy whatever its OBJEKTART. */
    @Test
    void overhangOnlyIsCanopy() {
        SwdCell.Building b = cell.buildings().get(0);
        Map<Integer, SwdCell.Span[]> spans = new HashMap<>();
        cell.spans().forEach((k, v) -> {
            SwdCell.Span[] o = new SwdCell.Span[v.length];
            for (int i = 0; i < v.length; i++) {
                o[i] = new SwdCell.Span(v[i].bottomH(), v[i].topH(), v[i].buildingIdx(), 1, v[i].roofFlags());
            }
            spans.put(k, o);
        });
        SwdCell c = copy(cell, cell.eKm(), b, spans);
        BuildingPlan p = BuildingPlan.of(view(c), c, b);
        assertEquals(Style.CANOPY, p.style());
        assertNull(p.door());
        assertTrue(p.rooms().isEmpty() && p.props().isEmpty() && p.lights().isEmpty());
        assertTrue(p.cols().corner(p.cols().minX, p.cols().minZ));
        assertFalse(p.cols().corner(p.cols().minX + 2, p.cols().minZ + 2));
    }

    /** CellView over a block-column lookup (as GenContext.cellAt) must find the same cell as a km lookup. */
    @Test
    void cellViewFromColumns() {
        CellView v = CellView.ofColumns(ORIGIN, (x, z) -> SwissCoords.cellKm(ORIGIN.east(x)) == cell.eKm()
                && SwissCoords.cellKm(ORIGIN.north(z)) == cell.nKm() ? cell : null);
        assertTrue(v.cell(-800, -300) == cell);
        assertEquals(1, Buildings.near(v, -800, -304).size());
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
        // (floors and rooms are not compared with the unsplit plan: the shifted copies see other terrain around
        // the footprint, and the ground floor is lifted towards the ground around it)
        assertEquals(fromWest.floors(), fromEast.floors());
    }

    /** In game each chunk starts with its own cell only; the fallback must supply the neighbour, once. */
    @Test
    void neighbourCellFromFallback() {
        SwdCell.Building b = cell.buildings().get(0);
        Map<Integer, SwdCell.Span[]> wSpans = new HashMap<>(), eSpans = new HashMap<>();
        for (int dz = b.minZ(); dz <= b.maxZ(); dz++) {
            for (int dx = b.minX(); dx <= b.maxX(); dx++) {
                SwdCell.Span[] s = cell.spansAt(dx, dz);
                if (s == null) continue;
                if (dx + 895 < 1000) wSpans.put(SwdCell.key(dx + 895, dz), s);
                else eSpans.put(SwdCell.key(dx + 895 - 1000, dz), s);
            }
        }
        SwdCell west = copy(cell, cell.eKm(), shifted(b, 895), wSpans);
        SwdCell east = copy(cell, cell.eKm() + 1, shifted(b, -105), eSpans);
        int[] asked = {0};
        GenContext.CellLookup store = (e, n) -> {
            asked[0]++;
            return e == west.eKm() && n == west.nKm() ? west : e == east.eKm() && n == east.nKm() ? east : null;
        };
        BuildingPlan fromWest = BuildingPlan.of(view(west).orElse(store), west, west.buildings().get(0));
        BuildingPlan fromEast = BuildingPlan.of(view(east).orElse(store), east, east.buildings().get(0));
        assertSame(fromWest, fromEast);
        assertSame(fromWest, BuildingPlan.of(view(west, east), west, west.buildings().get(0)));
        assertTrue(asked[0] <= 4, "memoised fallback, asked " + asked[0]);
        // the cache must not hand the half-known plan to a view that has both cells
        BuildingPlan half = Buildings.plan(view(west), west, west.buildings().get(0));
        BuildingPlan full = Buildings.plan(view(west, east), west, west.buildings().get(0));
        assertFalse(half.rooms().equals(full.rooms()), "home-only plan differs");
        assertEquals(fromWest.rooms(), full.rooms());
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
