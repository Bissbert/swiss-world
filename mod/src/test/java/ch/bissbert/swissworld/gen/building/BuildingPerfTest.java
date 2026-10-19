package ch.bissbert.swissworld.gen.building;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.Heights;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Planning must stay bounded for huge halls and rooms whose floor is mostly unusable (worldgen re-plans per chunk). */
class BuildingPerfTest {
    private static final SwissCoords ORIGIN = new SwissCoords(2_632_900, 1_170_600);

    /** A flat cell at 600 m holding one 1271 (barn) building; {@code lowEvery} out of each n columns have a roof too low to use. */
    private static BuildingPlan plan(int w, int d, int usableEvery) {
        int n = SwdCell.SIZE * SwdCell.SIZE;
        short[] terr = new short[SwdCell.TERR_N * SwdCell.TERR_N];
        Arrays.fill(terr, (short) Heights.encode(600));
        short[] none = new short[n];
        Arrays.fill(none, (short) Heights.NONE);
        Map<Integer, SwdCell.Span[]> spans = new HashMap<>();
        int x0 = 100, z0 = 100;
        for (int z = z0; z < z0 + d; z++) {
            for (int x = x0; x < x0 + w; x++) {
                boolean ok = usableEvery <= 1 || x % usableEvery < 3 && z % usableEvery < 3; // sparse tall columns in a low roof
                spans.put(SwdCell.key(x, z), new SwdCell.Span[]{new SwdCell.Span(Heights.encode(600), Heights.encode(ok ? 608 : 602.5), 0, 0, 0)});
            }
        }
        SwdCell.Building b = new SwdCell.Building(7L, 7L, 1, 1020, 1271, 8015, 1, 0, Heights.encode(600), Heights.encode(608), x0, z0, x0 + w - 1, z0 + d - 1);
        SwdCell cell = new SwdCell(2632, 1170, terr, new byte[n], none, new byte[n], new byte[n], none.clone(), new byte[n], null, List.of(b), spans);
        CellView view = new CellView(ORIGIN, (e, nn) -> e == 2632 && nn == 1170 ? cell : null);
        return BuildingPlan.of(view, cell, b);
    }

    private static long millis(int w, int d, int usableEvery) {
        long t = System.nanoTime();
        BuildingPlan p = plan(w, d, usableEvery);
        long ms = (System.nanoTime() - t) / 1_000_000;
        for (BuildingPlan.Room r : p.rooms()) {
            assertTrue(p.lights().stream().anyMatch(l -> l.x() >= r.x0() && l.x() <= r.x1() && l.z() >= r.z0() && l.z() <= r.z1()), "room without light " + r);
        }
        System.out.println("perf " + w + "x" + d + " usableEvery=" + usableEvery + ": " + ms + " ms, rooms=" + p.rooms().size() + " lights=" + p.lights().size());
        return ms;
    }

    @Test
    void hugeHallsPlanFast() {
        millis(60, 60, 1); // warm up
        long a = millis(200, 200, 1), b = millis(300, 100, 30), c = millis(200, 200, 60);
        assertTrue(a < 100, "200x200 hall took " + a + " ms");
        assertTrue(b < 100, "300x100 mostly unusable hall took " + b + " ms");
        assertTrue(c < 100, "200x200 nearly unusable hall took " + c + " ms");
    }
}
