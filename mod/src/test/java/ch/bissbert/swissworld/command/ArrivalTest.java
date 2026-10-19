package ch.bissbert.swissworld.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArrivalTest {
    @Test
    void keepsWaitingWhileProgressing() {
        int start = 1000;
        // two minutes in, but cells arrived a moment ago: no timeout (the old fixed 120 s limit would have fired)
        assertNull(Arrival.givesUp(start + 2 * 60 * 20 + 1, start, start + 2 * 60 * 20 - 100, false));
        // 60 s without anything: give up
        assertEquals("no progress for 60 s", Arrival.givesUp(start + Arrival.NO_PROGRESS_TICKS + 1, start, start, false));
        // ... unless the sidecar still reports a build in progress
        assertNull(Arrival.givesUp(start + Arrival.NO_PROGRESS_TICKS + 1, start, start, true));
        // but never beyond the hard cap, with its own reason
        assertEquals("still not ready after 10 min",
                Arrival.givesUp(start + Arrival.HARD_CAP_TICKS + 1, start, start + Arrival.HARD_CAP_TICKS, true));
    }

    @Test
    void barSaysWhatTheSidecarDoesAndAlwaysMoves() {
        assertEquals("Building terrain… 0/1 cells, queued in the sidecar · 3 s · /swiss back cancels",
                Arrival.bar(0, 1, 0, 9, null, 3, false));
        Arrival.Build step = new Arrival.Build(2600, 1199, "buildings", 6, 8, null, 0, -1);
        assertEquals("Building terrain… cell 1 of 1: buildings (6/8) · 41 s · /swiss back cancels",
                Arrival.bar(0, 1, 0, 9, step, 41, false));
        Arrival.Build dl = new Arrival.Build(2600, 1199, "buildings", 6, 8, "b.zip", 50L << 20, 210L << 20);
        assertEquals("Building terrain… cell 2 of 4: downloading b.zip 50/210 MB · 12 s · /swiss back cancels",
                Arrival.bar(1, 4, 0, 9, dl, 12, false));
        assertEquals("Generating terrain… 6/9 chunks · 97 s · /swiss back cancels", Arrival.bar(4, 4, 6, 9, null, 97, false));
        assertTrue(Arrival.bar(0, 1, 0, 9, null, 5, true).contains("sidecar not reachable"));
        // same state, one second later: different text
        assertTrue(!Arrival.bar(0, 1, 0, 9, step, 41, false).equals(Arrival.bar(0, 1, 0, 9, step, 42, false)));
    }

    @Test
    void parsesStatusBuilds() {
        com.google.gson.JsonObject st = com.google.gson.JsonParser.parseString("""
                {"queue": {"now": 0, "prefetch": 0, "building": 2}, "builds": [
                 {"cell": [2600, 1199], "stage": "alti", "step": 1, "steps": 8, "elapsedS": 2.5},
                 {"cell": [2601, 1199], "stage": "buildings", "step": 6, "steps": 8, "elapsedS": 40,
                  "download": {"file": "b.zip", "bytes": 10, "totalBytes": null}},
                 {"cell": "garbage"}]}""").getAsJsonObject();
        java.util.List<Arrival.Build> b = Arrival.builds(st);
        assertEquals(2, b.size());
        assertEquals(new Arrival.Build(2600, 1199, "alti", 1, 8, null, 0, -1), b.get(0));
        assertEquals(new Arrival.Build(2601, 1199, "buildings", 6, 8, "b.zip", 10, -1), b.get(1));
        assertTrue(Arrival.builds(com.google.gson.JsonParser.parseString("{\"queue\": {}}").getAsJsonObject()).isEmpty());
    }

    @Test
    void openColumnRejectsRoofsWaterAndFootprints() {
        assertTrue(Arrival.openColumn(450, false, false, 449.6));
        assertTrue(Arrival.openColumn(451, false, false, 449.6)); // road surface, kerb
        assertFalse(Arrival.openColumn(462, false, false, 449.6)); // roof, eave, trunk
        assertFalse(Arrival.openColumn(449, true, false, 449.6)); // water
        assertFalse(Arrival.openColumn(449, false, true, 449.6)); // inside a footprint
        assertFalse(Arrival.openColumn(449, false, false, Double.NaN)); // no cell
    }

    @Test
    void nearestOpenPrefersTheTargetThenTheClosestStreet() {
        assertArrayEquals(new int[] {5, 7}, Arrival.nearestOpen(5, 7, 32, (x, z) -> true));
        // a 21x21 building around the origin; the street starts at x = 11 or z = -11 (closest: straight out, dist 11)
        int[] at = Arrival.nearestOpen(0, 0, 32, (x, z) -> Math.abs(x) > 10 || Math.abs(z) > 10);
        assertEquals(11, Math.abs(at[0]) + Math.abs(at[1]));
        assertTrue(at[0] == 0 || at[1] == 0);
        // the street east of the building is nearer than the one in the north
        assertArrayEquals(new int[] {4, 0}, Arrival.nearestOpen(0, 0, 32, (x, z) -> x >= 4 || z <= -9));
        // nothing open within the radius: null (the caller lands on the roof)
        assertNull(Arrival.nearestOpen(0, 0, 32, (x, z) -> Math.abs(x) > 40));
        assertNull(Arrival.nearestOpen(0, 0, 32, (x, z) -> x * x + z * z > 33 * 33));
    }
}
