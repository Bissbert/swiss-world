package ch.bissbert.swissworld.cell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Wgs84Test {
    @Test
    void bernProjectionCentre() {
        double[] ll = Wgs84.fromLv95(2_600_000, 1_200_000);
        assertEquals(46.95108, ll[0], 1e-5);
        assertEquals(7.43864, ll[1], 1e-5);
    }

    @Test
    void swisstopoWorkedExample() {
        // swisstopo's example: E 2'700'000 / N 1'100'000 -> 46°02'38.87" N, 8°43'49.79" E
        double[] ll = Wgs84.fromLv95(2_700_000, 1_100_000);
        assertEquals(46 + 2 / 60.0 + 38.87 / 3600, ll[0], 2e-5);
        assertEquals(8 + 43 / 60.0 + 49.79 / 3600, ll[1], 2e-5);
    }
}
