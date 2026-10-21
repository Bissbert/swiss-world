package ch.bissbert.swissworld.gen.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SitePlansAheadTest {
    @Test
    void tilesAheadOfAnEastboundPlayerAreTheNextColumn() {
        // at x 64 (tile 0), view 160: tiles -2..1 seen now; 30 m/s east for 6 s moves the view to 64 + 180
        List<int[]> t = SitePlans.aheadTiles(64, 64, 30, 0, 160);
        assertFalse(t.isEmpty());
        for (int[] a : t) {
            assertTrue(a[0] >= 2, "only tiles east of the current view: " + a[0]);
            assertTrue(a[1] >= -1 && a[1] <= 1, "same rows as the view: " + a[1]);
        }
        // nearest to the projected centre first: the middle row
        assertEquals(0, t.get(0)[1]);
    }

    @Test
    void standingStillPrefetchesNothing() {
        assertTrue(SitePlans.aheadTiles(64, 64, 0, 0, 160).isEmpty());
    }

    @Test
    void pure() {
        List<int[]> a = SitePlans.aheadTiles(-1000.5, 2000.25, -12, 21, 160), b = SitePlans.aheadTiles(-1000.5, 2000.25, -12, 21, 160);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertEquals(List.of(a.get(i)[0], a.get(i)[1]), List.of(b.get(i)[0], b.get(i)[1]));
    }
}
