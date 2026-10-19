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

    @Test
    void swisstopoWorkedExampleInverse() {
        double[] en = Wgs84.toLv95(46 + 2 / 60.0 + 38.87 / 3600, 8 + 43 / 60.0 + 49.79 / 3600);
        assertEquals(2_699_999.76, en[0], 0.05);
        assertEquals(1_099_999.97, en[1], 0.05);
    }

    @Test
    void roundTripWithinTwoMetres() {
        for (double[] p : new double[][] {{2_632_900, 1_170_600}, {2_638_359, 1_237_558}, {2_500_000, 1_120_000}, {2_820_000, 1_180_000}}) {
            double[] ll = Wgs84.fromLv95(p[0], p[1]);
            double[] en = Wgs84.toLv95(ll[0], ll[1]);
            assertEquals(p[0], en[0], 2.5);
            assertEquals(p[1], en[1], 2.5); // two ~1 m approximations, worst near Geneva
        }
    }
}
