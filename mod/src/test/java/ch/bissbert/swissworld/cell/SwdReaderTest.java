package ch.bissbert.swissworld.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
