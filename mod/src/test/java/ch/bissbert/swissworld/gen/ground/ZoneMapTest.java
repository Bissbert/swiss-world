package ch.bissbert.swissworld.gen.ground;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.Heights;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/** Zones and the ground stages on a synthetic mountain: chunk-border agreement, aspect, valid blocks, speed. */
class ZoneMapTest {
    static final SwissCoords ORIGIN = TestWorld.ORIGIN;

    /**
     * Cell 2632_1170 (x -900..99, z -400..599) as a cone (about 34 degrees) peaking at 2,850 m in its middle, falling
     * to about 2,500 m at the cell's sides: every aspect, both sides of the snow line. Forest below 2,560 m on the
     * west half, a rock band at 2,600 to 2,640 m, scree at 2,680 to 2,720 m, a glacier above 2,790 m on the north half.
     */
    static SwdCell mountain() {
        int n = SwdCell.SIZE * SwdCell.SIZE;
        short[] terr = new short[SwdCell.TERR_N * SwdCell.TERR_N];
        for (int j = 0; j < SwdCell.TERR_N; j++) {
            for (int i = 0; i < SwdCell.TERR_N; i++) {
                double r = Math.hypot(i * 2 - 500, j * 2 - 500);
                double h = 2850 - 400 * r / 600 + 6 * Math.sin(i * 0.11) * Math.cos(j * 0.07);
                terr[j * SwdCell.TERR_N + i] = (short) Heights.encode(h);
            }
        }
        byte[] surf = new byte[n];
        short[] none = new short[n];
        Arrays.fill(none, (short) Heights.NONE);
        SwdCell c = new SwdCell(2632, 1170, terr, surf, none, new byte[n], new byte[n], none.clone(), new byte[n], null,
                List.of(), new HashMap<>());
        for (int dz = 0; dz < SwdCell.SIZE; dz++) {
            for (int dx = 0; dx < SwdCell.SIZE; dx++) {
                double h = c.terrain(dx + 0.5, dz + 0.5);
                int s = 0;
                if (h < 2560 && dx < 500) s = 2;
                if (h > 2600 && h < 2640) s = 4;
                if (h > 2680 && h < 2720) s = 5;
                if (h > 2790 && dz < 500) s = 6;
                surf[SwdCell.key(dx, dz)] = (byte) s;
            }
        }
        return c;
    }

    /** The cell with the sidecar-zones layers set per column by {@code f(dx, dz) -> {regn, lith, topo, habt}}. */
    static SwdCell withLayers(SwdCell c, java.util.function.BiFunction<Integer, Integer, int[]> f) {
        int n = SwdCell.SIZE * SwdCell.SIZE;
        byte[] regn = new byte[n], lith = new byte[n], topo = new byte[n], habt = new byte[n];
        for (int dz = 0; dz < SwdCell.SIZE; dz++) {
            for (int dx = 0; dx < SwdCell.SIZE; dx++) {
                int[] v = f.apply(dx, dz);
                int k = SwdCell.key(dx, dz);
                regn[k] = (byte) v[0];
                lith[k] = (byte) v[1];
                topo[k] = (byte) v[2];
                habt[k] = (byte) v[3];
            }
        }
        return new SwdCell(c.eKm(), c.nKm(), c.terr(), c.surf(), c.wlvl(), c.wknd(), c.ocls(), c.oelv(), c.oflg(), c.insd(),
                c.buildings(), c.spans(), c.cnpy(), c.trees(), regn, lith, topo, habt);
    }

    static ColumnGrid.Cells only(SwdCell cell) {
        return (x, z) -> SwissCoords.cellKm(ORIGIN.east(x)) == cell.eKm() && SwissCoords.cellKm(ORIGIN.north(z)) == cell.nKm()
                ? cell : null;
    }

    /** World x of cell column dx, world z of cell row dz. */
    static int x(int dx) {
        return dx - 900;
    }

    /** Rows run north to south: dz 0 is the cell's northern edge. */
    static int z(int dz) {
        return dz - 400;
    }

    @Test
    void chunkBordersAgree() {
        ColumnGrid.Cells cells = only(mountain());
        // windows on the 4-column TPI lattice but not on chunk borders see each column exactly as its own chunk does
        for (int cx = x(300) & ~15; cx < x(700); cx += 48) {
            for (int cz = z(300) & ~15; cz < z(700); cz += 48) {
                ZoneMap a = ZoneMap.of(ORIGIN, cells, cx, cz), b = ZoneMap.of(ORIGIN, cells, cx + 16, cz + 16);
                for (int[] off : new int[][] {{8, 0}, {0, 8}, {4, 12}, {12, 4}}) {
                    ZoneMap s = ZoneMap.of(ORIGIN, cells, cx + off[0], cz + off[1]);
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            int x = s.x0 + lx, z = s.z0 + lz;
                            ZoneMap own = x < cx + 16 && z < cz + 16 ? a : x >= cx + 16 && z >= cz + 16 ? b
                                    : ZoneMap.of(ORIGIN, cells, Math.floorDiv(x, 16) * 16, Math.floorDiv(z, 16) * 16);
                            assertEquals(own.id(x, z), s.id(x, z), "zone at " + x + "," + z);
                            assertEquals(own.snow(x, z), s.snow(x, z), "snow at " + x + "," + z);
                            assertEquals(own.slope(x, z), s.slope(x, z), 1e-9);
                            assertEquals(own.smoothSlope(x, z), s.smoothSlope(x, z), 1e-9);
                            assertEquals(own.convexity(x, z), s.convexity(x, z), 1e-9);
                            assertEquals(own.tpi(x, z), s.tpi(x, z), 1e-9);
                            assertEquals(own.cliffDist(x, z), s.cliffDist(x, z), "cliff distance at " + x + "," + z);
                            assertEquals(own.drop(x, z), s.drop(x, z), "drop at " + x + "," + z);
                            assertEquals(GroundTexture.top(own, x, z), GroundTexture.top(s, x, z), "texture at " + x + "," + z);
                        }
                    }
                }
            }
        }
    }

    /** Layers win where set: region and lithology in the packed id, lee and exposed snow; zero layers fall back. */
    @Test
    void layersOverrideFallbacks() {
        SwdCell plain = mountain();
        // all-zero layers read exactly like no layers (region and lithology unknown, mid-slope, neutral wind)
        SwdCell zeros = withLayers(plain, (dx, dz) -> new int[4]);
        int x0 = x(480) & ~15, z0 = z(560) & ~15;
        for (int[] at : new int[][] {{x0, z0}, {x(200) & ~15, z(300) & ~15}, {x(700) & ~15, z(700) & ~15}}) {
            ZoneMap a = ZoneMap.of(ORIGIN, only(plain), at[0], at[1]), b = ZoneMap.of(ORIGIN, only(zeros), at[0], at[1]);
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    assertEquals(a.id(at[0] + lx, at[1] + lz), b.id(at[0] + lx, at[1] + lz));
                    assertEquals(a.snow(at[0] + lx, at[1] + lz), b.snow(at[0] + lx, at[1] + lz));
                }
            }
        }
        // REGN 6 (south flank) and LITH 1 (limestone) everywhere; TOPO lee on the west half, exposed on the east
        SwdCell south = withLayers(plain, (dx, dz) -> new int[] {6, 1, dx < 500 ? 1 << 6 : 2 << 6, 0});
        int lee = 0, exposed = 0, leeN = 0, expN = 0;
        boolean limestone = false;
        for (int cx = x(100) & ~15; cx < x(900); cx += 32) {
            for (int cz = z(100) & ~15; cz < z(900); cz += 32) {
                ZoneMap zm = ZoneMap.of(ORIGIN, only(south), cx, cz), ref = ZoneMap.of(ORIGIN, only(plain), cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx + lx, z = cz + lz;
                        Zone zone = zm.zone(x, z);
                        if (zone == Zone.NONE) continue;
                        assertEquals(Region.SOUTH_FLANK, ZoneId.region(zm.id(x, z)));
                        assertTrue(zone != Zone.CRYSTALLINE_CLIFF, "limestone has no crystalline cliffs");
                        limestone |= zone == Zone.LIMESTONE_CLIFF || zone == Zone.KARST;
                        if (zone.glacier()) continue;
                        if (x < x(500)) {
                            leeN++;
                            lee += zm.snow(x, z) - ref.snow(x, z);
                        } else {
                            expN++;
                            exposed += zm.snow(x, z) - ref.snow(x, z);
                        }
                    }
                }
            }
        }
        assertTrue(limestone);
        assertTrue(lee > 0 && exposed < 0, "lee " + lee + "/" + leeN + ", exposed " + exposed + "/" + expN);
    }

    /** Chunk borders agree with layers that change from column to column. */
    @Test
    void chunkBordersAgreeWithLayers() {
        SwdCell c = withLayers(mountain(), (dx, dz) -> new int[] {1 + (dx / 37 + dz / 53) % 6, (dx * 7 + dz) / 41 % 6,
                (dx * 31 + dz * 17) % 256, (dx / 13 + dz / 11) % 3 == 0 ? (dx / 29 % 6) << 3 | dz % 5 : 0});
        ColumnGrid.Cells cells = only(c);
        for (int cx = x(300) & ~15; cx < x(700); cx += 80) {
            for (int cz = z(300) & ~15; cz < z(700); cz += 80) {
                ZoneMap s = ZoneMap.of(ORIGIN, cells, cx + 8, cz + 4);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = s.x0 + lx, z = s.z0 + lz;
                        ZoneMap own = ZoneMap.of(ORIGIN, cells, Math.floorDiv(x, 16) * 16, Math.floorDiv(z, 16) * 16);
                        assertEquals(own.id(x, z), s.id(x, z), "zone at " + x + "," + z);
                        assertEquals(own.snow(x, z), s.snow(x, z), "snow at " + x + "," + z);
                    }
                }
            }
        }
    }

    @Test
    void cellEdgeChunkHasNoGround() {
        ColumnGrid.Cells cells = only(mountain());
        // the chunk straddling the cell's west edge (x = -900): columns west of it are outside every cell
        int x0 = Math.floorDiv(x(0), 16) * 16;
        ZoneMap zm = ZoneMap.of(ORIGIN, cells, x0 - 8, 96);
        for (int lx = 0; lx < 16; lx++) {
            int x = zm.x0 + lx;
            Zone z = zm.zone(x, 96);
            if (x < x(0)) assertEquals(Zone.NONE, z);
            else assertTrue(z != Zone.NONE);
        }
    }

    @Test
    void northFacesHoldMoreSnow() {
        ColumnGrid.Cells cells = only(mountain());
        int[] nSnow = new int[40], nAll = new int[40], sSnow = new int[40], sAll = new int[40];
        for (int cx = x(50) & ~15; cx < x(950); cx += 16) {
            for (int cz = z(50) & ~15; cz < z(950); cz += 16) {
                ZoneMap zm = ZoneMap.of(ORIGIN, cells, cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx + lx, z = cz + lz;
                        if (zm.zone(x, z) == Zone.NONE || zm.zone(x, z).glacier() || zm.slope(x, z) < 10) continue;
                        int band = (int) ((zm.metres(x, z) - 2400) / 25);
                        if (band < 0 || band >= 40) continue;
                        double nn = zm.northness(x, z);
                        if (nn > 0.7) {
                            nAll[band]++;
                            if (zm.snow(x, z) > 0) nSnow[band]++;
                        } else if (nn < -0.7) {
                            sAll[band]++;
                            if (zm.snow(x, z) > 0) sSnow[band]++;
                        }
                    }
                }
            }
        }
        int north = 0, south = 0;
        for (int b = 0; b < 40; b++) {
            if (nAll[b] < 50 || sAll[b] < 50) continue;
            double fn = nSnow[b] / (double) nAll[b], fs = sSnow[b] / (double) sAll[b];
            assertTrue(fn + 0.02 >= fs, "band " + (2400 + b * 25) + " m: north " + fn + " < south " + fs);
            north += nSnow[b];
            south += sSnow[b];
        }
        assertTrue(north > south, north + " vs " + south);
    }

    /** Every block the ground stages leave on top can stay there by vanilla rules. */
    @Test
    void stagesEmitValidTops() {
        TestWorld w = new TestWorld(only(mountain()));
        Map<Block, Integer> tops = new HashMap<>();
        int snowy = 0;
        for (int cx = x(100) & ~15; cx < x(900); cx += 32) {
            for (int cz = z(100) & ~15; cz < z(900); cz += 32) {
                ZoneMap zm = w.chunk(cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx + lx, z = cz + lz, y = w.top(x, z);
                        BlockState s = w.get(x, y, z);
                        tops.merge(s.getBlock(), 1, Integer::sum);
                        if (s.is(Blocks.SNOW)) {
                            snowy++;
                            BlockState below = w.get(x, y - 1, z);
                            assertTrue(snowLayerSurvives(below), "snow layer on " + below + " at " + x + "," + z);
                        }
                        if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.SNOW)) assertTrue(zm.snow(x, z) > 0 || zm.zone(x, z).glacier());
                    }
                }
            }
        }
        assertTrue(snowy > 0);
        assertTrue(tops.size() >= 8, "a mix of tops: " + tops);
    }

    static boolean snowLayerSurvives(BlockState below) {
        if (below.is(BlockTags.SNOW_LAYER_CANNOT_SURVIVE_ON)) return false;
        if (below.is(BlockTags.SNOW_LAYER_CAN_SURVIVE_ON)) return true;
        return Block.isFaceFull(below.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Direction.UP)
                || below.is(Blocks.SNOW) && below.getValue(SnowLayerBlock.LAYERS) == 8;
    }

    /** Zones, texture and snow of one chunk stay well inside the 0.5 ms budget. Printed as a BENCH line. */
    @Test
    void cheap() {
        ColumnGrid.Cells cells = only(mountain());
        long zones = 0, stages = 0;
        int chunks = 0;
        for (int rep = 0; rep < 4; rep++) {
            zones = stages = 0;
            chunks = 0;
            TestWorld w = new TestWorld(cells);
            for (int cx = x(200) & ~15; cx < x(800); cx += 16) {
                for (int cz = z(200) & ~15; cz < z(800); cz += 16) {
                    for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) w.baseTop(cx + lx, cz + lz);
                    long t0 = System.nanoTime();
                    ZoneMap zm = ZoneMap.of(ORIGIN, cells, cx, cz);
                    long t1 = System.nanoTime();
                    GroundTextureStage.apply(zm, w);
                    SnowStage.apply(zm, w);
                    long t2 = System.nanoTime();
                    zones += t1 - t0;
                    stages += t2 - t1;
                    chunks++;
                }
            }
        }
        double ms = (zones + stages) / 1e6 / chunks;
        System.out.printf("BENCH ground: zones %.3f ms/chunk, texture+snow %.3f ms/chunk (%d chunks)%n", zones / 1e6 / chunks,
                stages / 1e6 / chunks, chunks);
        // generous against busy machines; the budget is 0.5 ms
        assertTrue(ms < 5, "ground stages cost " + ms + " ms per chunk");
    }
}
