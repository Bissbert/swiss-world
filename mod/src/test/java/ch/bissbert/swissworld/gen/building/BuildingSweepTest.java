package ch.bissbert.swissworld.gen.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.cell.SwissCoords;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plans every building of a real cell (SWISSWORLD_SWEEP_CELL=path/to/cell.swd, origin as the dev world) and checks
 * reachability, a light and a container per room. Skipped without the variable.
 */
class BuildingSweepTest {
    @Test
    void sweep() throws Exception {
        String f = System.getenv("SWISSWORLD_SWEEP_CELL");
        assumeTrue(f != null && !f.isEmpty(), "SWISSWORLD_SWEEP_CELL not set");
        SwdCell cell = SwdReader.read(Path.of(f));
        CellView v = new CellView(new SwissCoords(2_632_900, 1_170_600),
                (e, n) -> e == cell.eKm() && n == cell.nKm() ? cell : null);
        List<String> bad = new ArrayList<>();
        Map<Style, Integer> styles = new EnumMap<>(Style.class);
        int rooms = 0, ladders = 0, pillars = 0, multi = 0;
        for (SwdCell.Building b : cell.buildings()) {
            BuildingPlan p;
            try {
                p = BuildingPlan.of(v, cell, b);
            } catch (RuntimeException e) {
                bad.add(b.gid() + " threw " + e);
                continue;
            }
            styles.merge(p.style(), 1, Integer::sum);
            rooms += p.rooms().size();
            if (p.floors().size() > 1) multi++;
            if (p.stairs() != null && p.stairs().ladder()) {
                ladders++;
                BuildingPlan.Stairs s = p.stairs();
                if (p.pillar(s.x() - s.dir().dx, s.z() - s.dir().dz)) pillars++;
            }
            if (!p.reachable()) bad.add(b.gid() + " unreachable floors " + p.floors());
            for (BuildingPlan.Room r : p.rooms()) {
                if (p.lights().stream().noneMatch(l -> in(r, l.x(), l.z()))) bad.add(b.gid() + " no light " + r);
                if (p.props().stream().noneMatch(q -> q.kind().container() && in(r, q.x(), q.z()))) {
                    bad.add(b.gid() + " no container " + r);
                }
            }
        }
        System.out.printf("sweep: %d buildings, %d multi-floor, %d rooms, %d ladders (%d on pillars), %s%n",
                cell.buildings().size(), multi, rooms, ladders, pillars, styles);
        assertEquals(List.of(), bad);
    }

    static boolean in(BuildingPlan.Room r, int x, int z) {
        return x >= r.x0() && x <= r.x1() && z >= r.z0() && z <= r.z1();
    }
}
