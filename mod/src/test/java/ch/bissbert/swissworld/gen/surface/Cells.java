package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.Heights;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.cell.SwissCoords;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test cells: the golden fixture, and flat synthetic cells whose layers tests edit column by column. */
final class Cells {
    static final Path FIX = Path.of(System.getProperty("swissworld.fixtures", "../fixtures"));
    static final SwissCoords ORIGIN = new SwissCoords(2_632_900, 1_170_600);

    private Cells() {}

    static SwdCell fixture() throws IOException {
        return SwdReader.read(FIX.resolve("synthetic_2632_1170.swd"));
    }

    /** Grid for the chunk containing (x, z) over a world that has only {@code cell}. */
    static ColumnGrid grid(SwdCell cell, int x, int z) {
        return new ColumnGrid(ORIGIN, (bx, bz) -> {
            long e = ORIGIN.east(bx), n = ORIGIN.north(bz);
            return SwissCoords.cellKm(e) == cell.eKm() && SwissCoords.cellKm(n) == cell.nKm() ? cell : null;
        }, Math.floorDiv(x, 16) * 16, Math.floorDiv(z, 16) * 16);
    }

    /** Flat cell 2632_1170 at {@code h} metres, meadow, every optional layer present and empty. */
    static SwdCell flat(double h) {
        int n = SwdCell.SIZE * SwdCell.SIZE;
        short[] terr = new short[SwdCell.TERR_N * SwdCell.TERR_N];
        Arrays.fill(terr, (short) Heights.encode(h));
        short[] none = new short[n];
        Arrays.fill(none, (short) Heights.NONE);
        Map<Integer, SwdCell.Span[]> spans = new HashMap<>();
        return new SwdCell(2632, 1170, terr, new byte[n], none, new byte[n], new byte[n], none.clone(), new byte[n],
                null, List.of(), spans);
    }

    static int dx(int x) {
        return SwissCoords.dx(ORIGIN.east(x));
    }

    static int dz(int z) {
        return SwissCoords.dz(ORIGIN.north(z));
    }

    /** Sets TERR nodes so that column (x, z) samples at exactly {@code h}: the four nodes around its centre. */
    static void ground(SwdCell c, int x, int z, double h) {
        int i = dx(x) / 2, j = dz(z) / 2;
        for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) c.terr()[(j + b) * SwdCell.TERR_N + i + a] = (short) Heights.encode(h);
    }

    static void water(SwdCell c, int x, int z, double h, int kind) {
        int k = SwdCell.key(dx(x), dz(z));
        c.wlvl()[k] = (short) Heights.encode(h);
        c.wknd()[k] = (byte) kind;
    }

    static void overlay(SwdCell c, int x, int z, int cls, int flags, double elev) {
        int k = SwdCell.key(dx(x), dz(z));
        c.ocls()[k] = (byte) cls;
        c.oflg()[k] = (byte) flags;
        c.oelv()[k] = (short) Heights.encode(elev);
    }

    static void surf(SwdCell c, int x, int z, int surf) {
        c.surf()[SwdCell.key(dx(x), dz(z))] = (byte) surf;
    }
}
