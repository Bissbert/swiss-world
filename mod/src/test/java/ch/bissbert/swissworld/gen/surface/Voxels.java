package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.gen.surface.OverlayPlan.Rail;
import ch.bissbert.swissworld.gen.surface.TransportPlan.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleBinaryOperator;

/**
 * A block model of a box of columns, built the way the stages build it: terrain up to {@link ColumnGrid#top}, water as
 * {@link WaterStage} places it, then {@link TransportPlan#build} per chunk (each chunk with its own grid, so chunk
 * borders are exercised). Plus checks that mirror vanilla's fluid rules.
 */
final class Voxels {
    enum B {
        AIR, SOLID, SOURCE, FLOW, CANE, RAIL, SLAB, LANTERN
    }

    final int x0, z0, y0, nx, nz, ny;
    private final B[] b;
    /** Water level (1..7 flowing, 8 falling) of FLOW blocks. */
    private final int[] level;
    private final Rail[] shape;
    /** Per column: the deck the plan reported (road/rail), or NONE. */
    final int[] deck;
    /** Per column: the floor of a bored profile here (whatever way runs above or through it), or NONE. */
    final int[] bore;
    final boolean[] slab, tunnelWay, openRoad, railWay, wayCol;
    final int[] originalTop;

    /** The grid a chunk is built with, from its north-west corner. */
    interface Grids {
        ColumnGrid grid(int x, int z);
    }

    Voxels(SwdCell cell, int x0, int z0, int x1, int z1, int y0, int y1) {
        this((x, z) -> Cells.grid(cell, x, z, TransportPlan.MARGIN), x0, z0, x1, z1, y0, y1);
    }

    Voxels(Grids grids, int x0, int z0, int x1, int z1, int y0, int y1) {
        this.x0 = x0;
        this.z0 = z0;
        this.y0 = y0;
        nx = x1 - x0 + 1;
        nz = z1 - z0 + 1;
        ny = y1 - y0 + 1;
        b = new B[nx * nz * ny];
        level = new int[b.length];
        shape = new Rail[b.length];
        java.util.Arrays.fill(b, B.AIR);
        deck = new int[nx * nz];
        bore = new int[nx * nz];
        java.util.Arrays.fill(deck, ColumnGrid.NONE);
        slab = new boolean[nx * nz];
        tunnelWay = new boolean[nx * nz];
        openRoad = new boolean[nx * nz];
        railWay = new boolean[nx * nz];
        wayCol = new boolean[nx * nz];
        originalTop = new int[nx * nz];
        for (int cx = Math.floorDiv(x0, 16); cx <= Math.floorDiv(x1, 16); cx++) {
            for (int cz = Math.floorDiv(z0, 16); cz <= Math.floorDiv(z1, 16); cz++) {
                ColumnGrid g = grids.grid(cx * 16, cz * 16);
                TransportPlan t = new TransportPlan(g);
                for (int x = Math.max(x0, cx * 16); x <= Math.min(x1, cx * 16 + 15); x++) {
                    for (int z = Math.max(z0, cz * 16); z <= Math.min(z1, cz * 16 + 15); z++) column(g, t, x, z);
                }
            }
        }
    }

    private void column(ColumnGrid g, TransportPlan t, int x, int z) {
        int c = col(x, z);
        int ground = g.ground(x, z);
        originalTop[c] = g.top(x, z);
        for (int y = y0; y <= ground; y++) put(x, y, z, B.SOLID, 0);
        WaterPlan.Fill f = WaterPlan.fill(g, x, z);
        if (f != null && f.reed()) {
            int s = f.sourceTop();
            for (int y = ground + 1; y < s; y++) put(x, y, z, B.SOLID, 0);
            put(x, s, z, B.CANE, 0);
            put(x, f.fallTop(), z, B.FLOW, 1);
        } else if (f != null) {
            for (int y = ground + 1; y <= f.sourceTop(); y++) put(x, y, z, B.SOURCE, 0);
            if (f.lip()) put(x, f.sourceTop() + 1, z, B.FLOW, 2);
            if (f.rock()) {
                for (int y = f.sourceTop() + 1; y <= f.fallTop(); y++) put(x, y, z, B.SOLID, 0);
            } else {
                for (int y = f.sourceTop() + 1; y < f.fallTop(); y++) put(x, y, z, B.FLOW, 8);
                if (f.falls()) put(x, f.fallTop(), z, B.FLOW, 1);
            }
        } else {
            for (int y = ground + 1; y <= g.top(x, z); y++) put(x, y, z, B.SOLID, 0);
        }
        boolean[] slabbed = {false};
        t.build(x, z, (y, p) -> {
            B v = switch (p) {
                case AIR -> B.AIR;
                case RAIL -> B.RAIL;
                case SLAB -> {
                    slabbed[0] = true;
                    yield B.SLAB;
                }
                case LANTERN -> B.LANTERN;
                default -> B.SOLID;
            };
            put(x, y, z, v, 0);
            if (p == Part.RAIL) shape[idx(x, y, z)] = t.railShape(x, z);
        });
        bore[c] = t.tunnelFloor(x, z);
        if (t.way(x, z)) {
            wayCol[c] = true;
            deck[c] = t.tunnel(x, z) || t.absorbed(x, z) ? t.tunnelFloor(x, z) : t.deck(x, z);
            slab[c] = slabbed[0];
            tunnelWay[c] = t.tunnel(x, z);
            openRoad[c] = t.open(x, z) && t.road(x, z);
            railWay[c] = t.railWay(x, z);
        }
    }

    boolean in(int x, int y, int z) {
        return x >= x0 && x < x0 + nx && z >= z0 && z < z0 + nz && y >= y0 && y < y0 + ny;
    }

    int col(int x, int z) {
        return (z - z0) * nx + (x - x0);
    }

    private int idx(int x, int y, int z) {
        return ((y - y0) * nz + (z - z0)) * nx + (x - x0);
    }

    private void put(int x, int y, int z, B v, int lvl) {
        if (!in(x, y, z)) return;
        b[idx(x, y, z)] = v;
        level[idx(x, y, z)] = lvl;
    }

    /** Outside the box: solid below the box, air above and beside. */
    B at(int x, int y, int z) {
        if (y < y0) return B.SOLID;
        if (!in(x, y, z)) return B.AIR;
        return b[idx(x, y, z)];
    }

    int level(int x, int y, int z) {
        return in(x, y, z) ? level[idx(x, y, z)] : 0;
    }

    Rail shape(int x, int y, int z) {
        return in(x, y, z) ? shape[idx(x, y, z)] : null;
    }

    static boolean water(B v) {
        return v == B.SOURCE || v == B.FLOW;
    }

    /** Fluid amount as vanilla counts it: 8 for a source or falling water, 8 - level for flowing water. */
    private int amount(int x, int y, int z) {
        B v = at(x, y, z);
        if (v == B.SOURCE) return 8;
        if (v != B.FLOW) return 0;
        int l = level(x, y, z);
        return l == 8 ? 8 : 8 - l;
    }

    /** Walking surface of a way column: one above the deck, half a block more on a slab. */
    double walk(int x, int z) {
        int c = col(x, z);
        return deck[c] + 1 + (slab[c] ? 0.5 : 0);
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Water that vanilla's rules would change on its first update, in the box shrunk by {@code margin} columns:
     * sources beside air, water above air, flowing water whose level its neighbours would not give it, flowing water
     * that would become a source, and flowing water that would spread sideways into air.
     */
    List<String> unstableWater(int margin) {
        List<String> bad = new ArrayList<>();
        for (int x = x0 + margin; x < x0 + nx - margin; x++) {
            for (int z = z0 + margin; z < z0 + nz - margin; z++) {
                for (int y = y0 + 1; y < y0 + ny; y++) {
                    B v = at(x, y, z);
                    if (!water(v)) continue;
                    String here = x + "," + y + "," + z;
                    B below = at(x, y - 1, z);
                    if (below == B.AIR || below == B.RAIL || below == B.LANTERN) {
                        bad.add(here + " over air");
                        continue;
                    }
                    if (v == B.SOURCE) {
                        for (int[] d : SIDES) {
                            if (at(x + d[0], y, z + d[1]) == B.AIR) bad.add(here + " source beside air");
                        }
                        continue;
                    }
                    int sources = 0, best = 0;
                    for (int[] d : SIDES) {
                        int a = amount(x + d[0], y, z + d[1]);
                        if (at(x + d[0], y, z + d[1]) == B.SOURCE) sources++;
                        best = Math.max(best, a - 1);
                    }
                    boolean holds = below == B.SOLID || below == B.SLAB || below == B.SOURCE;
                    if (sources >= 2 && holds) {
                        bad.add(here + " would become a source");
                        continue;
                    }
                    int lvl = level(x, y, z);
                    if (lvl == 8) {
                        if (!water(at(x, y + 1, z))) bad.add(here + " falling without water above");
                        continue;
                    }
                    if (water(at(x, y + 1, z))) {
                        bad.add(here + " flowing under water");
                        continue;
                    }
                    if (best <= 0 || 8 - best != lvl) bad.add(here + " level " + lvl + " but neighbours give " + (8 - best));
                    if (!water(below) && best - 1 > 0) {
                        for (int[] d : SIDES) {
                            if (at(x + d[0], y, z + d[1]) == B.AIR) bad.add(here + " spreads into air");
                        }
                    }
                }
            }
        }
        return bad;
    }

    /** Solid blocks at a water surface with water on two opposite sides (a stone in the river). */
    List<String> stonesInWater(int margin) {
        List<String> bad = new ArrayList<>();
        for (int x = x0 + margin; x < x0 + nx - margin; x++) {
            for (int z = z0 + margin; z < z0 + nz - margin; z++) {
                for (int y = y0 + 1; y < y0 + ny; y++) {
                    if (at(x, y, z) != B.SOLID || water(at(x, y + 1, z)) || at(x, y + 1, z) == B.SOLID) continue;
                    boolean ew = surface(x + 1, y, z) && surface(x - 1, y, z);
                    boolean ns = surface(x, y, z + 1) && surface(x, y, z - 1);
                    if (ew || ns) bad.add(x + "," + y + "," + z);
                }
            }
        }
        return bad;
    }

    /** Water at y with no water above: the surface. */
    private boolean surface(int x, int y, int z) {
        return water(at(x, y, z)) && !water(at(x, y + 1, z));
    }

    /** Sets TERR nodes so the terrain is {@code f(x, z)} metres at block column centres (exact for linear f). */
    static void terrain(SwdCell c, DoubleBinaryOperator f) {
        for (int j = 0; j < SwdCell.TERR_N; j++) {
            for (int i = 0; i < SwdCell.TERR_N; i++) {
                // node (i, j) lies at dx 2i, dz 2j; column x's centre at dx + 0.5 with dx = x + 900, dz = z + 400
                double x = 2 * i - 900 - 0.5, z = 2 * j - 400 - 0.5;
                c.terr()[j * SwdCell.TERR_N + i] = (short) ch.bissbert.swissworld.cell.Heights.encode(f.applyAsDouble(x, z));
            }
        }
    }
}
