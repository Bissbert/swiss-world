package ch.bissbert.swissworld.gen.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.gen.building.BuildingPlan;
import ch.bissbert.swissworld.gen.building.CellView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/**
 * The real-terrain entrance sweep: runs the whole pipeline over the playtest spots (grown by SWISSWORLD_SWEEP_CHUNKS,
 * default 2, plus the chunk boxes in SWISSWORLD_ENTRANCE_AREAS=cx0,cz0,cx1,cz1;...) of real store cells
 * (SWISSWORLD_SWEEP_STORE) and checks every door of a building with rooms whose surroundings were generated: the cell
 * outside the door is air (feet and head) and a walker gets from there, in steps of at most one block with two blocks
 * of headroom, to ground at least 3 blocks away from it (out of the landing). Failures go to $ENTRANCE_OUT.
 */
class EntranceSweepTest {
    /** Chebyshev distance from the outside cell a walker has to reach. */
    static final int REACH = 3;

    @Test
    void everyDoorOpensOntoWalkableGround() throws Exception {
        String dir = System.getenv("SWISSWORLD_SWEEP_STORE");
        assumeTrue(dir != null, "SWISSWORLD_SWEEP_STORE not set");
        int grow = Integer.parseInt(System.getenv().getOrDefault("SWISSWORLD_SWEEP_CHUNKS", "2"));
        List<int[]> areas = new ArrayList<>();
        for (int[] a : PipelineGoldenTest.AREAS) areas.add(new int[] {a[0] - grow, a[1] - grow, a[2] + grow, a[3] + grow});
        String more = System.getenv("SWISSWORLD_ENTRANCE_AREAS");
        if (more != null && !more.isBlank()) {
            for (String s : more.split(";")) {
                String[] v = s.trim().split(",");
                areas.add(new int[] {Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3])});
            }
        }
        Pipeline p = new Pipeline(Path.of(dir));
        CellView view = new CellView(Pipeline.ORIGIN, p::cell);
        Map<Long, SitePlan> plans = new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, SitePlan> e) {
                return size() > 4;
            }
        };
        List<String> bad = new ArrayList<>();
        Map<String, Integer> why = new TreeMap<>();
        int doors = 0, chunks = 0;
        List<String> doorList = new ArrayList<>(); // outside cell (feet) of every door checked: $ENTRANCE_DOORS (in-game probes)
        for (int[] a : areas) {
            Map<Long, ProtoChunk> done = new HashMap<>();
            for (int cz = a[1]; cz <= a[3]; cz++) {
                for (int cx = a[0]; cx <= a[2]; cx++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    int tx = SitePlan.tile(pos.getMinBlockX()), tz = SitePlan.tile(pos.getMinBlockZ());
                    SitePlan site = plans.computeIfAbsent(((long) tx << 32) ^ (tz & 0xFFFFFFFFL),
                            k -> SitePlans.compute(p::cell, Pipeline.ORIGIN, tx, tz));
                    ProtoChunk c = Pipeline.chunk(pos);
                    Pipeline.run(p.context(c, site));
                    done.put(pos.toLong(), c);
                    chunks++;
                }
            }
            Blocks3 w = new Blocks3(done);
            int x0 = a[0] * 16 + REACH + 2, z0 = a[1] * 16 + REACH + 2, x1 = a[2] * 16 + 15 - REACH - 2, z1 = a[3] * 16 + 15 - REACH - 2;
            Set<Long> seen = new HashSet<>();
            for (int[] corner : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
                SwdCell cell = view.cell(corner[0], corner[1]);
                if (cell == null || cell.buildings() == null) continue;
                for (SwdCell.Building b : cell.buildings()) {
                    if (!seen.add(b.gid())) continue;
                    if (view.blockX(cell, b.maxX()) < x0 - 40 || view.blockX(cell, b.minX()) > x1 + 40
                            || view.blockZ(cell, b.maxZ()) < z0 - 40 || view.blockZ(cell, b.minZ()) > z1 + 40) continue;
                    BuildingPlan bp = BuildingPlan.of(view, cell, b);
                    BuildingPlan.Door d = bp.door();
                    if (d == null || bp.rooms().isEmpty()) continue;
                    int ox = d.x() + d.out().dx, oz = d.z() + d.out().dz, y = bp.floorY(0) + d.up() + 1;
                    if (ox < x0 || ox > x1 || oz < z0 || oz > z1) continue;
                    doors++;
                    doorList.add(ox + " " + y + " " + oz + " " + b.gid());
                    String fail = check(w, bp, ox, y, oz);
                    if (fail == null) continue;
                    SitePlan site = plans.computeIfAbsent(((long) SitePlan.tile(ox) << 32) ^ (SitePlan.tile(oz) & 0xFFFFFFFFL),
                            k -> SitePlans.compute(p::cell, Pipeline.ORIGIN, SitePlan.tile(ox), SitePlan.tile(oz)));
                    String claim = site.claim(ox, oz) + " G" + (site.ground(ox, oz) - (y - 1));
                    why.merge(fail.split(" ")[0] + " " + site.claim(ox, oz), 1, Integer::sum);
                    int[] t = ch.bissbert.swissworld.gen.building.BuildingStage.trace(bp.cols(), d, y - 1, view);
                    double h = view.terrain(ox, oz);
                    String diag = " trace " + (t == null ? "none" : java.util.Arrays.toString(t)) + " foreign " + view.foreign(ox, oz, b.gid())
                            + " way " + view.way(ox, oz) + " terrain " + (Double.isNaN(h) ? "?" : String.valueOf((int) Math.floor(ch.bissbert.swissworld.cell.SwissCoords.toY(h)) - (y - 1)));
                    bad.add(b.gid() + " " + bp.style() + " door " + d + " outside " + ox + "," + y + "," + oz + " " + claim + ": " + fail + diag);
                    if (System.getenv("ENTRANCE_DUMP") != null) {
                        // the way out, cell by cell (rows: y - 1 .. y + 5 top down; columns: -1, 0, +1 across)
                        ch.bissbert.swissworld.gen.building.Dir side = d.out().cw();
                        for (int dy = 5; dy >= -1; dy--) {
                            StringBuilder sb = new StringBuilder("   y" + (dy >= 0 ? "+" : "") + dy + " ");
                            for (int i = 0; i <= 6; i++) {
                                for (int wv = -1; wv <= 1; wv++) {
                                    int qx = ox + d.out().dx * i + side.dx * wv, qz = oz + d.out().dz * i + side.dz * wv;
                                    String nm = name(w.at(qx, y + dy, qz));
                                    sb.append(String.format("%-6.6s", nm.equals("air") ? "." : nm)).append(wv == 1 ? " | " : " ");
                                }
                            }
                            bad.add(sb.toString());
                        }
                        StringBuilder cl = new StringBuilder("   claim ");
                        for (int i = 0; i <= 6; i++) {
                            for (int wv = -1; wv <= 1; wv++) {
                                int qx = ox + d.out().dx * i + side.dx * wv, qz = oz + d.out().dz * i + side.dz * wv;
                                cl.append(String.format("%-6.6s", site.claim(qx, qz).name() + (site.ground(qx, qz) - (y - 1)))).append(wv == 1 ? " | " : " ");
                            }
                        }
                        bad.add(cl.toString());
                    }
                }
            }
        }
        System.out.println("entrance sweep: " + chunks + " chunks, " + doors + " doors, " + bad.stream().filter(l -> !l.startsWith("   ")).count() + " bad " + why);
        Path out = Path.of(System.getenv().getOrDefault("ENTRANCE_OUT", "build/entrance-sweep.txt")).toAbsolutePath();
        Files.createDirectories(out.getParent());
        Files.write(out, bad);
        if (System.getenv("ENTRANCE_DOORS") != null) Files.write(Path.of(System.getenv("ENTRANCE_DOORS")), doorList);
        assertEquals(List.of(), bad.stream().filter(l -> !l.startsWith("   ")).limit(10).toList());
    }

    /** Null if the door opens onto walkable ground, else what is wrong. */
    static String check(Blocks3 w, BuildingPlan bp, int ox, int y, int oz) {
        if (!pass(w.at(ox, y, oz)) || !pass(w.at(ox, y + 1, oz))) {
            return "blocked feet " + name(w.at(ox, y, oz)) + " head " + name(w.at(ox, y + 1, oz));
        }
        if (!stand(w.at(ox, y - 1, oz))) return "nofloor under " + name(w.at(ox, y - 1, oz));
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(new int[] {ox, y, oz});
        seen.add(BlockPos.asLong(ox, y, oz));
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (Math.max(Math.abs(c[0] - ox), Math.abs(c[2] - oz)) >= REACH) return null;
            for (int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c[0] + dir[0], nz = c[2] + dir[1];
                if (bp.cols().body(nx, nz)) continue; // not back into the building
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = c[1] + dy;
                    if (dy == 1 && !pass(w.at(c[0], c[1] + 2, c[2]))) continue;
                    if (dy == -1 && !pass(w.at(nx, ny + 2, nz))) continue;
                    if (!pass(w.at(nx, ny, nz)) || !pass(w.at(nx, ny + 1, nz)) || !stand(w.at(nx, ny - 1, nz))) continue;
                    if (seen.add(BlockPos.asLong(nx, ny, nz))) q.add(new int[] {nx, ny, nz});
                }
            }
        }
        return "trapped " + seen.size() + " cells reachable";
    }

    static boolean pass(BlockState s) {
        return s.isAir() || s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty() && s.getFluidState().isEmpty();
    }

    static boolean stand(BlockState s) {
        return !s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    static String name(BlockState s) {
        return s.getBlock().builtInRegistryHolder().key().location().getPath();
    }

    /** Blocks of generated proto chunks; air outside them. */
    record Blocks3(Map<Long, ProtoChunk> chunks) {
        BlockState at(int x, int y, int z) {
            ProtoChunk c = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
            return c == null ? Blocks.AIR.defaultBlockState() : c.getBlockState(new BlockPos(x, y, z));
        }
    }
}
