package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.surface.TreePlan.Part;
import ch.bissbert.swissworld.gen.surface.TreePlan.Site;
import ch.bissbert.swissworld.gen.surface.TreePlan.Species;
import ch.bissbert.swissworld.gen.surface.TreePlan.Tree;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class TreeGrowerTest {
    private static final int[][] N6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Random sites: uneven room, slopes either way, altitudes up to the tree line, a terrain step under the crown. */
    private static Site site(SplittableRandom rnd) {
        float[] room = new float[8];
        float free = 1 + rnd.nextInt(TreePlan.REACH);
        for (int i = 0; i < 8; i++) room[i] = rnd.nextBoolean() ? free : (float) Math.max(1, free * rnd.nextDouble());
        int[] floor = new int[Site.SIDE * Site.SIDE];
        int step = rnd.nextInt(6), dir = rnd.nextInt(4);
        for (int dz = -TreePlan.REACH; dz <= TreePlan.REACH; dz++) {
            for (int dx = -TreePlan.REACH; dx <= TreePlan.REACH; dx++) {
                int along = dir == 0 ? dx : dir == 1 ? -dx : dir == 2 ? dz : -dz;
                floor[(dz + TreePlan.REACH) * Site.SIDE + dx + TreePlan.REACH] = along >= 3 ? step : along <= -3 ? -step : 0;
            }
        }
        if (rnd.nextInt(5) == 0) floor[(2 + TreePlan.REACH) * Site.SIDE + TreePlan.REACH] = Integer.MAX_VALUE; // a building
        return new Site(room, free, 400 + rnd.nextDouble() * 1850, rnd.nextDouble() * 1.6 - 0.8, rnd.nextDouble() * 1.6 - 0.8, floor);
    }

    @Test
    void everyArchetypeKeepsItsVanillaLeafDistancesOnAnySite() {
        SplittableRandom rnd = new SplittableRandom(42);
        for (Species s : Species.values()) {
            for (int h = 2; h <= 40; h++) {
                for (int k = 0; k < 6; k++) {
                    Site site = k == 0 ? Site.open(TreePlan.REACH) : site(rnd);
                    long hash = rnd.nextLong();
                    List<Part> parts = TreeGrower.grow(s, h, hash, site);
                    String what = s + " h" + h + " " + site;
                    Map<List<Integer>, Part> at = new HashMap<>();
                    for (Part p : parts) {
                        assertNull(at.put(List.of(p.dx(), p.dy(), p.dz()), p), "duplicate block in " + what);
                        assertTrue(Math.abs(p.dx()) <= TreePlan.REACH && Math.abs(p.dz()) <= TreePlan.REACH && p.dy() >= 0 && p.dy() < Math.max(2, h), what);
                        assertTrue(p.dy() >= site.floor(p.dx(), p.dz()), "below the floor: " + what + " " + p);
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
                        // exactly what vanilla's updateShape computes from the neighbours: no update changes it, none decays
                        int best = Integer.MAX_VALUE;
                        for (int[] d : N6) {
                            Part q = at.get(List.of(p.dx() + d[0], p.dy() + d[1], p.dz() + d[2]));
                            if (q != null) best = Math.min(best, q.log() ? 0 : q.distance());
                        }
                        assertTrue(p.distance() >= 1 && p.distance() <= TreePlan.LEAF_REACH, what);
                        assertEquals(best + 1, p.distance(), "leaf distance in " + what + " at " + p);
                    }
                    // branch logs touch the rest of the tree face to face
                    for (Part p : parts) {
                        if (!p.log() || p.dx() == 0 && p.dz() == 0 && p.dy() == 0) continue;
                        boolean joined = false;
                        for (int[] d : N6) {
                            Part q = at.get(List.of(p.dx() + d[0], p.dy() + d[1], p.dz() + d[2]));
                            joined |= q != null && q.log();
                        }
                        assertTrue(joined, "floating log in " + what + " at " + p);
                    }
                }
            }
        }
    }

    @Test
    void crownsGrowIntoTheFreeSide() {
        float[] room = new float[8];
        Arrays.fill(room, 6);
        // a neighbour to the east: little room east, north-east, south-east
        room[0] = 1.5f;
        room[1] = room[7] = 2.5f;
        Site crowded = new Site(room, 6, 600, 0, 0, null);
        for (Species s : new Species[] {Species.OAK, Species.SPRUCE, Species.BIRCH}) {
            List<Part> p = TreeGrower.grow(s, 24, 5, crowded);
            long east = p.stream().filter(q -> !q.log() && q.dx() >= 2).count(), west = p.stream().filter(q -> !q.log() && q.dx() <= -2).count();
            assertTrue(west > 2 * east, s + " west " + west + " east " + east);
            assertTrue(p.stream().allMatch(q -> q.dx() <= 2), s + " reaches past its room");
        }
    }

    @Test
    void denseStandsPruneTheirLowerTrunk() {
        float[] tight = new float[8];
        Arrays.fill(tight, 1.5f);
        for (Species s : new Species[] {Species.OAK, Species.SPRUCE}) {
            int solo = lowestLeaf(TreeGrower.grow(s, 28, 9, Site.open(5))), dense = lowestLeaf(TreeGrower.grow(s, 28, 9, new Site(tight, 5, 600, 0, 0, null)));
            assertTrue(dense >= solo + 6, s + " crown base solitary " + solo + ", in a stand " + dense);
        }
    }

    @Test
    void treesNearTheTreeLineAreNarrowerAndFlagged() {
        Site low = new Site(open8(5), 5, 1200, 0, 0, null), high = new Site(open8(5), 5, 2200, 0, 0, null);
        int lowLeaves = 0, highLeaves = 0, westHigh = 0, eastHigh = 0;
        for (long hash = 0; hash < 20; hash++) {
            lowLeaves += leaves(TreeGrower.grow(Species.SPRUCE, 16, hash, low), q -> true);
            List<Part> p = TreeGrower.grow(Species.SPRUCE, 16, hash, high);
            highLeaves += leaves(p, q -> true);
            westHigh += leaves(p, q -> q.dx() <= -2);
            eastHigh += leaves(p, q -> q.dx() >= 2);
        }
        assertTrue(highLeaves < 0.75 * lowLeaves, "high " + highLeaves + " low " + lowLeaves);
        assertTrue(eastHigh > 1.5 * westHigh, "wind-flagged: west " + westHigh + " east " + eastHigh);
    }

    private static float[] open8(float r) {
        float[] room = new float[8];
        Arrays.fill(room, r);
        return room;
    }

    private static int leaves(List<Part> parts, Predicate<Part> where) {
        return (int) parts.stream().filter(p -> !p.log() && where.test(p)).count();
    }

    private static int lowestLeaf(List<Part> parts) {
        return parts.stream().filter(p -> !p.log() && (p.dx() != 0 || p.dz() != 0)).mapToInt(Part::dy).min().orElseThrow();
    }

    @Test
    void neighbourCrownsLeaveGapsForLight() {
        for (int surf : new int[] {2, 1}) {
            SwdCell c = Cells.flat(surf == 2 ? 1200 : 700);
            Arrays.fill(c.surf(), (byte) surf);
            Map<List<Integer>, Integer> owner = new HashMap<>();
            int leaves = 0, shared = 0, chunks = 0, openColumns = 0;
            for (int cx = -160; cx < -96; cx += 16) {
                for (int cz = 0; cz < 64; cz += 16) {
                    ColumnGrid g = Cells.grid(c, cx, cz);
                    List<Tree> trees = TreePlan.trees(g, cx, cz, cx + 15, cz + 15);
                    boolean[] covered = new boolean[256];
                    for (Tree t : trees) {
                        for (Part p : TreePlan.shape(t)) {
                            int x = t.x() + p.dx(), z = t.z() + p.dz();
                            if (p.log() || x < cx || x > cx + 15 || z < cz || z > cz + 15) continue;
                            covered[(z - cz) * 16 + x - cx] = true;
                            leaves++;
                            Integer was = owner.put(List.of(x, p.dy() + g.ground(t.x(), t.z()), z), t.x() * 4096 + t.z());
                            if (was != null && was != t.x() * 4096 + t.z()) shared++;
                        }
                    }
                    for (boolean b : covered) if (!b) openColumns++;
                    chunks++;
                }
            }
            // crowns meet at their tips at most, and some sky reaches the forest floor in every chunk
            assertTrue(shared <= 0.02 * leaves, "surf " + surf + ": " + shared + " of " + leaves + " leaves claimed twice");
            assertTrue(openColumns >= 0.1 * 256 * chunks, "surf " + surf + ": open columns " + openColumns + " in " + chunks + " chunks");
        }
    }

    /** A second flat cell 2633_1170 east of {@link Cells#flat}'s, at another height, meadow with a road. */
    private static SwdCell east() {
        SwdCell f = Cells.flat(930);
        SwdCell c = new SwdCell(2633, 1170, f.terr(), f.surf(), f.wlvl(), f.wknd(), f.ocls(), f.oelv(), f.oflg(), f.insd(),
                f.buildings(), f.spans(), null, null);
        for (int dz = 0; dz < SwdCell.SIZE; dz++) c.ocls()[SwdCell.key(3, dz)] = 3;
        return c;
    }

    /** Cells as the game loads them for the chunk at (x0, z0): only those under a corner of the chunk plus its margin. */
    private static ColumnGrid.Cells loadedFor(int x0, int z0, SwdCell... cells) {
        Map<Long, SwdCell> loaded = new HashMap<>();
        int m = ColumnGrid.MARGIN;
        for (int x : new int[] {x0 - m, x0 + 15 + m}) {
            for (int z : new int[] {z0 - m, z0 + 15 + m}) {
                int e = SwissCoords.cellKm(Cells.ORIGIN.east(x)), n = SwissCoords.cellKm(Cells.ORIGIN.north(z));
                for (SwdCell c : cells) if (c.eKm() == e && c.nKm() == n) loaded.put(((long) e << 32) | n, c);
            }
        }
        return (x, z) -> loaded.get(((long) SwissCoords.cellKm(Cells.ORIGIN.east(x)) << 32) | SwissCoords.cellKm(Cells.ORIGIN.north(z)));
    }

    @Test
    void chunksWithDifferentCellsLoadedAgreeOnATreeByTheCellBorder() {
        SwdCell west = Cells.flat(900), east = east();
        Arrays.fill(west.surf(), (byte) 1);
        // cell 2633 starts at x = 100: the chunk 64..79 loads only 2632, the chunk 80..95 both
        ColumnGrid a = new ColumnGrid(Cells.ORIGIN, loadedFor(64, 0, west, east), 64, 0);
        ColumnGrid b = new ColumnGrid(Cells.ORIGIN, loadedFor(80, 0, west, east), 80, 0);
        assertNull(loadedFor(64, 0, west, east).at(100, 0));
        assertEquals(east, loadedFor(80, 0, west, east).at(100, 0));
        List<Tree> left = TreePlan.trees(a, 64, 0, 79, 15), right = TreePlan.trees(b, 80, 0, 95, 15);
        int shared = 0;
        for (Tree t : left) {
            if (t.x() + t.radius() < 80) continue;
            assertTrue(right.contains(t), "chunk 80 sees otherwise: " + t);
            assertEquals(TreePlan.shape(t), TreePlan.shape(right.get(right.indexOf(t))));
            shared++;
        }
        for (Tree t : right) if (t.x() - t.radius() <= 79) assertTrue(left.contains(t), "chunk 64 sees otherwise: " + t);
        assertTrue(shared > 3, "shared " + shared);
    }

    @Test
    void forestGapsHoldYoungTrees() {
        SwdCell c = Cells.flat(900);
        Arrays.fill(c.surf(), (byte) 2);
        int young = 0, all = 0;
        for (int cx = -400; cx < 0; cx += 16) {
            ColumnGrid g = Cells.grid(c, cx, 0);
            for (Tree t : TreePlan.trees(g, cx, 0, cx + 15, 15)) {
                if (t.x() < cx || t.x() > cx + 15 || t.z() < 0 || t.z() > 15) continue;
                all++;
                if (t.height() <= 7) young++;
            }
        }
        assertTrue(young > 0.03 * all && young < 0.25 * all, "young " + young + " of " + all);
    }
}
