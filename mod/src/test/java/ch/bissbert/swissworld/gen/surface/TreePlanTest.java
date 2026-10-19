package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.gen.surface.TreePlan.Part;
import ch.bissbert.swissworld.gen.surface.TreePlan.Species;
import ch.bissbert.swissworld.gen.surface.TreePlan.Tree;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TreePlanTest {
    @Test
    void fixtureForestIsDenseAndKeepsClearOfTheRoad() throws Exception {
        SwdCell cell = Cells.fixture();
        // forest dx 500..599 is x -400..-301; the road with the bridge is z -102..-98
        int count = 0, meadow = 0;
        for (int cx = -400; cx < -300; cx += 16) {
            for (int cz = -400; cz < 600; cz += 16) {
                ColumnGrid g = Cells.grid(cell, cx, cz);
                int x0 = Math.floorDiv(cx, 16) * 16, z0 = Math.floorDiv(cz, 16) * 16;
                for (Tree t : TreePlan.trees(g, x0, z0, x0 + 15, z0 + 15)) {
                    assertTrue(t.z() < -105 || t.z() > -95, "tree next to the road at " + t);
                    if (t.x() < x0 || t.x() > x0 + 15 || t.z() < z0 || t.z() > z0 + 15) continue;
                    if (t.x() >= -400 && t.x() <= -301) count++;
                    else meadow++;
                }
            }
        }
        // 100 x 1000 m at one candidate per 16 m^2, 90 % kept, minus the road strip
        assertTrue(count > 5000 && count < 6000, "trees " + count);
        // the chunks also reach into the meadow on both sides, where trees are rare
        assertTrue(meadow < 20, "meadow trees " + meadow);
    }

    @Test
    void neighbouringChunksAgreeOnSharedTrees() throws Exception {
        SwdCell cell = Cells.fixture();
        // chunks either side of x = -384
        ColumnGrid a = Cells.grid(cell, -390, 0), b = Cells.grid(cell, -380, 0);
        Set<Tree> left = new HashSet<>(TreePlan.trees(a, -400, 0, -385, 15));
        List<Tree> right = TreePlan.trees(b, -384, 0, -369, 15);
        int shared = 0;
        for (Tree t : right) {
            if (t.x() <= -385 + TreePlan.STAT_REACH && t.x() >= -384 - TreePlan.STAT_REACH) {
                assertTrue(left.contains(t), "left chunk misses " + t);
                shared++;
            }
        }
        assertTrue(shared > 0);
    }

    @Test
    void speciesFollowAltitudeBands() {
        assertEquals(Species.OAK, TreePlan.species(1, 600, 0.1));
        assertEquals(Species.BIRCH, TreePlan.species(1, 600, 0.9));
        assertEquals(Species.SPRUCE, TreePlan.species(1, 1700, 0.1));
        assertEquals(Species.SPRUCE, TreePlan.species(2, 600, 0.1));
        assertEquals(Species.LARCH, TreePlan.species(2, 2100, 0.1));
        assertNull(TreePlan.species(2, 2400, 0.1));
        assertEquals(Species.BUSH, TreePlan.species(14, 600, 0.1));
        assertEquals(0, TreePlan.density(7));
    }

    @Test
    void leavesKeepTheirVanillaDistanceForEverySpeciesAndSize() {
        int[][] n6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (Species s : Species.values()) {
            for (int h = 2; h <= 40; h += 2) {
                for (int r = 1; r <= TreePlan.REACH; r++) {
                    long hash = (h * 131L + r) * 0x9E3779B97F4A7C15L;
                    List<Part> parts = TreePlan.shape(s, h, r, hash);
                    String what = s + " h" + h + " r" + r;
                    Map<List<Integer>, Part> at = new HashMap<>();
                    for (Part p : parts) {
                        assertNull(at.put(List.of(p.dx(), p.dy(), p.dz()), p), "duplicate block in " + what);
                        assertTrue(Math.abs(p.dx()) <= r && Math.abs(p.dz()) <= r && p.dy() >= 0 && p.dy() < Math.max(2, h), what);
                        assertEquals(p.log(), p.axis() != null, what);
                    }
                    boolean hasLog = parts.stream().anyMatch(Part::log);
                    assertEquals(s != Species.BUSH, hasLog, what);
                    if (hasLog) assertTrue(at.get(List.of(0, 0, 0)).log(), "trunk of " + what);
                    assertTrue(parts.stream().anyMatch(p -> !p.log()), "no leaves on " + what);
                    for (Part p : parts) {
                        if (p.log()) continue;
                        if (!hasLog) {
                            assertEquals(0, p.distance(), what);
                            continue;
                        }
                        // exactly what vanilla's updateShape would compute from the neighbours: nothing changes on an update
                        int best = Integer.MAX_VALUE;
                        for (int[] d : n6) {
                            Part q = at.get(List.of(p.dx() + d[0], p.dy() + d[1], p.dz() + d[2]));
                            if (q != null) best = Math.min(best, q.log() ? 0 : q.distance());
                        }
                        assertTrue(p.distance() >= 1 && p.distance() <= 6, what);
                        assertEquals(best + 1, p.distance(), "leaf distance in " + what + " at " + p);
                    }
                }
            }
        }
    }

    @Test
    void largeTreesAreTallAndWide() {
        List<Part> spruce = TreePlan.shape(Species.SPRUCE, 32, TreePlan.crown(Species.SPRUCE, 32), 7);
        assertEquals(31, spruce.stream().mapToInt(Part::dy).max().orElseThrow());
        assertTrue(spruce.stream().anyMatch(p -> !p.log() && Math.abs(p.dx()) == 5), "spruce crown radius");
        List<Part> oak = TreePlan.shape(Species.OAK, 30, TreePlan.REACH, 7);
        assertTrue(oak.stream().anyMatch(p -> !p.log() && Math.abs(p.dx()) >= 7), "oak crown reaches out");
        assertTrue(oak.stream().anyMatch(p -> p.log() && p.axis() != TreePlan.Axis.Y), "oak has branches");
    }

    @Test
    void statisticalHeightsAreRealistic() throws Exception {
        for (double r = 0; r < 1; r += 0.01) {
            int forest = TreePlan.height(Species.SPRUCE, 2, 1200, r), open = TreePlan.height(Species.OAK, 0, 600, r);
            assertTrue(forest >= 18 && forest <= 35, "forest " + forest);
            assertTrue(open >= 8 && open <= 20, "open " + open);
            assertTrue(TreePlan.height(Species.OAK, 14, 600, r) <= 20 && TreePlan.height(Species.OAK, 11, 600, r) >= 8);
        }
        assertEquals(18, TreePlan.height(Species.SPRUCE, 2, 1200, 0));
        assertEquals(35, TreePlan.height(Species.SPRUCE, 2, 1200, 0.9999));
        // in the fixture forest every tree is forest-tall, its crown no wider than the statistical limit
        SwdCell cell = Cells.fixture();
        ColumnGrid g = Cells.grid(cell, -350, 200);
        List<Tree> trees = TreePlan.trees(g, -352, 192, -337, 207);
        assertTrue(trees.size() > 20);
        Set<Integer> heights = new HashSet<>();
        for (Tree t : trees) {
            if (t.x() < -400 || t.x() > -301) continue;
            assertTrue(t.height() >= 18 && t.height() <= 35, "height " + t);
            assertTrue(t.radius() >= 1 && t.radius() <= TreePlan.STAT_REACH, "radius " + t);
            heights.add(t.height());
        }
        assertTrue(heights.size() > 5, "heights vary: " + heights);
    }

    @Test
    void treeTableAnswersByArea() {
        TreeTops t = TreeTops.sorted(List.of(new SwdCell.Tree(500, 998, 12, 0, 0), new SwdCell.Tree(999, 5, 200, 75, 2),
                new SwdCell.Tree(10, 5, 30, 0, 1), new SwdCell.Tree(0, 6, 9, 0, 0)));
        assertEquals(4, t.size());
        assertEquals(List.of(new SwdCell.Tree(10, 5, 30, 0, 1), new SwdCell.Tree(999, 5, 200, 75, 2)), t.within(1, 0, 999, 5));
        assertEquals(List.of(new SwdCell.Tree(0, 6, 9, 0, 0), new SwdCell.Tree(500, 998, 12, 0, 0)), t.within(-5, 6, 600, 1200));
        assertTrue(t.within(11, 0, 998, 997).isEmpty());
    }

    @Test
    void treeDataReadsTheCellsOfTheChunk() {
        SwdCell flat = forest(), withTrees = withTrees(flat, List.of(new SwdCell.Tree(905, 405, 28, 45, SwdCell.Tree.CONIFER)));
        ColumnGrid.Cells only = (x, z) -> x >= -900 && x < 100 && z >= -400 && z < 600 ? withTrees : null;
        TreeData data = TreeData.of(Cells.ORIGIN, only);
        assertEquals(1, data.tops(2632, 1170).size());
        assertNull(data.tops(2633, 1170));
        assertNull(TreeData.of(Cells.ORIGIN, (x, z) -> flat).tops(2632, 1170));
        assertEquals(0, TreeData.of(Cells.ORIGIN, (x, z) -> withTrees(flat, List.of())).tops(2632, 1170).size());
    }

    private static SwdCell withTrees(SwdCell c, List<SwdCell.Tree> trees) {
        return new SwdCell(c.eKm(), c.nKm(), c.terr(), c.surf(), c.wlvl(), c.wknd(), c.ocls(), c.oelv(), c.oflg(), c.insd(),
                c.buildings(), c.spans(), null, trees);
    }

    /** A flat forest cell 2632_1170 whose tree table is {@code tops} (null: no TTOP layer). */
    private static TreeData table(List<SwdCell.Tree> tops) {
        TreeTops t = tops == null ? null : TreeTops.sorted(tops);
        return (eKm, nKm) -> eKm == 2632 && nKm == 1170 ? t : null;
    }

    private static SwdCell forest() {
        SwdCell c = Cells.flat(900);
        Arrays.fill(c.surf(), (byte) 2);
        return c;
    }

    @Test
    void aTreeTableReplacesTheStatisticalTrees() {
        SwdCell c = forest();
        ColumnGrid g = Cells.grid(c, 0, 0);
        assertTrue(TreePlan.trees(g, table(null), 0, 0, 15, 15).size() > 10);
        assertTrue(TreePlan.trees(g, table(List.of()), 0, 0, 15, 15).isEmpty());
        // one real conifer at x 5, z 5: dx 905, dz 405 (x = dx - 900, z = dz - 400)
        List<Tree> one = TreePlan.trees(g, table(List.of(new SwdCell.Tree(905, 405, 28, 45, SwdCell.Tree.CONIFER))), 0, 0, 15, 15);
        assertEquals(1, one.size());
        Tree t = one.get(0);
        assertEquals(List.of(5, 5, 28, 5), List.of(t.x(), t.z(), t.height(), t.radius()));
        assertEquals(Species.SPRUCE, t.species());
    }

    @Test
    void neighbouringChunksAgreeOnLargeCrowns() {
        SwdCell c = forest();
        // real trees with the widest crowns (8 m in the data, clamped to REACH) on both sides of the chunk border at x = 0 (dx 900), every 3 columns
        List<SwdCell.Tree> tops = new ArrayList<>();
        for (int x = -10; x <= 10; x++) {
            for (int z = 0; z < 16; z += 3) tops.add(new SwdCell.Tree(900 + x, 400 + z, 25 + (x & 7), 80, x % 2 == 0 ? 1 : 2));
        }
        TreeData data = table(tops);
        List<Tree> left = TreePlan.trees(Cells.grid(c, -16, 0), data, -16, 0, -1, 15);
        List<Tree> right = TreePlan.trees(Cells.grid(c, 0, 0), data, 0, 0, 15, 15);
        List<Tree> both = left.stream().filter(right::contains).toList();
        // every tree whose crown reaches both chunks is in both lists, in the same order
        for (Tree t : left) {
            if (t.x() + t.radius() >= 0) assertTrue(right.contains(t), "right chunk misses " + t);
        }
        for (Tree t : right) {
            if (t.x() - t.radius() <= -1) assertTrue(left.contains(t), "left chunk misses " + t);
        }
        assertEquals(both, right.stream().filter(left::contains).toList());
        assertTrue(both.stream().anyMatch(t -> t.x() == TreePlan.REACH - 1 && t.z() == 0) && both.stream().anyMatch(t -> t.x() == -TreePlan.REACH), "the widest crown reaches across");
        assertTrue(both.stream().allMatch(t -> t.radius() == TreePlan.REACH));
        // and they agree on every block of those trees
        for (Tree t : both) assertEquals(TreePlan.shape(t), TreePlan.shape(right.get(right.indexOf(t))));
    }

    @Test
    void treesGrowOverADeepTunnelButNotNextToARoad() {
        SwdCell c = Cells.flat(700);
        for (int z = -16; z < 32; z++) {
            Cells.surf(c, 0, z, 2);
            Cells.surf(c, 1, z, 2);
            Cells.overlay(c, 0, z, 3, ColumnGrid.TUNNEL, 650); // 50 m under the forest
            Cells.overlay(c, 10, z, 3, 0, Double.NaN);
        }
        ColumnGrid g = Cells.grid(c, 0, 0);
        assertFalse(TreePlan.surfaceOverlay(g, 0, 0));
        assertTrue(TreePlan.surfaceOverlay(g, 10, 0));
        assertTrue(TreePlan.allowed(g, 1, 0));
        assertFalse(TreePlan.allowed(g, 8, 0));
        // a shallow tunnel still blocks
        Cells.overlay(c, 0, 0, 3, ColumnGrid.TUNNEL, 695);
        assertTrue(TreePlan.surfaceOverlay(Cells.grid(c, 0, 0), 0, 0));
    }
}
