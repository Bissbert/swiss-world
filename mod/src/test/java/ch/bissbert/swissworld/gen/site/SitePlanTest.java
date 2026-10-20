package ch.bissbert.swissworld.gen.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import ch.bissbert.swissworld.gen.surface.TransportPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The site plan is the same function of the data from every tile, and matches the per-chunk plans it replaces. */
class SitePlanTest {
    static final Path FIX = Path.of(System.getProperty("swissworld.fixtures", "../fixtures"));
    static final SwissCoords ORIGIN = new SwissCoords(2_632_900, 1_170_600);

    static ColumnGrid.Cells cells(SwdCell... cs) {
        Map<Long, SwdCell> by = new HashMap<>();
        for (SwdCell c : cs) by.put(((long) c.eKm() << 32) | (c.nKm() & 0xFFFFFFFFL), c);
        return (x, z) -> by.get(((long) SwissCoords.cellKm(ORIGIN.east(x)) << 32)
                | (SwissCoords.cellKm(ORIGIN.north(z)) & 0xFFFFFFFFL));
    }

    static SwdCell fixture() throws IOException {
        return SwdReader.read(FIX.resolve("synthetic_2632_1170.swd"));
    }

    /** Store cells for the real-data checks: SWISSWORLD_SWEEP_STORE (a private clone of the store). */
    static ColumnGrid.Cells store(String... names) throws IOException {
        String dir = System.getenv("SWISSWORLD_SWEEP_STORE");
        if (dir == null || dir.isEmpty()) return null;
        SwdCell[] cs = new SwdCell[names.length];
        for (int i = 0; i < names.length; i++) {
            Path p = Path.of(dir, "cells", names[i] + ".swd");
            if (!Files.isRegularFile(p)) return null;
            cs[i] = SwdReader.read(p);
        }
        return cells(cs);
    }

    @Test
    void neighbouringTilesAgreeOnTheirOverlap() throws IOException {
        ColumnGrid.Cells cells = cells(fixture());
        // the fixture covers x -900..99, z -400..599; tiles (-1,0) and (0,0) share the PAD strip either side of x=0
        SitePlan a = new SitePlan(ORIGIN, cells, -1, 0), b = new SitePlan(ORIGIN, cells, 0, 0);
        int differ = 0;
        for (int z = 0; z < SitePlan.TILE; z++) {
            for (int x = -SitePlan.PAD; x < SitePlan.PAD; x++) {
                if (a.claim(x, z) != b.claim(x, z) || a.ground(x, z) != b.ground(x, z)) differ++;
            }
        }
        assertEquals(0, differ);
    }

    @Test
    void distanceMatchesBruteForce() throws IOException {
        SitePlan p = new SitePlan(ORIGIN, cells(fixture()), -1, 0);
        Map<Claim, Integer> seen = new EnumMap<>(Claim.class);
        for (Claim c : Claim.values()) {
            for (int z = p.minZ(); z < p.minZ() + SitePlan.TILE; z += 3) {
                for (int x = p.minX(); x < p.minX() + SitePlan.TILE; x += 3) {
                    int best = SitePlan.FAR;
                    for (int dz = -SitePlan.PAD; dz <= SitePlan.PAD; dz++) {
                        for (int dx = -SitePlan.PAD; dx <= SitePlan.PAD; dx++) {
                            if (p.claim(x + dx, z + dz) == c) best = Math.min(best, Math.max(Math.abs(dx), Math.abs(dz)));
                        }
                    }
                    assertEquals(best, p.distanceTo(c, x, z), c + " at " + x + "," + z);
                    if (best == 0) seen.merge(c, 1, Integer::sum);
                }
            }
        }
        assertTrue(seen.getOrDefault(Claim.FREE, 0) > 0, "fixture has free ground: " + seen);
    }

    /** The tile's grid and transport plan answer exactly as a chunk's own grid and plan did. */
    static void sameAsPerChunk(SwissCoords o, ColumnGrid.Cells cells, int tx, int tz, int chunks) {
        SitePlan p = new SitePlan(o, cells, tx, tz);
        ColumnGrid tg = p.grid();
        TransportPlan tt = p.transport();
        int checked = 0;
        for (int c = 0; c < chunks; c++) {
            int cx = p.minX() + 16 * ((c * 5) % 8), cz = p.minZ() + 16 * ((c * 3) % 8);
            ColumnGrid g = new ColumnGrid(o, cells, cx, cz, TransportPlan.MARGIN);
            TransportPlan t = new TransportPlan(g);
            for (int z = cz; z < cz + 16; z++) {
                for (int x = cx; x < cx + 16; x++) {
                    String at = x + "," + z;
                    assertEquals(g.top(x, z), tg.top(x, z), "top " + at);
                    assertEquals(g.deck(x, z), tg.deck(x, z), "deck " + at);
                    assertEquals(t.deck(x, z), tt.deck(x, z), "t.deck " + at);
                    assertEquals(t.earthwork(x, z), tt.earthwork(x, z), "earthwork " + at);
                    assertEquals(t.railWay(x, z), tt.railWay(x, z), "railWay " + at);
                    assertEquals(t.way(x, z), tt.way(x, z), "way " + at);
                    assertEquals(t.structure(x, z), tt.structure(x, z), "structure " + at);
                    StringBuilder sa = new StringBuilder(), sb = new StringBuilder();
                    t.build(x, z, (y, part) -> sa.append(y).append(part).append(';'));
                    tt.build(x, z, (y, part) -> sb.append(y).append(part).append(';'));
                    assertEquals(sa.toString(), sb.toString(), "build " + at);
                    checked++;
                }
            }
        }
        assertTrue(checked > 0);
    }

    @Test
    void tileGridMatchesChunkGridOnFixture() throws IOException {
        sameAsPerChunk(ORIGIN, cells(fixture()), -1, 0, 8);
    }

    /** Interlaken Ost rail yard and the Aare bridge on real data (needs SWISSWORLD_SWEEP_STORE). */
    @Test
    void tileGridMatchesChunkGridOnRealCells() throws IOException {
        ColumnGrid.Cells cells = store("2632_1171", "2633_1171");
        org.junit.jupiter.api.Assumptions.assumeTrue(cells != null, "SWISSWORLD_SWEEP_STORE not set");
        sameAsPerChunk(ORIGIN, cells, SitePlan.tile(23), SitePlan.tile(-561), 64);
        sameAsPerChunk(ORIGIN, cells, SitePlan.tile(1028), SitePlan.tile(-865), 64);
    }
}
