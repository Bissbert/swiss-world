package ch.bissbert.swissworld.gen.ground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.Heights;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/** The landform texture: rock by slope and convexity, no speckles, earth in risers, one colour family, GMAT. */
class LandformTest {
    static final int X0 = ZoneMapTest.x(0), Z0 = ZoneMapTest.z(0);

    /** A cell whose terrain is {@code h(xm, zm)} in metres from its north-west corner, open ground everywhere. */
    static SwdCell cell(BiFunction<Double, Double, Double> h) {
        int n = SwdCell.SIZE * SwdCell.SIZE;
        short[] terr = new short[SwdCell.TERR_N * SwdCell.TERR_N];
        for (int j = 0; j < SwdCell.TERR_N; j++) {
            for (int i = 0; i < SwdCell.TERR_N; i++) terr[j * SwdCell.TERR_N + i] = (short) Heights.encode(h.apply(i * 2.0, j * 2.0));
        }
        short[] none = new short[n];
        Arrays.fill(none, (short) Heights.NONE);
        return new SwdCell(2632, 1170, terr, new byte[n], none, new byte[n], new byte[n], none.clone(), new byte[n], null,
                List.of(), new HashMap<>());
    }

    /**
     * Slope rising steadily from 0 degrees in the west to 60 in the east, ribbed down the fall line by 2 m ribs and
     * gullies every 60 m (convex and concave ground at every slope).
     */
    static SwdCell ramp() {
        int n = 1001;
        double[] base = new double[n];
        for (int i = 1; i < n; i++) base[i] = base[i - 1] + Math.tan(Math.toRadians(60.0 * i / 1000));
        DoubleUnaryOperator at = xm -> base[Math.min(n - 1, (int) xm)];
        return cell((xm, zm) -> 1400 + at.applyAsDouble(xm) + 2 * Math.sin(2 * Math.PI * zm / 60));
    }

    /** Terraces: 3 m steps every 10 m on a gentle grade (soil treads, earth risers). */
    static SwdCell terraces() {
        return cell((xm, zm) -> 1300 + 3 * Math.floor(xm / 10) + 0.02 * zm);
    }

    /** The cell with GMAT set to {@code v} everywhere. */
    static SwdCell withGmat(SwdCell p, int v) {
        byte[] gm = new byte[SwdCell.SIZE * SwdCell.SIZE];
        Arrays.fill(gm, (byte) v);
        return new SwdCell(p.eKm(), p.nKm(), p.terr(), p.surf(), p.wlvl(), p.wknd(), p.ocls(), p.oelv(), p.oflg(), p.insd(),
                p.buildings(), p.spans(), p.cnpy(), p.trees(), p.regn(), p.lith(), p.topo(), p.habt(), gm);
    }

    static ColumnGrid.Cells only(SwdCell c) {
        return ZoneMapTest.only(c);
    }

    // ---- pure landform functions ----

    @Test
    void rockinessMonotonicInSlopeAndConvexity() {
        for (Belt belt : Belt.values()) {
            for (boolean forest : new boolean[] {false, true}) {
                for (double c = -1; c <= 1; c += 0.05) {
                    double prev = -1;
                    for (double s = 0; s <= 80; s += 0.5) {
                        double f = Landform.softRock(s, c, forest, belt);
                        assertTrue(f >= prev - 1e-12, "soft rock falls with slope at " + s + "/" + c);
                        if (s < Landform.SOFT_FLOOR) assertEquals(0, f, "rock below the soft floor at " + s);
                        prev = f;
                        assertTrue(Landform.softRock(s, c + 0.05, forest, belt) >= f - 1e-12, "soft rock falls with convexity");
                        assertTrue(Landform.cliffRock(s + 0.5, c) >= Landform.cliffRock(s, c) - 1e-12);
                        assertTrue(Landform.cliffRock(s, c + 0.05) >= Landform.cliffRock(s, c) - 1e-12);
                    }
                }
            }
        }
        assertEquals(1, Landform.softRock(60, 0, false, Belt.MONTANE), 1e-9);
        assertTrue(Landform.softRock(38, 1, false, Belt.MONTANE) > Landform.softRock(38, -1, false, Belt.MONTANE));
    }

    /** On the ribbed ramp the share of rock tops never falls as the slope steepens, and ribs are barer than gullies. */
    @Test
    void rockShareRisesWithSlopeAndConvexity() {
        SwdCell c = ramp();
        TestWorld w = new TestWorld(only(c));
        int[] rock = new int[16], all = new int[16];
        int ribRock = 0, rib = 0, gullyRock = 0, gully = 0;
        for (int cz = Z0 + 200; cz < Z0 + 456; cz += 16) {
            for (int cx = X0 + 16; cx < X0 + 976; cx += 16) {
                ZoneMap zm = w.chunk(cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx + lx, z = cz + lz;
                        Zone zone = zm.zone(x, z);
                        if (zone.group == Zone.Group.NONE || zone.group == Zone.Group.WATER || zone.group == Zone.Group.ICE) continue;
                        boolean r = TextureScanTest.rock(GroundTexture.top(zm, x, z));
                        double s = zm.smoothSlope(x, z), conv = Landform.convex(zm.convexity(x, z), zm.tpi(x, z));
                        int bin = Math.min(15, (int) (s / 5));
                        all[bin]++;
                        if (r) rock[bin]++;
                        if (s >= 34 && s < 44) {
                            if (conv > 0.3) {
                                rib++;
                                if (r) ribRock++;
                            } else if (conv < -0.3) {
                                gully++;
                                if (r) gullyRock++;
                            }
                        }
                    }
                }
            }
        }
        double prev = 0;
        StringBuilder shares = new StringBuilder();
        for (int b = 0; b < 16; b++) {
            if (all[b] < 200) continue;
            double f = rock[b] / (double) all[b];
            shares.append(b * 5).append(": ").append(String.format("%.3f ", f));
            assertTrue(f >= prev - 0.03, "rock share falls at " + b * 5 + " deg: " + shares);
            if (b * 5 + 5 <= 25) assertEquals(0, rock[b], "rock below 25 deg: " + shares);
            prev = Math.max(prev, f);
        }
        assertTrue(prev > 0.9, "steep ground is rock: " + shares);
        assertTrue(rib > 100 && gully > 100, rib + " ribs, " + gully + " gullies");
        assertTrue(ribRock / (double) rib > gullyRock / (double) gully + 0.1,
                "ribs " + ribRock + "/" + rib + " vs gullies " + gullyRock + "/" + gully);
    }

    /** Below 25 degrees no rock top stands alone in soil (the old speckles). */
    @Test
    void noLoneRockInGentleGrass() {
        for (SwdCell c : new SwdCell[] {ramp(), ZoneMapTest.mountain(), terraces()}) {
            TestWorld w = new TestWorld(only(c));
            HashMap<Long, ZoneMap> maps = new HashMap<>();
            int x0 = (X0 + 304) & ~15, z0 = (Z0 + 304) & ~15;
            for (int cz = z0; cz < z0 + 384; cz += 16) {
                for (int cx = x0; cx < x0 + 384; cx += 16) maps.put(key(cx, cz), w.chunk(cx, cz));
            }
            int gentle = 0;
            for (int z = z0 + 1; z < z0 + 383; z++) {
                for (int x = x0 + 1; x < x0 + 383; x++) {
                    ZoneMap zm = maps.get(key(Math.floorDiv(x, 16) * 16, Math.floorDiv(z, 16) * 16));
                    if (zm.smoothSlope(x, z) >= 25 || zm.zone(x, z).group == Zone.Group.NONE) continue;
                    gentle++;
                    if (!TextureScanTest.rock(w.get(x, w.top(x, z), z).getBlock())) continue;
                    boolean ring = true;
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        ring &= GroundTexture.soil(w.get(x + d[0], w.top(x + d[0], z + d[1]), z + d[1]).getBlock());
                    }
                    assertTrue(!ring, "lone rock at " + x + "," + z + " slope " + zm.smoothSlope(x, z));
                }
            }
            assertTrue(gentle > 1000);
        }
    }

    /**
     * A soil column shows earth all the way down its exposed step face, never stone: also on 12 m steps the orthophoto
     * calls grass (risers deeper than the terrain's 3 blocks of dirt).
     */
    @Test
    void soilRisersAreEarth() {
        SwdCell steps = withGmat(cell((xm, zm) -> 1300 + 12 * Math.floor(xm / 40)), 0x02);
        int deep = 0, cliffs = 0;
        SwdCell[] cells = {steps, terraces(), ramp(), ZoneMapTest.mountain()};
        for (int k = 0; k < cells.length; k++) {
            SwdCell c = cells[k];
            TestWorld w = new TestWorld(only(c));
            int x0 = (X0 + 400) & ~15, z0 = (Z0 + 400) & ~15;
            java.util.Map<Long, ZoneMap> zms = new java.util.HashMap<>();
            for (int cz = z0 - 16; cz < z0 + 144; cz += 16) {
                for (int cx = x0 - 16; cx < x0 + 144; cx += 16) zms.put(((long) cx << 32) ^ (cz & 0xffffffffL), w.chunk(cx, cz));
            }
            int faces = 0;
            for (int z = z0; z < z0 + 128; z++) {
                for (int x = x0; x < x0 + 128; x++) {
                    int y = w.top(x, z);
                    ZoneMap zm = zms.get(((long) (x & ~15) << 32) ^ ((z & ~15) & 0xffffffffL));
                    boolean cliff = zm != null && zm.smoothSlope(x, z) >= GroundTexture.FACE_ROCK;
                    // photo grass on a near-vertical step is a tuft on a cliff: its face is rock, by design
                    if (cliff && TextureScanTest.rock(w.get(x, y, z).getBlock())) cliffs++;
                    if (!GroundTexture.soil(w.get(x, y, z).getBlock())) continue;
                    int low = Math.min(Math.min(w.top(x - 1, z), w.top(x + 1, z)), Math.min(w.top(x, z - 1), w.top(x, z + 1)));
                    if (low < y - 1) faces++;
                    if (low < y - GroundTexture.UNDER - 1) deep++;
                    for (int b = y - 1; b > low && !cliff; b--) {
                        Block s = w.get(x, b, z).getBlock();
                        assertTrue(!TextureScanTest.rock(s) && s != Blocks.GRAVEL, s + " in the riser at " + x + "," + b + "," + z);
                    }
                }
            }

        }
        assertTrue(cliffs > 100, "rock on near-vertical photo-grass steps " + cliffs);
    }

    /** Rock and scree tops come from their lithology's family only; nothing pink anywhere. */
    @Test
    void oneFamilyPerLithology() {
        Set<Block> granite = Set.of(Blocks.GRANITE, Blocks.POLISHED_GRANITE, Blocks.RED_SANDSTONE, Blocks.TERRACOTTA);
        for (Lith l : Lith.values()) {
            for (Palette p : new Palette[] {GroundTexture.rock(l), GroundTexture.scree(l)}) {
                for (int i = 0; i < p.size(); i++) assertTrue(!granite.contains(p.block(i)), l + " has " + p.block(i));
            }
        }
        // light greys for limestone, no light blocks in schist
        assertTrue(GroundTexture.rock(Lith.LIMESTONE).share(Blocks.CALCITE) <= 0.06);
        for (Block b : new Block[] {Blocks.CALCITE, Blocks.DIORITE, Blocks.SANDSTONE}) {
            assertEquals(0, GroundTexture.rock(Lith.SCHIST).share(b));
            assertEquals(0, GroundTexture.rock(Lith.CRYSTALLINE).share(b));
        }
        // soil palettes carry no rock
        for (Zone z : Zone.values()) {
            Palette p = GroundTexture.palette(z);
            if (p == null || z == Zone.INDUSTRIAL || z == Zone.RIVERBED_GRAVEL || z == Zone.ALPINE_LAKE_SHORE) continue;
            for (int i = 0; i < p.size(); i++) assertTrue(p.block(i) != null && !TextureScanTest.rock(p.block(i)), z + " has rock");
        }
        // end to end: the mountain in bands of every lithology
        SwdCell c = ZoneMapTest.withLayers(ZoneMapTest.mountain(), (dx, dz) -> new int[] {0, 1 + dx / 200 % 5, 0, 0});
        TestWorld w = new TestWorld(only(c));
        int checked = 0;
        for (int cz = Z0 + 100; cz < Z0 + 900; cz += 32) {
            for (int cx = X0 + 100; cx < X0 + 900; cx += 32) {
                ZoneMap zm = w.chunk(cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx + lx, z = cz + lz;
                        Block b = GroundTexture.top(zm, x, z);
                        if (b == null || GroundTexture.soil(b) || !(TextureScanTest.rock(b) || b == Blocks.GRAVEL)) continue;
                        Lith l = ZoneId.lith(zm.id(x, z));
                        Zone zone = zm.zone(x, z);
                        if (zone == Zone.RIVERBED_GRAVEL || zone == Zone.ALPINE_LAKE_SHORE) continue;
                        checked++;
                        assertTrue(GroundTexture.rock(l).share(b) > 0 || GroundTexture.scree(l).share(b) > 0,
                                b + " is not of the " + l + " family (" + zone + ")");
                    }
                }
            }
        }
        assertTrue(checked > 500, "rock tops checked " + checked);
    }

    /** GMAT decides the class of open and rock columns and picks the tone; a cell without it reads as GMAT 0. */
    @Test
    void gmatOverridesClassAndTone() {
        SwdCell plain = ZoneMapTest.withLayers(ZoneMapTest.mountain(), (dx, dz) -> new int[] {0, 2, 0, 0});
        byte[] gm = new byte[SwdCell.SIZE * SwdCell.SIZE];
        for (int dz = 0; dz < SwdCell.SIZE; dz++) {
            for (int dx = 0; dx < SwdCell.SIZE; dx++) {
                int v = dz < 500 ? (dx < 500 ? 0xF5 : 0x06) : (dx < 500 ? 0x02 : 0);
                gm[SwdCell.key(dx, dz)] = (byte) v;
            }
        }
        SwdCell p = plain;
        SwdCell with = new SwdCell(p.eKm(), p.nKm(), p.terr(), p.surf(), p.wlvl(), p.wknd(), p.ocls(), p.oelv(), p.oflg(),
                p.insd(), p.buildings(), p.spans(), p.cnpy(), p.trees(), p.regn(), p.lith(), p.topo(), p.habt(), gm);
        Palette rock = GroundTexture.rock(Lith.CRYSTALLINE);
        Block lightest = rock.block(rock.size() - 1), darkest = rock.block(0);
        int light = 0, dark = 0, soil = 0, same = 0, changed = 0;
        for (int cz = Z0 + 100; cz < Z0 + 900; cz += 32) {
            for (int cx = X0 + 100; cx < X0 + 900; cx += 32) {
                ZoneMap a = ZoneMap.of(ZoneMapTest.ORIGIN, only(with), cx, cz), b = ZoneMap.of(ZoneMapTest.ORIGIN, only(plain), cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx + lx, z = cz + lz, dx = x - X0, dz = z - Z0;
                        Zone zone = a.zone(x, z);
                        Block got = GroundTexture.top(a, x, z), ref = GroundTexture.top(b, x, z);
                        boolean applies = zone.group == Zone.Group.OPEN || zone.group == Zone.Group.ROCK
                                || zone.group == Zone.Group.LOOSE && zone != Zone.RIVERBED_GRAVEL && zone != Zone.ALPINE_LAKE_SHORE;
                        if (dz >= 500 && dx >= 500) {
                            assertEquals(ref, got, "GMAT 0 reads as no GMAT at " + x + "," + z);
                            same++;
                        } else if (applies && dz < 500 && dx < 500) {
                            assertEquals(lightest, got, "light rock at " + x + "," + z + " in " + zone);
                            light++;
                        } else if (applies && dz < 500) {
                            assertEquals(darkest, got, "dark rock at " + x + "," + z + " in " + zone);
                            dark++;
                        } else if (applies) {
                            assertTrue(GroundTexture.soil(got), "green grass gives soil at " + x + "," + z + ": " + got);
                            soil++;
                            if (got != ref) changed++;
                        }
                    }
                }
            }
        }
        assertTrue(light > 500 && dark > 500 && soil > 500 && same > 500, light + " " + dark + " " + soil + " " + same);
        assertNotEquals(0, changed, "GMAT grass turns some landform rock to soil");
        assertEquals(0, plain.gmat(10, 10));
    }

    private static long key(int x, int z) {
        return (long) x << 32 | (z & 0xffffffffL);
    }
}
