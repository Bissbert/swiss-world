package ch.bissbert.swissworld.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerPrefetchTest {
    private static final double REACH = 192 + PlayerPrefetch.GEN_MARGIN_M;

    private static String ids(List<int[]> cells) {
        StringBuilder b = new StringBuilder();
        for (int[] c : cells) b.append(c[0]).append('_').append(c[1]).append(' ');
        return b.toString().trim();
    }

    @Test
    void standingStillAsksForNothing() {
        assertTrue(PlayerPrefetch.aheadCells(2_632_500, 1_170_500, 0.5, 0, REACH).isEmpty());
    }

    @Test
    void eastboundGetsTheCellsToTheEastNearestFirst() {
        // footprint 2632..2632 x 1170..1170 (500 +- 228 m); ring ahead is column 2633, rows 1169..1171 within 60 degrees
        List<int[]> a = PlayerPrefetch.aheadCells(2_632_500, 1_170_500, 30, 0, REACH);
        assertEquals("2633_1170 2633_1169 2633_1171", ids(a));
    }

    @Test
    void northwestboundDoesNotLookEastOrSouth() {
        List<int[]> a = PlayerPrefetch.aheadCells(2_632_500, 1_170_500, -20, 20, REACH);
        for (int[] c : a) assertTrue(c[0] <= 2632 && c[1] >= 1170, ids(a));
        assertTrue(a.stream().anyMatch(c -> c[0] == 2631 && c[1] == 1171), ids(a));
    }

    @Test
    void neverReturnsTheFootprintItself() {
        // at the corner of four cells the footprint is 2x2, and the ring starts beyond it
        List<int[]> a = PlayerPrefetch.aheadCells(2_633_000, 1_171_000, 0, 25, REACH);
        for (int[] c : a) assertTrue(c[1] >= 1172 || c[1] <= 1169, ids(a));
        assertTrue(a.size() <= PlayerPrefetch.MAX_AHEAD_CELLS);
    }

    @Test
    void sameInputSameOutput() {
        assertEquals(ids(PlayerPrefetch.aheadCells(2_640_123, 1_165_987, 17, -23, REACH)),
                ids(PlayerPrefetch.aheadCells(2_640_123, 1_165_987, 17, -23, REACH)));
    }
}
