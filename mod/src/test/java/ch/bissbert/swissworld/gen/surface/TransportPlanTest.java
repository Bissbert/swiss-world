package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.gen.surface.OverlayPlan.Rail;
import ch.bissbert.swissworld.gen.surface.Voxels.B;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    /** Playtest 2026-10-20 00.10.30: on a cross slope the road's edges stood a half step off its middle. */
    @Test
    void roadOnACrossSlopeIsLevelAcross() {
        SwdCell c = slopedRoad();
        List<String> ragged = new ArrayList<>();
        for (int z = -40; z <= 40; z++) {
            if (Math.abs(z - 7) <= TransportPlan.ROAD_RADIUS + 1) continue; // the spike
            TransportPlan t = new TransportPlan(Cells.grid(c, 0, Math.floorDiv(z, 16) * 16, TransportPlan.MARGIN));
            java.util.Set<String> levels = new java.util.TreeSet<>();
            for (int x = 0; x <= 5; x++) levels.add(t.roadDeck(x, z) + (t.roadSlab(x, z) ? ".5" : ""));
            if (levels.size() > 1) ragged.add(z + ": " + levels);
        }
        assertTrue(ragged.isEmpty(), () -> "rows with more than one level: " + ragged);
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

    /** Playtest 2026-10-20 00.10.30: a road cut into a steep slope left a vertical dirt and stone wall beside it. */
    @Test
    void cutIntoASteepSlopeIsTerracedAndGrassed() {
        SwdCell c = Cells.flat(600);
        Voxels.terrain(c, (x, z) -> 600 + 1.6 * x);
        for (int z = -60; z <= 60; z++) {
            for (int x = 0; x <= 5; x++) Cells.overlay(c, x, z, 3, x == 0 || x == 5 ? ColumnGrid.EDGE : 0, Double.NaN);
        }
        Voxels v = new Voxels(c, -4, -20, 24, 20, -1440, -1340);
        List<String> walls = new ArrayList<>();
        int steps = 0;
        for (int z = -18; z <= 18; z++) {
            for (int x = 6; x <= 20; x++) {
                int a = topSolid(v, x - 1, z), b = topSolid(v, x, z);
                if (b - a > TransportPlan.CUT_SOIL) walls.add(x + "," + z + ": " + a + " -> " + b);
                // a cut column's step face stands in soil: every block of it is under the cut's top, none bare rock
                if (v.part(x, b, z) != TransportPlan.Part.TOP) continue;
                for (int y = a + 1; y < b; y++) {
                    steps++;
                    if (v.part(x, y, z) != TransportPlan.Part.SUBSOIL) walls.add(x + "," + y + "," + z + " face " + v.part(x, y, z));
                }
            }
        }
        assertTrue(steps > 0, "the slope is cut");
        assertTrue(walls.isEmpty(), () -> "bare or high steps above the road: " + walls);
    }

    /** Playtest 00.07 (28980,-1255,49): a road bridge a block over a slope showed as a long stone-brick wall. */
    @Test
    void lowBridgeOverDryGroundIsAnEmbankment() {
        SwdCell c = Cells.flat(600);
        for (int z = -30; z <= 30; z++) {
            for (int x = 0; x <= 3; x++) Cells.overlay(c, x, z, 3, ColumnGrid.BRIDGE, 602);
            for (int x = 10; x <= 13; x++) Cells.overlay(c, x, z, 3, ColumnGrid.BRIDGE, 608);
        }
        Voxels v = new Voxels(c, -4, -10, 16, 10, -1440, -1380);
        for (int z = -5; z <= 5; z++) {
            for (int x = 0; x <= 3; x++) {
                int deck = v.deck[v.col(x, z)], top = v.originalTop[v.col(x, z)];
                assertTrue(deck - 2 - top <= TransportPlan.LOW_BRIDGE, "low deck " + deck + " over " + top);
                for (int y = top + 1; y < deck; y++) {
                    assertEquals(TransportPlan.Part.SOIL, v.part(x, y, z), "low bridge " + x + "," + y + "," + z);
                }
                // no railing along an embankment: it read as a long retaining wall
                assertNotEquals(TransportPlan.Part.RAILING, v.part(x, deck + 1, z), "railing on low bridge " + x + "," + z);
            }
            // a high bridge keeps its beam, with air or a pier under it
            int deck = v.deck[v.col(11, z)];
            assertEquals(TransportPlan.Part.BEAM, v.part(11, deck - 1, z));
            assertEquals(TransportPlan.Part.RAILING, v.part(10, deck + 1, z), "railing on the high bridge at " + z);
            assertTrue(v.at(11, deck - 2, z) == B.AIR || v.part(11, deck - 2, z) == TransportPlan.Part.BEAM, "air or pier under the beam at " + z);
        }
    }

    /**
     * Playtest 00.07 again (28980,-1255,49): a road bridge along a slope with nothing under it stood on a beam and a
     * railing up to ten blocks over the downhill half. It is grassed fill with a slope beside it; one over a stream
     * keeps its beam.
     */
    @Test
    void sideHillBridgeIsFill() {
        SwdCell c = Cells.flat(600);
        Voxels.terrain(c, (x, z) -> 600 - 1.2 * x);
        for (int z = -40; z <= 40; z++) {
            for (int x = 0; x <= 7; x++) Cells.overlay(c, x, z, 3, ColumnGrid.BRIDGE, 600);
        }
        // the same bridge further south crosses a stream on its downhill side
        for (int z = 20; z <= 40; z++) Cells.water(c, 6, z, 600 - 1.2 * 6 + 0.5, 2);
        Voxels v = new Voxels(c, -6, -12, 24, 30, -1450, -1380);
        int deepest = 0;
        for (int z = -6; z <= 6; z++) {
            int deck = v.deck[v.col(0, z)];
            for (int x = 0; x <= 7; x++) {
                int top = v.originalTop[v.col(x, z)];
                deepest = Math.max(deepest, deck - 2 - top);
                assertNotEquals(TransportPlan.Part.BEAM, v.part(x, deck - 1, z), "beam on the side-hill bridge at " + x + "," + z);
                assertNotEquals(TransportPlan.Part.RAILING, v.part(x, deck + 1, z), "railing on the side-hill bridge at " + x + "," + z);
                for (int y = top + 1; y < deck; y++) {
                    assertEquals(TransportPlan.Part.SOIL, v.part(x, y, z), "fill at " + x + "," + y + "," + z);
                }
            }
            // downhill of the edge the ground slopes away from the deck one block per column: no soil wall
            for (int x = 8; x <= 10; x++) {
                assertTrue(topSolid(v, x, z) >= deck - (x - 7), "slope at " + x + "," + z + ": " + topSolid(v, x, z) + " vs deck " + deck);
            }
            assertEquals(TransportPlan.Part.TOP, v.part(9, topSolid(v, 9, z), z), "grassed slope at 9," + z);
        }
        assertTrue(deepest > TransportPlan.LOW_BRIDGE + 4, "the downhill edge stands well above the ground: " + deepest);
        for (int z = 24; z <= 30; z++) {
            int deck = v.deck[v.col(0, z)];
            assertEquals(TransportPlan.Part.BEAM, v.part(6, deck - 1, z), "beam over the stream at 6," + z);
        }
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

    /** Playtest 17.12.05: a sloped T junction of class-1 roads had uneven slab steps and kerb walls beside it. */
    @Test
    void sloppedJunctionHasHalfStepsAndNoKerbWalls() {
        SwdCell c = Cells.flat(600);
        Voxels.terrain(c, (x, z) -> 600 - 0.1 * z + 0.15 * x);
        for (int z = -40; z <= 40; z++) {
            for (int x = 0; x <= 5; x++) Cells.overlay(c, x, z, 1, x == 0 || x == 5 ? ColumnGrid.EDGE : 0, Double.NaN);
        }
        for (int x = 6; x <= 40; x++) {
            for (int z = 0; z <= 5; z++) Cells.overlay(c, x, z, 1, z == 0 || z == 5 ? ColumnGrid.EDGE : 0, Double.NaN);
        }
        Voxels v = new Voxels(c, -8, -30, 34, 30, -1440, -1370);
        List<String> bad = new ArrayList<>();
        for (int x = -8; x <= 34; x++) {
            for (int z = -30; z <= 30; z++) {
                int col = v.col(x, z);
                if (!v.wayCol[col]) continue;
                for (int y = (int) Math.ceil(v.walk(x, z)); y < -1370; y++) {
                    if (v.at(x, y, z) != B.AIR) bad.add("on the road at " + x + "," + y + "," + z + ": " + v.at(x, y, z));
                }
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], nz = z + d[1];
                    if (!v.in(nx, v.y0, nz)) continue;
                    if (v.wayCol[v.col(nx, nz)]) {
                        if (Math.abs(v.walk(x, z) - v.walk(nx, nz)) > 0.5) bad.add("step " + x + "," + z + " -> " + nx + "," + nz);
                    } else if (topSolid(v, nx, nz) >= v.walk(x, z) + 0.5) {
                        bad.add("kerb wall beside " + x + "," + z + " at " + nx + "," + nz);
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), () -> "junction faults: " + bad);
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
                        if (p == null && switchAt(v, x, y, z, s, d)) continue;
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
                            // a switch: the through track's rail on its outside points at it
                            joined |= railsBeside(v, x, y, z) == 3 && !ends(v.shape(x + d[0], y + dy, z + d[1])).isEmpty()
                                    && ends(v.shape(x + d[0], y + dy, z + d[1])).stream().anyMatch(e -> e[0] == -d[0] && e[1] == -d[1]);
                            if (!joined) bad.add(x + "," + y + "," + z + " " + s + " beside a rail at " + d[0] + "," + dy + "," + d[1]);
                        }
                    }
                    // never in a trench: the bed beside the rail (not along it) is not higher than the rail's bed
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        if (ends.stream().anyMatch(e -> e[0] == d[0] && e[1] == d[1])) continue;
                        int c = v.col(x + d[0], z + d[1]);
                        if (v.railWay[c] && v.at(x + d[0], y, z + d[1]) == B.SOLID
                                && v.part(x + d[0], y, z + d[1]) != TransportPlan.Part.RAILING) {
                            bad.add(x + "," + y + "," + z + " in a trench");
                        }
                    }
                }
            }
        }
        return bad;
    }

    /** Rails on the four sides of (x, y, z), one level up or down included. */
    private static int railsBeside(Voxels v, int x, int y, int z) {
        int n = 0;
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int dy = -1; dy <= 1; dy++) if (v.at(x + d[0], y + dy, z + d[1]) == B.RAIL) n++;
        }
        return n;
    }

    /**
     * The open end towards d meets the side of a switch (vanilla's T junction): a rail with three rails beside it, at
     * the same level or, if this rail ascends towards it, one up. The through track keeps its shape; the branch ends.
     */
    private static boolean switchAt(Voxels v, int x, int y, int z, Rail s, int[] d) {
        int[] up = dirUp(s);
        int ty = up != null && up[0] == d[0] && up[1] == d[1] ? y + 1 : y;
        int tx = x + d[0], tz = z + d[1];
        if (v.at(tx, ty, tz) != B.RAIL) return false;
        return railsBeside(v, tx, ty, tz) == 3;
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
    void diagonalRailBridgeKeepsEveryRailOfItsStaircase() {
        SwdCell c = Cells.flat(600);
        // a narrow rail bridge running south-east, 6 m over the ground: per row the two centre columns of the
        // staircase and one deck column on the east; the west of each row is open, so the west centre column is an
        // edge of the deck (Brünig line at Interlaken, E 2633932 N 1171472)
        for (int i = -40; i <= 40; i++) {
            Cells.overlay(c, i, i, OverlayPlan.RAIL_BED, RAIL | ColumnGrid.BRIDGE, 606);
            Cells.overlay(c, i + 1, i, OverlayPlan.RAIL_BED, RAIL | ColumnGrid.BRIDGE, 606);
            Cells.overlay(c, i + 2, i, OverlayPlan.RAIL_BED, ColumnGrid.BRIDGE, 606);
        }
        Voxels v = new Voxels(c, -30, -30, 30, 30, -1410, -1385);
        List<String> bad = railFaults(v);
        assertTrue(bad.isEmpty(), () -> "rail faults: " + bad);
        int rails = 0;
        for (int i = -20; i <= 20; i++) {
            for (int x = i; x <= i + 1; x++) if (v.at(x, v.deck[v.col(x, i)] + 1, i) == B.RAIL) rails++;
        }
        assertEquals(82, rails, "rails on the staircase");
    }

    /** The diagonal track east of Interlaken Ost ended where it met a rail bridge 5 m up (713,-1433,-545). */
    @Test
    void diagonalTrackClimbsOntoABridgeDeck() {
        SwdCell c = Cells.flat(600);
        for (int i = -50; i <= 50; i++) {
            boolean bridge = i >= 0;
            int f = bridge ? ColumnGrid.BRIDGE : 0;
            double deck = bridge ? 605 : Double.NaN;
            for (int j = -1; j <= 2; j++) Cells.overlay(c, i + j, i, OverlayPlan.RAIL_BED, f, deck);
            Cells.overlay(c, i, i, OverlayPlan.RAIL_BED, RAIL | f, deck);
            Cells.overlay(c, i + 1, i, OverlayPlan.RAIL_BED, RAIL | f, deck);
        }
        Voxels v = new Voxels(c, -30, -30, 30, 30, -1420, -1385);
        List<String> bad = railFaults(v);
        assertTrue(bad.isEmpty(), () -> "rail faults: " + bad);
        // unbroken: two rails a row (a flipped corner moves one into the row beside it)
        int rails = 0;
        for (int i = -28; i <= 28; i++) {
            for (int x = i - 1; x <= i + 2; x++) {
                for (int y = v.y0; y < v.y0 + v.ny; y++) if (v.at(x, y, i) == B.RAIL) rails++;
            }
        }
        assertTrue(Math.abs(rails - 2 * 57) <= 1, "rails on the staircase: " + rails);
        // and it climbs onto the deck
        int top = Integer.MIN_VALUE;
        for (int y = v.y0; y < v.y0 + v.ny; y++) if (v.at(28, y, 28) == B.RAIL || v.at(29, y, 28) == B.RAIL) top = y;
        assertEquals(v.deck[v.col(28, 28)] + 1, top, "rail on the deck");
    }

    @Test
    void diagonalTrackClimbsAGradeWithoutSteps() {
        for (double grade : new double[] {0.02, 0.04, 0.08}) {
            SwdCell c = Cells.flat(600);
            Voxels.terrain(c, (x, z) -> 600 - grade * (x + z));
            // a staircase track running south-east (down the grade), its bed three columns either side
            for (int i = -50; i <= 50; i++) {
                for (int j = -3; j <= 4; j++) Cells.overlay(c, i + j, i, OverlayPlan.RAIL_BED, 0, Double.NaN);
            }
            for (int i = -50; i <= 50; i++) {
                Cells.overlay(c, i, i, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
                Cells.overlay(c, i + 1, i, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
            }
            Voxels v = new Voxels(c, -30, -30, 30, 30, -1420, -1385);
            List<String> bad = railFaults(v);
            assertTrue(bad.isEmpty(), () -> "grade " + grade + " rail faults: " + bad);
            // the track is there all the way and climbs: rails at more than one level, none missing
            int rails = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int x = -29; x <= 29; x++) {
                for (int z = -29; z <= 29; z++) {
                    for (int y = v.y0; y < v.y0 + v.ny; y++) {
                        if (v.at(x, y, z) != B.RAIL) continue;
                        rails++;
                        lo = Math.min(lo, y);
                        hi = Math.max(hi, y);
                    }
                }
            }
            assertTrue(rails >= 2 * 57, "grade " + grade + " rails " + rails);
            assertTrue(hi - lo >= (int) (grade * 100) - 1, "grade " + grade + " climbs " + (hi - lo));
        }
    }

    /** A branch running in on a shallow staircase (three east per south) from (x, z) until it reaches row {@code toZ}. */
    private static void branch(SwdCell c, int x, int z, int toZ, int touching) {
        int cx = x, cz = z;
        while (cz < toZ) {
            for (int i = 0; i < 3; i++) railBand(c, cx++, cz, 0);
            railBand(c, cx - 1, ++cz, 0);
        }
        for (int i = 0; i < touching; i++) railBand(c, cx++, cz, 0);
    }

    @Test
    void tracksMergingSideBySideJoinAtASwitchWithoutDeadEnds() {
        SwdCell c = Cells.flat(600);
        for (int x = -50; x <= 50; x++) {
            for (int z = -24; z <= 24; z++) Cells.overlay(c, x, z, OverlayPlan.RAIL_BED, 0, Double.NaN);
        }
        // the main tracks: rows -12 and 12, east-west
        for (int x = -50; x <= 50; x++) {
            Cells.overlay(c, x, -12, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
            Cells.overlay(c, x, 12, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
        }
        // a branch that runs in beside the northern track, touching it for 8 columns before it ends
        branch(c, -50, -22, -13, 8);
        // a branch whose centre line stops two rows short of the southern track
        branch(c, -50, 2, 9, 0);
        Voxels v = new Voxels(c, -46, -24, 40, 24, -1410, -1390);
        List<String> bad = railFaults(v);
        assertTrue(bad.isEmpty(), () -> "rail faults: " + bad);
        int switches = 0;
        for (int x = -45; x <= 39; x++) {
            for (int z = -23; z <= 23; z++) {
                int y = v.deck[v.col(x, z)] + 1;
                if (v.at(x, y, z) == B.RAIL && railsBeside(v, x, y, z) == 3) switches++;
            }
        }
        assertEquals(2, switches, "one switch per branch");
        // the main tracks run through, the switches included: a cart passes every branch in both directions
        for (int x = -45; x <= 39; x++) {
            assertEquals(B.RAIL, v.at(x, v.deck[v.col(x, -12)] + 1, -12), "northern track at " + x);
            assertEquals(B.RAIL, v.at(x, v.deck[v.col(x, 12)] + 1, 12), "southern track at " + x);
            assertEquals(Rail.EAST_WEST, v.shape(x, v.deck[v.col(x, -12)] + 1, -12), "northern track at " + x);
            assertEquals(Rail.EAST_WEST, v.shape(x, v.deck[v.col(x, 12)] + 1, 12), "southern track at " + x);
        }
    }

    /**
     * Two tracks crossing (2339,-1989, where a crossover cut the middle of three parallel tracks): the crossing node
     * was dropped, which broke both lines. The straighter pair of arms carries on through it; a stub gives way.
     */
    @Test
    void crossingTracksKeepOneLineThrough() {
        for (boolean stub : new boolean[] {false, true}) {
            SwdCell c = Cells.flat(600);
            int north = stub ? -5 : -40; // the north arm: a stub of five rails, or a full track
            for (int x = -40; x <= 40; x++) {
                for (int z = -2; z <= 2; z++) Cells.overlay(c, x, z, OverlayPlan.RAIL_BED, 0, Double.NaN);
            }
            for (int z = north; z <= 40; z++) {
                for (int x = -2; x <= 2; x++) Cells.overlay(c, x, z, OverlayPlan.RAIL_BED, 0, Double.NaN);
            }
            for (int x = -40; x <= 40; x++) Cells.overlay(c, x, 0, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
            for (int z = north; z <= 40; z++) Cells.overlay(c, 0, z, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
            TransportPlan t = new TransportPlan(Cells.grid(c, 0, 0, TransportPlan.MARGIN));
            assertTrue(t.rail(0, 0), "a rail on the crossing");
            Rail through = stub ? Rail.EAST_WEST : Rail.NORTH_SOUTH;
            assertEquals(through, t.railShape(0, 0), "stub " + stub);
            for (int i = -20; i <= 20; i++) {
                int x = stub ? i : 0, z = stub ? 0 : i;
                assertTrue(t.rail(x, z), "through line at " + x + "," + z);
                assertEquals(through, t.railShape(x, z), "through line at " + x + "," + z);
            }
        }
    }

    /**
     * The Brünig line at its passing loops (2339,-1987, 23339,-4986): the main line bends away where a loop track runs
     * on straight, and the switch kept the straighter pair, the loop's, so the main line ended beside it. With the main
     * line tagged (OFLG bit5) the switch keeps it; untagged (older cells) the straighter pair still wins.
     */
    @Test
    void switchKeepsTheTaggedMainLine() {
        for (boolean tagged : new boolean[] {true, false}) {
            int main = RAIL | (tagged ? ColumnGrid.MAIN : 0);
            SwdCell c = Cells.flat(600);
            for (int x = -40; x <= 40; x++) {
                for (int z = -3; z <= 40; z++) Cells.overlay(c, x, z, OverlayPlan.RAIL_BED, 0, Double.NaN);
            }
            // the main line: from the west along row 0, then south and a diagonal staircase to the south-east
            for (int x = -40; x <= 0; x++) Cells.overlay(c, x, 0, OverlayPlan.RAIL_BED, main, Double.NaN);
            Cells.overlay(c, 0, 1, OverlayPlan.RAIL_BED, main, Double.NaN);
            for (int i = 0; i < 30; i++) {
                Cells.overlay(c, i, i + 2, OverlayPlan.RAIL_BED, main, Double.NaN);
                Cells.overlay(c, i + 1, i + 2, OverlayPlan.RAIL_BED, main, Double.NaN);
            }
            // the loop track runs on straight east
            for (int x = 1; x <= 40; x++) Cells.overlay(c, x, 0, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
            TransportPlan t = new TransportPlan(Cells.grid(c, 0, 0, TransportPlan.MARGIN));
            assertTrue(t.rail(0, 0) && t.rail(1, 0) && t.rail(0, 1), "switch and its three arms");
            assertEquals(tagged ? Rail.SOUTH_WEST : Rail.EAST_WEST, t.railShape(0, 0), "tagged " + tagged);
        }
    }

    /**
     * Meiringen (23789,-4868) and Interlaken Ost (172,-561): two main lines meet, so all three arms of the switch are
     * tagged (bit5) and the straighter pair won; the line from Interlaken ended beside the one from Luzern. With the
     * trunk tagged (bit6) the switch keeps it; without (older cells) the straighter pair still wins.
     */
    @Test
    void switchKeepsTheTrunkWhereMainLinesMeet() {
        for (boolean trunk : new boolean[] {true, false}) {
            int bent = RAIL | ColumnGrid.MAIN | (trunk ? ColumnGrid.TRUNK : 0);
            SwdCell c = Cells.flat(600);
            for (int x = -40; x <= 40; x++) {
                for (int z = -3; z <= 40; z++) Cells.overlay(c, x, z, OverlayPlan.RAIL_BED, 0, Double.NaN);
            }
            for (int x = -40; x <= 0; x++) Cells.overlay(c, x, 0, OverlayPlan.RAIL_BED, bent, Double.NaN);
            Cells.overlay(c, 0, 1, OverlayPlan.RAIL_BED, bent, Double.NaN);
            for (int i = 0; i < 30; i++) {
                Cells.overlay(c, i, i + 2, OverlayPlan.RAIL_BED, bent, Double.NaN);
                Cells.overlay(c, i + 1, i + 2, OverlayPlan.RAIL_BED, bent, Double.NaN);
            }
            // the other main line runs on straight east
            for (int x = 1; x <= 40; x++) Cells.overlay(c, x, 0, OverlayPlan.RAIL_BED, RAIL | ColumnGrid.MAIN, Double.NaN);
            TransportPlan t = new TransportPlan(Cells.grid(c, 0, 0, TransportPlan.MARGIN));
            assertTrue(t.rail(0, 0) && t.rail(1, 0) && t.rail(0, 1), "switch and its three arms");
            assertEquals(trunk ? Rail.SOUTH_WEST : Rail.EAST_WEST, t.railShape(0, 0), "trunk " + trunk);
        }
    }

    /**
     * Meiringen (flags as in cell 2656_1175, x 23764.., z -4880..): the line from Interlaken (trunk, T) and the one
     * from Luzern (main line, M) run a column or two apart and merge. Their rails touch in squares of four rails, a
     * switch at two opposite corners (23789,-4868); each switch kept its straight pair, the two went round a square
     * on different sides, and the line from Interlaken ended there. Both switches of a square now drop the same side,
     * and where three arms run on the trunk the switch keeps two of them.
     */
    @Test
    void trunkRunsThroughWhereTwoMainLinesMerge() {
        String[] rows = {
            ".                                         ",
            "R...                                      ",
            "MMMRR...                                  ",
            "..MMMMRR....                              ",
            "  ...MMMMRRR...                           ",
            "...  ...MMMMRRRR...                       ",
            "TTT...  ...MMM.RRRRR...                   ",
            "..TTTTT... ..MMMM..RRRR....               ",
            "  ....TTTT......MMMM..RRRRR...            ",
            ".     ...TTTTT.....MMM....RRRRR...        ",
            "RR...     ...TTTTT...MMMM.....RRRRR...    ",
            ".RRRRR...     ...TTTTT..MMM.......RRRR....",
            " ....RRRR....    ....TTTT.MMMM... ...RRRRR",
            "     ...RRRRR....    ...TTTTTMMMM...  ...R",
            "....     ...RRRRR...     ...TTTTTMMM...  .",
            "RRRR...     ....RRRRR...    ....TTTTMMM...",
            "...RRRRR...     ....RRRRR...    ...TTTTTMM",
            "   ....RRRRR...     ...RRRRR....    ...TTT",
            "..     ....RRRR....    ....RRRRR...     ..",
            "RR...      ...RRRRR...     ...RRRRRR...   ",
            ".RRRRR...      ...RRRRR...    ...RRRRRRR..",
        };
        SwdCell c = Cells.flat(600);
        for (int j = 0; j < rows.length; j++) {
            for (int i = 0; i < rows[j].length(); i++) {
                char ch = rows[j].charAt(i);
                int main = RAIL | ColumnGrid.MAIN;
                int flags = ch == 'T' ? main | ColumnGrid.TRUNK : ch == 'M' ? main : ch == 'R' ? RAIL : 0;
                if (ch != ' ') Cells.overlay(c, i - 25, j - 12, OverlayPlan.RAIL_BED, flags, Double.NaN);
            }
        }
        TransportPlan t = new TransportPlan(Cells.grid(c, 0, 0, TransportPlan.MARGIN));
        // along the rails from the trunk (a rail of it at 23768..23779, -4872) towards the station
        int[] end = alongRails(t, -21, -4, 12, (x, z) -> x >= 11);
        assertTrue(end[0] >= 11, "line ends at " + (end[0] + 23789) + "," + (end[1] - 4868));
    }

    /**
     * Follows the rails from the first rail at x0..x0+n-1 in row z0, both ways, until a rail where {@code goal} holds
     * or the line ends (a rail whose next one does not join back); the rail it stopped at.
     */
    private static int[] alongRails(TransportPlan t, int x0, int z0, int n, java.util.function.BiPredicate<Integer, Integer> goal) {
        int sx = x0;
        while (!t.rail(sx, z0) && sx < x0 + n) sx++;
        assertTrue(t.rail(sx, z0), "no rail at the start");
        int[] last = null;
        for (int[] first : ends(t.railShape(sx, z0))) {
            int x = sx, z = z0, px = sx + first[0], pz = z0 + first[1];
            for (int steps = 0; steps < 400 && !goal.test(x, z); steps++) {
                int[] next = null;
                for (int[] e : ends(t.railShape(x, z))) if (x + e[0] != px || z + e[1] != pz) next = new int[] {x + e[0], z + e[1]};
                if (next == null || !t.rail(next[0], next[1])) break;
                boolean back = false;
                for (int[] e : ends(t.railShape(next[0], next[1]))) back |= next[0] + e[0] == x && next[1] + e[1] == z;
                if (!back) break;
                px = x;
                pz = z;
                x = next[0];
                z = next[1];
            }
            if (goal.test(x, z)) return new int[] {x, z};
            last = new int[] {x, z};
        }
        return last;
    }

    /**
     * Interlaken Ost (flags as in cell 2633_1171, x 150.., z -572..; platforms left out): the Brünig line (trunk, T)
     * leaves the station north-east, the BOB (main line, M) east. Where they part the rails form a crossing at
     * 171,-560 whose fourth arm is a one-rail stub of the BOB; the stub made the switch give up on the main lines and
     * keep the straight pair, the BOB's, and the Brünig line ended at 172,-560. The trunk pair now wins past the stub.
     */
    @Test
    void trunkLeavesTheYardPastAMainLineStub() {
        String[] rows = {
            "RRRRR......       ......RRRRRRR.....          ",
            ".....       ......RRRRRRR.....                ",
            "       .....RRRRRRR.....                   ...",
            " .....RRRRRRR......                      ..TTT",
            "RRRRRRR......                         ..TTTTR.",
            "R......                            ...TTTR... ",
            ".                               ...TTTT..     ",
            "                             ...TTTT..        ",
            "                          ...TTTT...    ......",
            "                       ...TTTT... ......MMMMMM",
            "                    ...TTTT.......MMMMMMM.....",
            "                 ...TTTT....MMMMMMM.....      ",
            "             ....TTTT.MMMMMMM......       ....",
            "         ....TTTTTMMMMM......        .....RRRR",
            "     ....TTTTTMMM......        .....RRRRRRR...",
            "....TTTTTTMM.....        ......RRRRRR......   ",
            "TTTTTM.....        ......RRRRRRR.....         ",
            "......        .....RRRRRRR.....               ",
            "        .....RRRRRRR.....                     ",
            "  ......RRRRRR......                          ",
            "..RRRRRRR.....                                ",
            "RRR.....                                      ",
            "..                                        ...RRR"
        };
        SwdCell c = Cells.flat(600);
        for (int j = 0; j < rows.length; j++) {
            for (int i = 0; i < rows[j].length(); i++) {
                char ch = rows[j].charAt(i);
                int main = RAIL | ColumnGrid.MAIN;
                int flags = ch == 'T' ? main | ColumnGrid.TRUNK : ch == 'M' ? main : ch == 'R' ? RAIL : 0;
                if (ch != ' ') Cells.overlay(c, i - 21, j - 12, OverlayPlan.RAIL_BED, flags, Double.NaN);
            }
        }
        TransportPlan t = new TransportPlan(Cells.grid(c, 0, 0, TransportPlan.MARGIN));
        // along the rails from the station (a rail of the trunk at 150..159, -557) to the north-east
        int[] end = alongRails(t, -21, 3, 12, (x, z) -> z <= -8);
        assertTrue(end[1] <= -8 && end[0] > 10, "line ends at " + (end[0] + 171) + "," + (end[1] - 560));
    }

    /**
     * Meiringen (23788,-4868): two main lines run a column apart before they merge; the trunk keeps its centres and
     * the other main line gives way.
     */
    @Test
    void trunkKeepsItsCentresBesideAnotherMainLine() {
        SwdCell c = Cells.flat(600);
        for (int x = -40; x <= 40; x++) {
            for (int z = -3; z <= 20; z++) Cells.overlay(c, x, z, OverlayPlan.RAIL_BED, 0, Double.NaN);
        }
        int main = RAIL | ColumnGrid.MAIN;
        for (int x = -40; x <= 40; x++) Cells.overlay(c, x, 0, OverlayPlan.RAIL_BED, main | ColumnGrid.TRUNK, Double.NaN);
        for (int i = 0; i < 12; i++) {
            Cells.overlay(c, -12 - i, 1 + i, OverlayPlan.RAIL_BED, main, Double.NaN);
            Cells.overlay(c, 12 + i, 1 + i, OverlayPlan.RAIL_BED, main, Double.NaN);
        }
        for (int x = -12; x <= 12; x++) Cells.overlay(c, x, 1, OverlayPlan.RAIL_BED, main, Double.NaN);
        TransportPlan t = new TransportPlan(Cells.grid(c, 0, 0, TransportPlan.MARGIN));
        for (int x = -8; x <= 24; x++) assertTrue(t.rail(x, 0), "trunk at " + x);
    }

    /**
     * The Brünig line at 2339,-1987: the main line runs a column beside a siding, the two thinned as one band and the
     * main line's centres were dropped there, so its rails ran over onto the siding. The tagged main line keeps its
     * centres; the siding gives way.
     */
    @Test
    void mainLineKeepsItsCentresBesideATrack() {
        SwdCell c = Cells.flat(600);
        for (int x = -40; x <= 40; x++) {
            for (int z = -3; z <= 20; z++) Cells.overlay(c, x, z, OverlayPlan.RAIL_BED, 0, Double.NaN);
        }
        for (int x = -40; x <= 40; x++) Cells.overlay(c, x, 0, OverlayPlan.RAIL_BED, RAIL | ColumnGrid.MAIN, Double.NaN);
        // a siding comes up from the south-west, runs along the main line touching it, and leaves to the south-east
        for (int i = 0; i < 12; i++) {
            Cells.overlay(c, -12 - i, 1 + i, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
            Cells.overlay(c, 12 + i, 1 + i, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
        }
        for (int x = -12; x <= 12; x++) Cells.overlay(c, x, 1, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
        TransportPlan t = new TransportPlan(Cells.grid(c, 0, 0, TransportPlan.MARGIN));
        for (int x = -8; x <= 24; x++) assertTrue(t.rail(x, 0), "main line at " + x);
    }

    /**
     * The Brünig line at 2339,-1989 (flags as in cell 2635_1172): the main line runs two columns beside a siding and
     * a crossover between them. Its rails there are set off its centres onto untagged columns, and the switch counted
     * that arm as not on the main line and took the crossover; the line ended at 2340,-1988.
     */
    @Test
    void switchCountsMainLineRailsSetOffItsCentres() {
        String[] rows = {
            "                        ..RR.  ",
            "                       .RRR.  .",
            "                      .RRR.  .M",
            "                    ..RRR. ..MM",
            "                   .RRR.. .MMM.",
            "                  .RRR.. .MM.. ",
            "                ..RRR....MM.  .",
            "               .RRRR. .MMM.  .R",
            "              .RRRR. .MM..  .RR",
            "            ..RRRR...MM.  ..RR.",
            "           .RRRRR..MMM.  .RRR. ",
            "          .RR.RR..MM..  .RR..  ",
            "        ..RR.RR..MM.  ..RR.    ",
            "       .RRR.RR.MMM.  .RRR.     ",
            "      .RR..RR.MM..  .RR..      ",
            "    ..RR..RR.MM.  ..RR.        ",
            "   .RRR..RRMMM.  .RRR.         ",
            "  .RR...RRMM..  .RR..          ",
            "..RR. .RRMM.  ..RR.            ",
            "RRR. .RRMM.  .RRR.             ",
            "R....RMMM.  .RR..              ",
            ". .RRMM.. ..RR.                ",
            " .RRMM.  .RRR.                 ",
            ".RMMM.  .RR..                  ",
            "RMM..  .RR.                    ",
            "MM.  .RRR.                     ",
            "M.  .RR..                      ",
        };
        SwdCell c = Cells.flat(600);
        for (int j = 0; j < rows.length; j++) {
            for (int i = 0; i < rows[j].length(); i++) {
                char ch = rows[j].charAt(i);
                int flags = ch == 'M' ? RAIL | ColumnGrid.MAIN : ch == 'R' ? RAIL : 0;
                if (ch != ' ') Cells.overlay(c, i - 14, j - 13, OverlayPlan.RAIL_BED, flags, Double.NaN);
            }
        }
        TransportPlan t = new TransportPlan(Cells.grid(c, 0, 0, TransportPlan.MARGIN));
        assertTrue(t.rail(0, 0) && t.rail(0, 1) && t.rail(1, 0), "switch with its arms south and east");
        assertEquals(Rail.SOUTH_EAST, t.railShape(0, 0));
    }

    @Test
    void shortTrackPiecesWhereTracksMergeAreDropped() {
        SwdCell c = Cells.flat(600);
        for (int x = -20; x <= 20; x++) Cells.overlay(c, x, 0, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
        // a three-node piece left over beside the track, and a four-node one
        for (int x = 0; x < 3; x++) Cells.overlay(c, x, 4, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
        for (int x = 0; x < 4; x++) Cells.overlay(c, x, 8, OverlayPlan.RAIL_BED, RAIL, Double.NaN);
        TransportPlan t = new TransportPlan(Cells.grid(c, 0, 0, TransportPlan.MARGIN));
        for (int x = -10; x <= 10; x++) assertTrue(t.rail(x, 0), "main track at " + x);
        for (int x = 0; x < 3; x++) assertFalse(t.rail(x, 4), "short piece at " + x);
        for (int x = 0; x < 4; x++) assertTrue(t.rail(x, 8), "four-node piece at " + x);
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
                // headroom: 6 blocks above the floor; 3 under the saddle stream, whose bed is lower than a full bore
                int head = z >= 12 && z <= 16 ? 3 : 6;
                for (int y = floor + 1; y <= floor + head; y++) {
                    B b = v.at(x, y, z);
                    if (b != B.AIR && b != B.RAIL && b != B.LANTERN) bad.add(x + "," + y + "," + z + " blocked: " + b);
                }
                // no sky: something solid above the air
                boolean roof = false;
                for (int y = floor + head + 1; y < v.y0 + v.ny; y++) roof |= v.at(x, y, z) == B.SOLID;
                if (!roof) bad.add(x + "," + z + " open to the sky");
                // no water beside any air block of the tunnel
                for (int y = floor + 1; y <= floor + head; y++) {
                    for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}) {
                        if (Voxels.water(v.at(x + d[0], y + d[1], z + d[2]))) bad.add(x + "," + y + "," + z + " water beside");
                    }
                }
            }
            // walls left and right of the 5-wide profile
            int floor = v.deck[v.col(0, z)];
            for (int y = floor + 1; y <= floor + (z >= 12 && z <= 16 ? 3 : 6); y++) {
                if (v.at(-3, y, z) != B.SOLID || v.at(3, y, z) != B.SOLID) bad.add("wall missing at " + z + "," + y);
            }
        }
        // rail through the whole tunnel, connected
        bad.addAll(railFaults(v));
        bad.addAll(v.unstableWater(2));
        assertTrue(bad.isEmpty(), () -> "tunnel faults: " + bad);
        // the hill above stays: the stream on the hill still runs there
        assertEquals(B.SOURCE, v.at(0, -1386, 0));
        // the saddle stream runs on over the tunnel: its water is not replaced by the lining
        for (int x = -6; x <= 6; x++) {
            int bed = v.originalTop[v.col(x, 14)];
            for (int y = bed + 1; y <= -1395; y++) assertTrue(Voxels.water(v.at(x, y, 14)), "saddle stream at " + x + "," + y);
            assertEquals(B.SOLID, v.at(x, bed, 14), "bed under the saddle stream at " + x);
            // the bed is bed material, not the tunnel's lining (playtest: stone bricks in the stream bed); right over
            // the bore's air it is one that cannot fall
            TransportPlan.Part p = v.part(x, bed, 14);
            assertNotEquals(TransportPlan.Part.LINING, p, "lining in the stream bed at " + x);
            if (v.at(x, bed - 1, 14) == B.AIR) assertEquals(TransportPlan.Part.BED, p, "bed over the bore at " + x);
        }
    }

    /**
     * Playtest 19.05.33: a footpath without the tunnel flag runs inside the tunnel's strip (deep under the hill, and
     * near a portal where the hill is low) and branches off beside it. The bore wins: its interior stays clear, the
     * path runs on above it where it can.
     */
    @Test
    void waysCrossingATunnelLeaveItsInteriorClear() {
        SwdCell c = tunnelHill();
        for (int z = -25; z <= -10; z++) {
            // its own levels (OELV) put it half way up the bore
            for (int x = -2; x <= -1; x++) Cells.overlay(c, x, z, 6, 0, 603);
        }
        for (int z = 34; z <= 44; z++) {
            for (int x = 1; x <= 2; x++) Cells.overlay(c, x, z, 6, 0, Double.NaN);
        }
        // two paths over the hill, across the rail: 2 and 6 rows wide
        for (int x = -12; x <= 12; x++) {
            for (int z = -31; z <= -30; z++) Cells.overlay(c, x, z, 6, 0, Double.NaN);
            for (int z = 20; z <= 25; z++) Cells.overlay(c, x, z, 6, 0, Double.NaN);
        }
        // a branch leaving the bore at its floor's level, deep under the hill
        for (int x = -10; x <= -1; x++) {
            for (int z = -5; z <= -3; z++) Cells.overlay(c, x, z, 6, 0, 600);
        }
        Voxels v = new Voxels(c, -16, -60, 16, 60, -1430, -1370);
        int floor = v.deck[v.col(0, 0)];
        List<String> bad = new ArrayList<>();
        for (int z = -40; z <= 40; z++) {
            int head = z >= 12 && z <= 16 ? 3 : 6;
            for (int x = -2; x <= 2; x++) {
                int col = v.col(x, z);
                // the bore's floor under a path it took over; at the ramped edge by a portal the arch comes one lower
                int f = v.bore[col];
                if (f == ColumnGrid.NONE) {
                    bad.add(x + "," + z + " no bore");
                    continue;
                }
                // its floor is the rail tunnel's ballast, not lining (playtest: stone bricks under the absorbed path)
                TransportPlan.Part fp = v.part(x, f, z);
                if (fp != TransportPlan.Part.BALLAST && fp != TransportPlan.Part.FLOOR) bad.add(x + "," + z + " floor " + fp);
                if (fp == TransportPlan.Part.FLOOR && boreClass(c, x, z) != OverlayPlan.RAIL_BED) bad.add(x + "," + z + " floor class");
                int h = v.tunnelWay[col] || z < 34 ? head : head - 1;
                for (int y = f + 1; y <= f + h; y++) {
                    B b = v.at(x, y, z);
                    if (b != B.AIR && b != B.RAIL && b != B.LANTERN) bad.add(x + "," + y + "," + z + " in the bore: " + b);
                }
                boolean roof = false;
                for (int y = f + h + 1; y < v.y0 + v.ny; y++) roof |= v.at(x, y, z) == B.SOLID;
                if (!roof) bad.add(x + "," + z + " open to the sky");
            }
            int f = v.bore[v.col(0, z)];
            for (int y = f + 1; y <= f + head; y++) {
                boolean branch = z >= -5 && z <= -3; // the branch leaves through the left wall
                if (!branch && v.at(-3, y, z) != B.SOLID || v.at(3, y, z) != B.SOLID) bad.add("wall open at " + z + "," + y);
            }
        }
        // the branch leaves through the wall at the bore's floor and walks on: no step up, no plug
        for (int z = -5; z <= -3; z++) {
            int f = v.bore[v.col(-2, z)];
            for (int x = -6; x <= -3; x++) {
                int d = v.deck[v.col(x, z)];
                if (Math.abs(d - f) > 1) bad.add("branch " + x + "," + z + " deck " + d + " floor " + f);
                for (int y = d + 1; y <= d + 2; y++) if (v.at(x, y, z) == B.SOLID) bad.add("branch blocked " + x + "," + y + "," + z);
            }
        }
        // the paths over the bore are still there, on the surface
        for (int[] p : new int[][] {{-8, -30}, {3, -31}, {8, -31}, {0, -30}, {0, 22}, {-2, 25}}) {
            int d = v.deck[v.col(p[0], p[1])];
            assertTrue(d > floor + 8, "path " + p[0] + "," + p[1] + " deck " + d);
            assertEquals(B.SOLID, v.at(p[0], d, p[1]));
            assertEquals(B.AIR, v.at(p[0], d + 2, p[1]));
        }
        bad.addAll(railFaults(v));
        bad.addAll(v.unstableWater(2));
        assertTrue(bad.isEmpty(), () -> "faults: " + bad);
    }

    private static int boreClass(SwdCell c, int x, int z) {
        return new TransportPlan(Cells.grid(c, x, z, TransportPlan.MARGIN)).boreClass(x, z);
    }

    /**
     * A diagonal rail tunnel under a hill (top 620 m) with open approaches at 600 m. A footpath crosses over it at an
     * angle (the sidecar burns it over the tunnel: its columns lose the tunnel flag and the rail centre), and at the
     * south portal the rail centre lacks the tunnel flag, beside the headwall (playtest 17.09.19, 5892/-4699).
     */
    @Test
    void diagonalTunnelKeepsItsTrackWhereOtherWaysCutIt() {
        SwdCell c = Cells.flat(600);
        Voxels.terrain(c, (x, z) -> {
            double a = Math.abs(x + z) / 2.0;
            return a >= 30 ? 600 : a > 20 ? 600 + 2 * (30 - a) : 620;
        });
        for (int i = -50; i <= 50; i++) {
            boolean tunnel = Math.abs(i) <= 22;
            for (int j = -3; j <= 4; j++) {
                Cells.overlay(c, i + j, i, OverlayPlan.RAIL_BED, tunnel ? TUNNEL : 0, 600); // a cut up to the portals
            }
            for (int j = 0; j <= 1; j++) {
                boolean portal = i == 22; // the centre line's last tunnel row has no tunnel flag
                Cells.overlay(c, i + j, i, OverlayPlan.RAIL_BED, RAIL | (tunnel && !portal ? TUNNEL : 0), 600);
            }
        }
        // the footpath, two wide, running north-east over the hill
        for (int i = -30; i <= 30; i++) {
            for (int j = 0; j <= 1; j++) Cells.overlay(c, i + j, -i, 6, 0, Double.NaN);
        }
        Voxels v = new Voxels(c, -40, -40, 40, 40, -1420, -1370);
        List<String> bad = new ArrayList<>(railFaults(v));
        int rails = 0;
        for (int x = -39; x <= 39; x++) {
            for (int z = -39; z <= 39; z++) {
                int f = v.bore[v.col(x, z)];
                if (f != ColumnGrid.NONE && !v.tunnelWay[v.col(x, z)]) {
                    TransportPlan.Part p = v.part(x, f, z);
                    if (p != TransportPlan.Part.BALLAST && p != TransportPlan.Part.FLOOR) bad.add(x + "," + z + " floor " + p);
                }
                for (int y = v.y0; y < v.y0 + v.ny; y++) if (v.at(x, y, z) == B.RAIL) rails++;
            }
        }
        assertTrue(bad.isEmpty(), () -> "faults: " + bad);
        assertTrue(rails >= 2 * 78, "rails " + rails);
        // the path still runs over the hill
        assertEquals(B.SOLID, v.at(1, -1380, -1));
        assertEquals(B.AIR, v.at(1, -1379, -1));
    }

    /**
     * A diagonal track (staircase running south-east) with a one column wide path crossing it to the north-east: the
     * path takes one column of the staircase (overlay.py: later wins), so two centres touch only at their corners. In
     * the open and in a tunnel the staircase keeps every step (Brienz line, 1485/-1111).
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void diagonalTrackKeepsTheStepACrossingPathTook(boolean tunnel) {
        SwdCell c = Cells.flat(600);
        if (tunnel) {
            Voxels.terrain(c, (x, z) -> {
                double a = Math.abs(x + z) / 2.0;
                return a >= 30 ? 600 : a > 20 ? 600 + 2 * (30 - a) : 620;
            });
        }
        for (int i = -50; i <= 50; i++) {
            boolean t = tunnel && Math.abs(i) <= 22;
            double h = tunnel ? 600 + 0.3 * i : 600; // the tunnel climbs, so its rail steps up beside the floor
            for (int j = -3; j <= 4; j++) Cells.overlay(c, i + j, i, OverlayPlan.RAIL_BED, t ? TUNNEL : 0, h);
            for (int j = 0; j <= 1; j++) Cells.overlay(c, i + j, i, OverlayPlan.RAIL_BED, RAIL | (t ? TUNNEL : 0), h);
        }
        for (int k = -30; k <= 30; k++) Cells.overlay(c, k, 1 - k, 6, 0, Double.NaN);
        Voxels v = new Voxels(c, -40, -40, 40, 40, -1420, -1370);
        List<String> bad = new ArrayList<>(railFaults(v));
        // the step the path took keeps its rail, perhaps on its other corner (0, 1); on the level every step does (a
        // climbing staircase drops a corner where it ascends)
        if (!hasRail(v, 1, 0) && !hasRail(v, 0, 1)) bad.add("no rail at the crossing");
        for (int i = -15; i <= 15 && !tunnel; i++) {
            for (int x = i; x <= i + 1; x++) {
                boolean found = hasRail(v, x, i) || x == 1 && i == 0 && hasRail(v, 0, 1);
                if (!found) bad.add("no rail at " + x + "," + i);
            }
        }
        assertTrue(bad.isEmpty(), () -> "faults: " + bad);
    }

    /**
     * Brienz (12194,-7972): a road the sidecar burnt over a rail tunnel at a slant took the tunnel's columns, rail bit
     * and flag included, and its two ends lie offset both ways. The bore still runs under the road, and so does the
     * track.
     */
    @ParameterizedTest
    @CsvSource({"0, false", "0.1, false", "0.3, false", "0, true", "0.3, true"})
    void roadAcrossATunnelAtASlantIsBoredUnder(double slope, boolean band) {
        SwdCell c = Cells.flat(600);
        Voxels.terrain(c, (x, z) -> {
            double a = Math.abs(x + z) / 2.0;
            return a >= 30 ? 600 : a > 20 ? 600 + 2 * (30 - a) : 620;
        });
        for (int i = -50; i <= 50; i++) {
            boolean t = Math.abs(i) <= 22;
            double h = 600 + slope * i;
            for (int j = -3; j <= 4; j++) Cells.overlay(c, i + j, i, OverlayPlan.RAIL_BED, t ? TUNNEL : 0, h);
            for (int j = 0; j <= 1; j++) Cells.overlay(c, i + j, i, OverlayPlan.RAIL_BED, RAIL | (t ? TUNNEL : 0), h);
        }
        // a road four wide over the hill, north to south: later wins, so it takes the tunnel's columns
        for (int z = -30; z <= 30; z++) {
            for (int x = -2; x <= 1; x++) Cells.overlay(c, x, z, 4, 0, Double.NaN);
        }
        if (band) {
            // Brienz 12283,-7925: the centre line is cut a little beyond the road, the tunnel's band is not, so the
            // join runs through band columns
            for (int x = 2; x <= 3; x++) {
                for (int z = x - 1; z <= x; z++) Cells.overlay(c, x, z, OverlayPlan.RAIL_BED, TUNNEL, 600 + slope * z);
            }
        }
        Voxels v = new Voxels(c, -40, -40, 40, 40, -1420, -1370);
        List<String> bad = new ArrayList<>(railFaults(v));
        for (int z = -6; z <= 6; z++) {
            if (!hasRail(v, z, z) && !hasRail(v, z + 1, z)) bad.add("no rail at row " + z);
        }
        // the road keeps its deck on the hill over the bore, with no rail on it
        for (int z = -4; z <= 4; z++) {
            if (v.at(-1, v.deck[v.col(-1, z)], z) != B.SOLID) bad.add("road deck open at -1," + z);
        }
        for (int z = -8; z <= 8; z++) {
            for (int x = -4; x <= 3; x++) {
                // the bore floor lies near y -1400 here, the road over the hill some twenty blocks higher
                for (int y = -1394; y < v.y0 + v.ny; y++) {
                    if (v.at(x, y, z) == B.RAIL) bad.add("rail on the road deck at " + x + "," + y + "," + z);
                }
            }
        }
        assertTrue(bad.isEmpty(), () -> "faults: " + bad);
    }

    private static boolean hasRail(Voxels v, int x, int z) {
        for (int y = v.y0; y < v.y0 + v.ny; y++) if (v.at(x, y, z) == B.RAIL) return true;
        return false;
    }

    /**
     * Two parallel rail tunnels (twin tubes) 3 to 8 columns apart, with way columns between them that lost the
     * tunnel flag (a shoulder or a path burnt over the hill). The bore gap must not carry a bore across: the tubes stay
     * separate, with their walls and rock between them.
     */
    @Test
    void twinTunnelsStaySeparate() {
        for (int sep = 3; sep <= 8; sep++) {
            SwdCell c = Cells.flat(600);
            Voxels.terrain(c, (x, z) -> Math.abs(z) >= 50 ? 600 : Math.abs(z) > 35 ? 650 - Math.abs(z) : 615);
            int east = 2 + sep + 3; // the second tube's centre
            for (int z = -70; z <= 70; z++) {
                boolean tunnel = Math.abs(z) <= 40;
                for (int i = -2; i <= 2; i++) {
                    int flags = (i == 0 ? RAIL : 0) | (tunnel ? TUNNEL : 0);
                    Cells.overlay(c, i, z, OverlayPlan.RAIL_BED, flags, tunnel ? 600 : Double.NaN);
                    Cells.overlay(c, east + i, z, OverlayPlan.RAIL_BED, flags, tunnel ? 600 : Double.NaN);
                }
                for (int x = 3; x < east - 2; x++) Cells.overlay(c, x, z, OverlayPlan.RAIL_BED, 0, Double.NaN);
            }
            Voxels v = new Voxels(c, -10, -60, east + 10, 60, -1430, -1370);
            List<String> bad = new ArrayList<>();
            for (int z = -30; z <= 30; z++) {
                // walls and rock between the tubes: nothing of a bore there
                for (int x = 3; x < east - 2; x++) {
                    for (int y = -1399; y <= -1394; y++) {
                        if (v.at(x, y, z) != B.SOLID) bad.add(x + "," + y + "," + z + " open between the tubes");
                    }
                }
            }
            bad.addAll(railFaults(v));
            int s = sep;
            assertTrue(bad.isEmpty(), () -> "separation " + s + ": " + bad);
        }
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
