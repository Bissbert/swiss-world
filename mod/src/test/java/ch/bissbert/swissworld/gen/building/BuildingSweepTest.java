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

    /**
     * Renders every building into a block array (real stages, chunk by chunk) and floods the outside air from the
     * boundary of the padded bbox. Doors, glass and panes are solid. Interior air (body columns between floor and
     * roof) that the flood reaches is a leak; every building with rooms needs a door from outside air into
     * interior air on floor 0 that a walker can reach. Acceptance: the 959 buildings of the origin cell.
     */
    @Test
    void watertight() throws Exception {
        String f = System.getenv("SWISSWORLD_SWEEP_CELL");
        assumeTrue(f != null && !f.isEmpty(), "SWISSWORLD_SWEEP_CELL not set");
        SwdCell cell = SwdReader.read(Path.of(f));
        CellView v = new CellView(new SwissCoords(2_632_900, 1_170_600),
                (e, n) -> e == cell.eKm() && n == cell.nKm() ? cell : null);
        List<String> bad = watertight(List.of(cell), v, "origin cell");
        // cell-edge buildings (a neighbour is missing) are skipped; those are covered by the store sweep below
        assertEquals(List.of(), bad.stream().limit(10).toList());
    }

    /** Same over cells of a store (SWISSWORLD_SWEEP_STORE=dir with cells/, SWISSWORLD_SWEEP_LIST=2634_1171,...). */
    @Test
    void watertightStore() throws Exception {
        String dir = System.getenv("SWISSWORLD_SWEEP_STORE"), list = System.getenv("SWISSWORLD_SWEEP_LIST");
        assumeTrue(dir != null && list != null && !dir.isEmpty(), "SWISSWORLD_SWEEP_STORE/LIST not set");
        Map<String, SwdCell> memo = new java.util.HashMap<>();
        CellView v = new CellView(new SwissCoords(2_632_900, 1_170_600), (e, n) -> memo.computeIfAbsent(e + "_" + n, k -> {
            try {
                Path p = Path.of(dir, "cells", k + ".swd");
                return java.nio.file.Files.isRegularFile(p) ? SwdReader.read(p) : null;
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        }));
        List<SwdCell> cells = new ArrayList<>();
        for (String k : list.split(",")) {
            String[] en = k.trim().split("_");
            SwdCell c = v.cells().get(Integer.parseInt(en[0]), Integer.parseInt(en[1]));
            if (c != null && c.buildings() != null) cells.add(c);
        }
        List<String> bad = watertight(cells, v, "store cells " + list);
        assertEquals(List.of(), bad.stream().limit(10).toList());
    }

    /**
     * Writes probe targets for the in-game check (SWISSWORLD_PROBE_OUT): per building its door cells, the cell inside,
     * roof columns over rooms (top block per the offline render) and panes with their run axis. Buildings: the gids in
     * SWISSWORLD_PROBE_GIDS plus the first SWISSWORLD_PROBE_PER_STYLE (default 3) complete buildings of each style.
     */
    @Test
    void probeTargets() throws Exception {
        String dir = System.getenv("SWISSWORLD_SWEEP_STORE"), list = System.getenv("SWISSWORLD_SWEEP_LIST"),
                out = System.getenv("SWISSWORLD_PROBE_OUT");
        assumeTrue(dir != null && list != null && out != null, "probe env not set");
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        java.util.Set<String> gids = new java.util.HashSet<>(List.of(System.getenv().getOrDefault("SWISSWORLD_PROBE_GIDS", "").split(",")));
        int per = Integer.parseInt(System.getenv().getOrDefault("SWISSWORLD_PROBE_PER_STYLE", "3"));
        Map<String, SwdCell> memo = new java.util.HashMap<>();
        CellView v = new CellView(new SwissCoords(2_632_900, 1_170_600), (e, n) -> memo.computeIfAbsent(e + "_" + n, k -> {
            try {
                Path p = Path.of(dir, "cells", k + ".swd");
                return java.nio.file.Files.isRegularFile(p) ? SwdReader.read(p) : null;
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        }));
        List<String> lines = new ArrayList<>();
        Map<Style, Integer> taken = new java.util.EnumMap<>(Style.class);
        for (String k : list.split(",")) {
            String[] en = k.trim().split("_");
            SwdCell cell = v.cells().get(Integer.parseInt(en[0]), Integer.parseInt(en[1]));
            if (cell == null || cell.buildings() == null) continue;
            for (SwdCell.Building b : cell.buildings()) {
                boolean pick = gids.contains(Long.toString(b.gid()));
                BuildingPlan p = BuildingPlan.of(v, cell, b);
                if (p.door() == null || p.rooms().isEmpty()) continue;
                if (!pick && taken.getOrDefault(p.style(), 0) >= per) continue;
                BuildingRender r = new BuildingRender(p, v);
                if (r.incomplete && !pick || !BuildingRender.check(r).isEmpty()) continue;
                taken.merge(p.style(), 1, Integer::sum);
                BuildingPlan.Door d = p.door();
                int y = p.floorY(0) + d.up() + 1;
                lines.add("B " + b.gid() + " " + p.style() + " " + d.x() + " " + y + " " + d.z() + " " + d.out().dx + " "
                        + d.out().dz + " " + (d.x() - d.out().dx) + " " + (d.z() - d.out().dz));
                int top = p.floors().size() - 1, n = 0;
                for (BuildingPlan.Room room : p.rooms()) {
                    if (room.floor() != top || n >= 3) continue;
                    int cx = (room.x0() + room.x1()) / 2, cz = (room.z0() + room.z1()) / 2, fy = p.floorY(top);
                    if (!p.cols().body(cx, cz) || p.cols().topFull(cx, cz) <= fy + 2 || !r.air(cx, fy + 1, cz)) continue;
                    int t = r.y0 + r.h - 1;
                    while (t > fy && r.air(cx, t, cz)) t--;
                    lines.add("R " + cx + " " + (fy + 1) + " " + cz + " " + t);
                    n++;
                }
                n = 0;
                for (int yy = r.y0; yy < r.y0 + r.h && n < 3; yy++) {
                    for (int z = p.cols().minZ; z <= p.cols().maxZ && n < 3; z++) {
                        for (int x = p.cols().minX; x <= p.cols().maxX && n < 3; x++) {
                            net.minecraft.world.level.block.state.BlockState bs = r.at(x, yy, z);
                            if (!(bs.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock)) continue;
                            boolean ew = bs.getValue(net.minecraft.world.level.block.CrossCollisionBlock.EAST);
                            lines.add("P " + x + " " + yy + " " + z + " " + (ew ? "x" : "z"));
                            n++;
                        }
                    }
                }
            }
        }
        java.nio.file.Files.write(Path.of(out), lines);
        System.out.println("probeTargets: " + lines.stream().filter(l -> l.startsWith("B")).count() + " buildings " + taken);
    }

    /**
     * Renders and checks every building of the cells; prints one summary line and writes the problems to
     * $WATERTIGHT_OUT (default /tmp/watertight.txt). WT_NEAR=x,z adds the plan, ground ring, side view and top view of
     * the buildings around that block column, whether they fail or not.
     */
    static List<String> watertight(List<SwdCell> cells, CellView v, String label) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        List<String> bad = new ArrayList<>();
        Map<String, Integer> causes = new java.util.TreeMap<>();
        int total = 0, checked = 0, skipped = 0, withRooms = 0, leaking = 0, doorless = 0, badDoor = 0, openPanes = 0, hemmed = 0, thin = 0;
        String near = System.getenv("WT_NEAR");
        for (SwdCell cell : cells) {
            for (SwdCell.Building b : cell.buildings()) {
                total++;
                BuildingPlan p = BuildingPlan.of(v, cell, b);
                BuildingRender r = new BuildingRender(p, v);
                if (r.incomplete) skipped++; // a neighbour cell is missing: flat ground and unknown columns, still checked
                checked++;
                if (!p.rooms().isEmpty()) withRooms++;
                List<String> why = BuildingRender.check(r);
                if (near != null) bad.addAll(inspect(near, p, r, v, why));
                if (why.isEmpty()) continue;
                for (String w : why) {
                    causes.merge(p.style() + " " + w.split(" ")[0] + (w.startsWith("door") ? " " + w.split(" ")[1] : ""), 1, Integer::sum);
                }
                if (why.stream().anyMatch(w -> w.startsWith("leak"))) leaking++;
                if (why.stream().anyMatch(w -> w.startsWith("pane-open"))) openPanes++;
                if (why.stream().anyMatch(w -> w.startsWith("nodoor"))) doorless++;
                else if (why.stream().anyMatch(w -> w.startsWith("door"))) badDoor++;
                else if (why.stream().anyMatch(w -> w.startsWith("hemmed"))) hemmed++;
                else if (why.stream().anyMatch(w -> w.startsWith("thin"))) thin++;
                bad.add(b.gid() + " " + p.style() + " bbox " + p.cols().minX + ".." + p.cols().maxX + " " + p.cols().minZ + ".."
                        + p.cols().maxZ + " door=" + p.door() + " " + why);
            }
        }
        System.out.printf("watertight[%s]: %d buildings, %d checked, %d at a missing cell edge, %d with rooms, %d leaking, "
                + "%d without door, %d bad door, %d with open panes, %d hemmed in by neighbours, %d thin; %s%n", label, total,
                checked, skipped, withRooms, leaking, doorless, badDoor, openPanes, hemmed, thin, causes);
        // acceptance: nothing leaks or lacks a door; a door may only fail where neighbouring buildings or a thin shell
        // leave no room (a handful per thousand)
        org.junit.jupiter.api.Assertions.assertTrue(hemmed + thin <= Math.max(2, withRooms / 100),
                "hemmed in " + hemmed + ", thin " + thin);
        java.nio.file.Files.write(Path.of(System.getenv().getOrDefault("WATERTIGHT_OUT", "/tmp/watertight.txt")), bad);
        return bad.stream().filter(l -> !l.startsWith("NEAR") && !l.contains("[hemmed ") && !l.contains("[thin ")).toList();
    }

    private static List<String> inspect(String near, BuildingPlan p, BuildingRender r, CellView v, List<String> why) {
        String[] q = near.split(",");
        int qx = Integer.parseInt(q[0]), qz = Integer.parseInt(q[1]);
        Columns c = p.cols();
        if (qx < c.minX - 2 || qx > c.maxX + 2 || qz < c.minZ - 2 || qz > c.maxZ + 2) return List.of();
        StringBuilder sb = new StringBuilder("NEAR gid=" + p.gid() + " " + p.style() + " bbox " + c.minX + ".." + c.maxX + " "
                + c.minZ + ".." + c.maxZ + " baseY=" + c.baseY + " y0=" + p.floorY(0) + " floors=" + p.floors()
                + " storey=" + p.storey() + " door=" + p.door() + " why=" + why + "\nterrain minus floor 0 around the footprint:\n");
        for (int z = c.minZ - 1; z <= c.maxZ + 1; z++) {
            for (int x = c.minX - 1; x <= c.maxX + 1; x++) {
                double h = v.terrain(x, z);
                sb.append(Double.isNaN(h) ? " ??" : String.format("%3d", (int) Math.floor(SwissCoords.toY(h)) - p.floorY(0)));
            }
            sb.append('\n');
        }
        sb.append("\nmap (B body, e eave, F other building, D door, o outside cell, . free):\n");
        for (int z = c.minZ - 8; z <= c.maxZ + 8; z++) {
            for (int x = c.minX - 8; x <= c.maxX + 8; x++) {
                BuildingPlan.Door d = p.door();
                char ch = c.state(x, z) == Columns.BODY ? 'B' : c.state(x, z) == Columns.EAVE ? 'e' : v.foreign(x, z, p.gid()) ? 'F' : '.';
                if (d != null && x == d.x() && z == d.z()) ch = 'D';
                else if (d != null && x == d.x() + d.out().dx && z == d.z() + d.out().dz) ch = 'o';
                sb.append(ch);
            }
            sb.append('\n');
        }
        return List.of(sb.toString(), r.dump(), r.top());
    }

    static boolean in(BuildingPlan.Room r, int x, int z) {
        return x >= r.x0() && x <= r.x1() && z >= r.z0() && z <= r.z1();
    }
}
