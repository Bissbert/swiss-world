package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.gen.surface.TreePlan.Part;
import ch.bissbert.swissworld.gen.surface.TreePlan.Species;
import ch.bissbert.swissworld.gen.surface.TreePlan.Tree;
import java.util.HashSet;
import java.util.List;
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
            if (t.x() <= -385 + TreePlan.REACH && t.x() >= -384 - TreePlan.REACH) {
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
    void leavesKnowTheirDistanceToTheTrunk() {
        for (Species s : Species.values()) {
            for (long h = 0; h < 50; h++) {
                List<Part> parts = TreePlan.shape(s, h * 0x9E3779B97F4A7C15L);
                boolean hasLog = parts.stream().anyMatch(Part::log);
                assertEquals(s != Species.BUSH, hasLog, s.toString());
                Set<List<Integer>> seen = new HashSet<>();
                for (Part p : parts) {
                    assertTrue(seen.add(List.of(p.dx(), p.dy(), p.dz())), "duplicate block in " + s);
                    assertTrue(Math.abs(p.dx()) <= TreePlan.REACH && Math.abs(p.dz()) <= TreePlan.REACH);
                    if (p.log()) continue;
                    if (hasLog) assertTrue(p.distance() >= 1 && p.distance() <= 6);
                    else assertEquals(0, p.distance());
                }
            }
        }
        assertFalse(TreePlan.shape(Species.SPRUCE, 1).isEmpty());
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
