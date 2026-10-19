package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.gen.surface.OverlayPlan.Rail;
import ch.bissbert.swissworld.gen.surface.Voxels.B;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransportPlanTest {
    private static final int RAIL = ColumnGrid.RAIL, TUNNEL = ColumnGrid.TUNNEL;

    // ------------------------------------------------------------------ roads

    /** A 6-wide polished andesite road running north-south up a 12 % grade with a 30 % cross slope and a spike. */
    private static SwdCell slopedRoad() {
        SwdCell c = Cells.flat(600);
        Voxels.terrain(c, (x, z) -> 600 - 0.12 * z + 0.3 * x);
        for (int z = -60; z <= 60; z++) {
            for (int x = 0; x <= 5; x++) Cells.overlay(c, x, z, 3, x == 0 || x == 5 ? ColumnGrid.EDGE : 0, Double.NaN);
        }
        // a single column 3 m above the slope, as noisy terrain data has
        Cells.ground(c, 2, 7, 600 - 0.12 * 7 + 0.3 * 2 + 3);
        return c;
    }

    @Test
    void roadClimbsInHalfStepsWithoutBumps() {
        Voxels v = new Voxels(slopedRoad(), -8, -40, 14, 40, -1440, -1370);
        List<String> steps = new ArrayList<>(), bumps = new ArrayList<>();
        int slabs = 0;
        for (int z = -40; z <= 40; z++) {
            for (int x = 0; x <= 5; x++) {
                if (v.slab[v.col(x, z)]) slabs++;
                double h = v.walk(x, z), hi = Double.NEGATIVE_INFINITY;
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], nz = z + d[1];
                    if (nx < 0 || nx > 5 || nz < -40 || nz > 40) continue;
                    double n = v.walk(nx, nz);
                    if (Math.abs(h - n) > 0.5) steps.add(x + "," + z + ": " + h + " vs " + n);
                    hi = Math.max(hi, n);
                }
                if (h >= hi + 1) bumps.add(x + "," + z);
            }
        }
        assertTrue(steps.isEmpty(), () -> "steps over half a block: " + steps);
        assertTrue(bumps.isEmpty(), () -> "bumps: " + bumps);
        assertTrue(slabs > 50, "half steps are slabs: " + slabs);
        // the spike is cut away: nothing stands on the road at the spike column
        int d = v.deck[v.col(2, 7)];
        for (int y = d + 2; y < -1370; y++) assertEquals(B.AIR, v.at(2, y, 7), "above spike at " + y);
    }

    @Test
    void roadShoulderIsGradedToTheDeck() {
        Voxels v = new Voxels(slopedRoad(), -8, -40, 14, 40, -1440, -1370);
        for (int z = -38; z <= 38; z++) {
            for (int x : new int[] {-1, 6}) {
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int dz = -1; dz <= 1; dz++) {
                    int rx = x < 0 ? 0 : 5;
                    lo = Math.min(lo, v.deck[v.col(rx, z + dz)]);
                    hi = Math.max(hi, v.deck[v.col(rx, z + dz)]);
                }
                int top = topSolid(v, x, z);
                assertTrue(top >= lo && top <= hi, "shoulder " + x + "," + z + " top " + top + " not in " + lo + ".." + hi);
            }
            // two columns out: within one block of the deck (a 1:1 slope)
            int top = topSolid(v, -2, z), deck = v.deck[v.col(0, z)];
            assertTrue(Math.abs(top - deck) <= 2, "slope at -2," + z + ": " + top + " vs deck " + deck);
        }
    }

    private static int topSolid(Voxels v, int x, int z) {
        for (int y = v.y0 + v.ny - 1; y >= v.y0; y--) if (v.at(x, y, z) == B.SOLID) return y;
        return v.y0 - 1;
    }

    @Test
    void roadCrossesAStreamOnACulvert() {
        SwdCell c = Cells.flat(600);
        for (int z = -30; z <= 30; z++) for (int x = 0; x <= 5; x++) Cells.overlay(c, x, z, 4, 0, Double.NaN);
        // a stream running east across the road in a 2 m deep bed: water at 599.5, bed 598
        for (int x = -20; x <= 25; x++) {
            for (int z = 0; z <= 1; z++) {
                Cells.ground(c, x, z, 598);
                Cells.water(c, x, z, 599.5, 3);
            }
        }
        Voxels v = new Voxels(c, -16, -24, 22, 24, -1420, -1380);
        List<String> bad = v.unstableWater(2);
        assertTrue(bad.isEmpty(), () -> "unstable water: " + bad);
        // the stream goes on under the road, the road lies above it
        for (int x = 0; x <= 5; x++) {
            assertEquals(B.SOURCE, v.at(x, -1401, 0));
            assertTrue(v.deck[v.col(x, 0)] > -1401, "deck above the water");
        }
    }

    // ------------------------------------------------------------------ rails

    private static void railBand(SwdCell c, int x, int z, int width) {
        for (int i = -width; i <= width; i++) Cells.overlay(c, x + i, z, OverlayPlan.RAIL_BED, i == 0 ? RAIL : 0, Double.NaN);
    }

    /** Every connection of every rail reaches a rail that connects back; adjacent rails are joined, never side by side. */
    private static List<String> railFaults(Voxels v) {
        List<String> bad = new ArrayList<>();
        for (int x = v.x0 + 1; x < v.x0 + v.nx - 1; x++) {
            for (int z = v.z0 + 1; z < v.z0 + v.nz - 1; z++) {
                for (int y = v.y0 + 1; y < v.y0 + v.ny - 1; y++) {
                    if (v.at(x, y, z) != B.RAIL) continue;
                    if (v.at(x, y - 1, z) != B.SOLID) bad.add(x + "," + y + "," + z + " not on a bed");
                    Rail s = v.shape(x, y, z);
                    List<int[]> ends = ends(s);
                    for (int[] d : ends) {
                        int[] p = target(v, x, y, z, s, d);
                        if (p == null) {
                            boolean edge = x + d[0] <= v.x0 || x + d[0] >= v.x0 + v.nx - 1 || z + d[1] <= v.z0 || z + d[1] >= v.z0 + v.nz - 1;
                            if (!edge) bad.add(x + "," + y + "," + z + " " + s + " open towards " + d[0] + "," + d[1]);
                        }
                    }
                    // any rail beside it (same level or one up/down) must be one of its connections
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (v.at(x + d[0], y + dy, z + d[1]) != B.RAIL) continue;
                            boolean joined = ends.stream().anyMatch(e -> e[0] == d[0] && e[1] == d[1]);
                            if (!joined) bad.add(x + "," + y + "," + z + " " + s + " beside a rail at " + d[0] + "," + dy + "," + d[1]);
                        }
                    }
                    // never in a trench: the bed beside the rail (not along it) is not higher than the rail's bed
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        if (ends.stream().anyMatch(e -> e[0] == d[0] && e[1] == d[1])) continue;
                        int c = v.col(x + d[0], z + d[1]);
                        if (v.railWay[c] && v.at(x + d[0], y, z + d[1]) == B.SOLID) bad.add(x + "," + y + "," + z + " in a trench");
                    }
                }
            }
        }
        return bad;
    }

    private static List<int[]> ends(Rail s) {
        List<int[]> e = new ArrayList<>();
        switch (s) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> {
                e.add(new int[] {0, -1});
                e.add(new int[] {0, 1});
            }
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> {
                e.add(new int[] {1, 0});
                e.add(new int[] {-1, 0});
            }
            case NORTH_EAST -> {
                e.add(new int[] {0, -1});
                e.add(new int[] {1, 0});
            }
            case NORTH_WEST -> {
                e.add(new int[] {0, -1});
                e.add(new int[] {-1, 0});
            }
            case SOUTH_EAST -> {
                e.add(new int[] {0, 1});
                e.add(new int[] {1, 0});
            }
            case SOUTH_WEST -> {
                e.add(new int[] {0, 1});
                e.add(new int[] {-1, 0});
            }
        }
        return e;
    }

    private static int[] dirUp(Rail s) {
        return switch (s) {
            case ASCENDING_NORTH -> new int[] {0, -1};
            case ASCENDING_SOUTH -> new int[] {0, 1};
            case ASCENDING_EAST -> new int[] {1, 0};
            case ASCENDING_WEST -> new int[] {-1, 0};
            default -> null;
        };
    }

    /** The rail that (x, y, z) connects to towards d, if it connects back, as vanilla's RailState would find it. */
    private static int[] target(Voxels v, int x, int y, int z, Rail s, int[] d) {
        int[] up = dirUp(s);
        int tx = x + d[0], tz = z + d[1];
        List<Integer> ys = new ArrayList<>();
        if (up != null && up[0] == d[0] && up[1] == d[1]) {
            ys.add(y + 1);
        } else {
            ys.add(y);
            ys.add(y - 1);
        }
        for (int ty : ys) {
            if (v.at(tx, ty, tz) != B.RAIL) continue;
            Rail o = v.shape(tx, ty, tz);
            int[] back = {-d[0], -d[1]};
            if (ends(o).stream().noneMatch(e -> e[0] == back[0] && e[1] == back[1])) continue;
            int[] oup = dirUp(o);
            boolean oUpBack = oup != null && oup[0] == back[0] && oup[1] == back[1];
            // the other rail is one lower only if it ascends towards us; at the same level it must not
            if (ty == y - 1 && !oUpBack) continue;
            if (ty == y && oUpBack) continue;
            return new int[] {tx, ty, tz};
        }
        return null;
    }

    @Test
    void railClimbsAGradeOnItsBedWithAscendingRails() {
        SwdCell c = Cells.flat(600);
        Voxels.terrain(c, (x, z) -> 600 - 0.15 * z + 0.4 * x);
        for (int z = -60; z <= 60; z++) railBand(c, 0, z, 2);
        Voxels v = new Voxels(c, -6, -40, 6, 40, -1440, -1380);
        List<String> bad = railFaults(v);
        assertTrue(bad.isEmpty(), () -> "rail faults: " + bad);
        int ascending = 0;
        for (int z = -39; z <= 39; z++) {
            int d = v.deck[v.col(0, z)];
            assertEquals(B.RAIL, v.at(0, d + 1, z), "rail at 0," + z);
            if (v.shape(0, d + 1, z).ascending()) ascending++;
        }
        assertTrue(ascending >= 9, "ascending " + ascending);
    }

    @Test
    void diagonalTracksCurveCleanlyAndTouchingTracksDoNotMerge() {
        SwdCell c = Cells.flat(600);
        // two staircase tracks running south-east, their centres 2 columns apart (touching), a third 4 apart
        for (int i = -40; i <= 40; i++) {
            for (int off : new int[] {0, 2, 6}) {
                Cells.overlay(c, i + off, i, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
                Cells.overlay(c, i + 1 + off, i, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
                Cells.overlay(c, i - 1 + off, i, OverlayPlan.RAIL_BED, 0, Double.NaN);
                Cells.overlay(c, i + 2 + off, i, OverlayPlan.RAIL_BED, 0, Double.NaN);
            }
        }
        // overlay order above may have cleared rail flags of a neighbouring track's bed: set the centres again
        for (int i = -40; i <= 40; i++) {
            for (int off : new int[] {0, 2, 6}) {
                Cells.overlay(c, i + off, i, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
                Cells.overlay(c, i + 1 + off, i, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
            }
        }
        Voxels v = new Voxels(c, -30, -30, 30, 30, -1410, -1390);
        List<String> bad = railFaults(v);
        assertTrue(bad.isEmpty(), () -> "rail faults: " + bad);
        // the separate track (offset 6) is a clean alternating staircase of curves
        int curves = 0;
        for (int i = -20; i <= 20; i++) {
            int x = i + 6, y = v.deck[v.col(x, i)] + 1;
            if (v.at(x, y, i) == B.RAIL && !v.shape(x, y, i).ascending() && v.shape(x, y, i) != Rail.NORTH_SOUTH
                    && v.shape(x, y, i) != Rail.EAST_WEST) curves++;
        }
        assertTrue(curves >= 40, "curves " + curves);
    }

    @Test
    void railBesideAStreamAndOverItStaysDry() {
        SwdCell c = Cells.flat(600);
        for (int z = -40; z <= 40; z++) railBand(c, 0, z, 2);
        for (int x = -20; x <= 20; x++) {
            Cells.ground(c, x, 3, 597);
            Cells.water(c, x, 3, 599.6, 3);
        }
        Voxels v = new Voxels(c, -12, -20, 12, 20, -1420, -1380);
        List<String> bad = new ArrayList<>(v.unstableWater(2));
        bad.addAll(railFaults(v));
        assertTrue(bad.isEmpty(), () -> "faults: " + bad);
    }

    // ------------------------------------------------------------------ tunnels

    /**
     * A rail tunnel running north-south at 600 m under a hill whose top is 615 m, with a thin stretch (cover under
     * 2 m) and two streams crossing above it, between open approaches at 600 m.
     */
    private static SwdCell tunnelHill() {
        SwdCell c = Cells.flat(600);
        Voxels.terrain(c, (x, z) -> {
            double a = Math.abs(z);
            if (a >= 50) return 600;
            if (a > 35) return 600 + (50 - a);
            if (z >= 10 && z <= 18) return 606; // a saddle: the ceiling (y -1393) comes within 2 m of it
            return 615;
        });
        for (int z = -70; z <= 70; z++) {
            boolean tunnel = Math.abs(z) <= 40;
            for (int i = -2; i <= 2; i++) {
                int flags = (i == 0 ? RAIL : 0) | (tunnel ? TUNNEL : 0);
                Cells.overlay(c, i, z, OverlayPlan.RAIL_BED, flags, tunnel ? 600 : Double.NaN);
            }
        }
        // a stream on the hill above the tunnel, and one in the saddle, lower than the tunnel's ceiling
        for (int x = -20; x <= 20; x++) {
            Cells.ground(c, x, 0, 613);
            Cells.water(c, x, 0, 614.5, 3);
            Cells.ground(c, x, 14, 604);
            Cells.water(c, x, 14, 605.5, 3);
        }
        return c;
    }

    @Test
    void tunnelIsClosedLinedAndDry() {
        Voxels v = new Voxels(tunnelHill(), -10, -60, 10, 60, -1430, -1370);
        List<String> bad = new ArrayList<>();
        for (int z = -40; z <= 40; z++) {
            for (int x = -2; x <= 2; x++) {
                int col = v.col(x, z);
                assertTrue(v.tunnelWay[col], "tunnel way " + x + "," + z);
                int floor = v.deck[col];
                // headroom: 6 blocks above the floor (5 when a rail lies on it)
                for (int y = floor + 1; y <= floor + 6; y++) {
                    B b = v.at(x, y, z);
                    if (b != B.AIR && b != B.RAIL && b != B.LANTERN) bad.add(x + "," + y + "," + z + " blocked: " + b);
                }
                // no sky: something solid above the air
                boolean roof = false;
                for (int y = floor + 7; y < v.y0 + v.ny; y++) roof |= v.at(x, y, z) == B.SOLID;
                if (!roof) bad.add(x + "," + z + " open to the sky");
                // no water beside any air block of the tunnel
                for (int y = floor + 1; y <= floor + 6; y++) {
                    for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}) {
                        if (Voxels.water(v.at(x + d[0], y + d[1], z + d[2]))) bad.add(x + "," + y + "," + z + " water beside");
                    }
                }
            }
            // walls left and right of the 5-wide profile
            int floor = v.deck[v.col(0, z)];
            for (int y = floor + 1; y <= floor + 6; y++) {
                if (v.at(-3, y, z) != B.SOLID || v.at(3, y, z) != B.SOLID) bad.add("wall missing at " + z + "," + y);
            }
        }
        // rail through the whole tunnel, connected
        bad.addAll(railFaults(v));
        bad.addAll(v.unstableWater(2));
        assertTrue(bad.isEmpty(), () -> "tunnel faults: " + bad);
        // the hill above stays: the stream on the hill still runs there
        assertEquals(B.SOURCE, v.at(0, -1386, 0));
    }

    @Test
    void tunnelPortalsOpenToTheApproach() {
        Voxels v = new Voxels(tunnelHill(), -10, -60, 10, 60, -1430, -1370);
        for (int z : new int[] {-41, 41}) {
            int deck = v.deck[v.col(0, z)];
            assertFalse(v.tunnelWay[v.col(0, z)]);
            assertEquals(B.RAIL, v.at(0, deck + 1, z));
            for (int y = deck + 2; y <= deck + 5; y++) assertEquals(B.AIR, v.at(0, y, z), "approach at " + z + "," + y);
        }
    }
}
