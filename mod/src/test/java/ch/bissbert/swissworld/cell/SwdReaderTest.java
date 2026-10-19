package ch.bissbert.swissworld.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class SwdReaderTest {
    private static final Path FIX = Path.of(System.getProperty("swissworld.fixtures", "../fixtures"));

    @Test
    void readsGoldenFixture() throws Exception {
        SwdCell cell = SwdReader.read(FIX.resolve("synthetic_2632_1170.swd"));
        JsonObject exp = JsonParser.parseString(Files.readString(FIX.resolve("synthetic_2632_1170.expected.json"))).getAsJsonObject();

        assertEquals(exp.get("eKm").getAsInt(), cell.eKm());
        assertEquals(exp.get("nKm").getAsInt(), cell.nKm());
        assertNotNull(cell.insd());

        for (JsonElement e : exp.getAsJsonArray("terrNodes")) {
            JsonObject n = e.getAsJsonObject();
            assertEquals(n.get("h").getAsDouble(), cell.node(n.get("i").getAsInt(), n.get("j").getAsInt()), 1e-9);
        }
        for (JsonElement e : exp.getAsJsonArray("columns")) {
            JsonObject c = e.getAsJsonObject();
            int dx = c.get("dx").getAsInt(), dz = c.get("dz").getAsInt();
            assertEquals(c.get("surf").getAsInt(), cell.surf(dx, dz));
            if (c.get("wlvl").isJsonNull()) assertTrue(Double.isNaN(cell.water(dx, dz)));
            else assertEquals(c.get("wlvl").getAsDouble(), cell.water(dx, dz), 1e-9);
            assertEquals(c.get("wknd").getAsInt(), cell.waterKind(dx, dz));
            assertEquals(c.get("ocls").getAsInt(), cell.overlay(dx, dz));
            assertEquals(c.get("oflg").getAsInt(), cell.overlayFlags(dx, dz));
            assertEquals(c.get("insd").getAsInt() != 0, cell.inside(dx, dz));
            if (c.has("oelv")) assertEquals(c.get("oelv").getAsDouble(), cell.overlayElevation(dx, dz), 1e-9);
        }

        JsonObject b = exp.getAsJsonArray("buildings").get(0).getAsJsonObject();
        SwdCell.Building bt = cell.buildings().get(0);
        assertEquals(Long.parseUnsignedLong(b.get("gid").getAsString()), bt.gid());
        assertEquals(b.get("egid").getAsLong(), bt.egid());
        assertEquals(b.get("gwrClass").getAsInt(), bt.gwrClass());
        assertEquals(b.get("floors").getAsInt(), bt.floors());
        assertEquals(b.get("baseH").getAsDouble(), Heights.toMetres(bt.baseH()), 1e-9);
        assertEquals(b.get("topH").getAsDouble(), Heights.toMetres(bt.topH()), 1e-9);
        assertEquals(exp.get("spanColumns").getAsInt(), cell.spans().size());

        JsonObject p = exp.getAsJsonObject("spanProbe");
        SwdCell.Span s = cell.spansAt(p.get("dx").getAsInt(), p.get("dz").getAsInt())[0];
        assertEquals(p.get("bottom").getAsDouble(), Heights.toMetres(s.bottomH()), 1e-9);
        assertEquals(p.get("top").getAsDouble(), Heights.toMetres(s.topH()), 1e-9);
        assertEquals(p.get("roofFlags").getAsInt(), s.roofFlags());
    }

    @Test
    void coordinatesRoundTrip() {
        SwissCoords c = new SwissCoords(2_632_900, 1_170_600);
        // block (0,0) centre is E 2632900.5 / N 1170599.5 -> cell 2632_1170, dx 900, dz 400
        assertEquals(2632, SwissCoords.cellKm(c.east(0)));
        assertEquals(1170, SwissCoords.cellKm(c.north(0)));
        assertEquals(900, SwissCoords.dx(c.east(0)));
        assertEquals(400, SwissCoords.dz(c.north(0)));
        assertEquals(-1807, SwissCoords.toY(193), 1e-9);
        assertEquals(1000, SwissCoords.toY(3000), 1e-9);
        assertEquals(4634, SwissCoords.toMetres(SwissCoords.toY(4634)), 1e-9);
    }

    @Test
    void readsCanopyAndTrees() throws Exception {
        byte[] cnpy = new byte[SwdCell.SIZE * SwdCell.SIZE];
        cnpy[SwdCell.key(10, 20)] = (byte) 31;
        cnpy[SwdCell.key(999, 999)] = (byte) 250;
        ByteBuffer ttop = ByteBuffer.allocate(4 + 2 * 8).order(ByteOrder.LITTLE_ENDIAN);
        ttop.putInt(2);
        ttop.putShort((short) 10).putShort((short) 20).put((byte) 31).put((byte) 45).put((byte) 1).put((byte) 0);
        ttop.putShort((short) 999).putShort((short) 999).put((byte) 250).put((byte) 0).put((byte) 3).put((byte) 0);
        SwdCell cell = SwdReader.decode(swd(2632, 1170,
                "TERR", new byte[SwdCell.TERR_N * SwdCell.TERR_N * 2], "SURF", new byte[cnpy.length],
                "CNPY", cnpy, "TTOP", ttop.array(), "ZZZZ", new byte[] {1, 2, 3}));
        assertTrue(cell.hasCanopy());
        assertEquals(31, cell.canopy(10, 20));
        assertEquals(250, cell.canopy(999, 999));
        assertEquals(0, cell.canopy(0, 0));
        assertTrue(cell.hasTrees());
        assertEquals(List.of(new SwdCell.Tree(10, 20, 31, 45, SwdCell.Tree.CONIFER),
                new SwdCell.Tree(999, 999, 250, 0, SwdCell.Tree.LARCH)), cell.trees());
    }

    @Test
    void emptyTreeTableStillCountsAsPresent() throws Exception {
        byte[] none = new byte[4];
        SwdCell cell = SwdReader.decode(swd(2632, 1170,
                "TERR", new byte[SwdCell.TERR_N * SwdCell.TERR_N * 2], "SURF", new byte[SwdCell.SIZE * SwdCell.SIZE],
                "TTOP", none));
        assertTrue(cell.hasTrees());
        assertTrue(cell.trees().isEmpty());
        assertFalse(cell.hasCanopy());
        assertEquals(0, cell.canopy(5, 5));
    }

    @Test
    void fixtureCanopyAndTreesMatchSidecar() throws Exception {
        SwdCell cell = SwdReader.read(FIX.resolve("synthetic_2632_1170.swd"));
        assertTrue(cell.hasCanopy());
        assertTrue(cell.hasTrees());
        assertEquals(18, cell.canopy(550, 10));
        assertEquals(0, cell.canopy(10, 900));
        assertEquals(java.util.Set.of(new SwdCell.Tree(520, 40, 31, 35, SwdCell.Tree.CONIFER),
                new SwdCell.Tree(575, 12, 24, 28, SwdCell.Tree.BROADLEAF),
                new SwdCell.Tree(590, 600, 2, 0, SwdCell.Tree.UNKNOWN)), java.util.Set.copyOf(cell.trees()));
    }

    /** A minimal .swd: layers given as tag, bytes pairs; even layers zlib, odd ones raw, so both codecs are read. */
    private static byte[] swd(int eKm, int nKm, Object... layers) {
        int count = layers.length / 2;
        ByteArrayOutputStream blobs = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(24 + count * 32).order(ByteOrder.LITTLE_ENDIAN);
        head.put("SWD1".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1).putShort((short) 0)
                .putInt(eKm).putInt(nKm).putInt(count).putInt(0);
        for (int i = 0; i < count; i++) {
            byte[] raw = (byte[]) layers[2 * i + 1];
            byte[] stored = i % 2 == 0 ? deflate(raw) : raw;
            head.put(((String) layers[2 * i]).getBytes(StandardCharsets.US_ASCII)).putInt(i % 2 == 0 ? 1 : 0)
                    .putLong(head.capacity() + blobs.size()).putLong(stored.length).putLong(raw.length);
            blobs.writeBytes(stored);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head.array());
        out.writeBytes(blobs.toByteArray());
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater();
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }
}
