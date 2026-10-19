package ch.bissbert.swissworld.cell;

/**
 * LV95 -> WGS84 with swisstopo's approximate formulas ("Approximate solution for the transformation CH1903+ <->
 * WGS84"), accurate to about 1 m, which is all {@code /swiss where} needs. Owner: mod-core.
 */
public final class Wgs84 {
    private Wgs84() {}

    /** @return {latitude, longitude} in degrees */
    public static double[] fromLv95(double e, double n) {
        double y = (e - 2_600_000) / 1e6, x = (n - 1_200_000) / 1e6;
        double lon = 2.6779094 + 4.728982 * y + 0.791484 * y * x + 0.1306 * y * x * x - 0.0436 * y * y * y;
        double lat = 16.9023892 + 3.238272 * x - 0.270978 * y * y - 0.002528 * x * x - 0.0447 * y * y * x - 0.0140 * x * x * x;
        return new double[] {lat * 100 / 36, lon * 100 / 36};
    }

    /** @return {E, N} in LV95 metres */
    public static double[] toLv95(double lat, double lon) {
        double p = (lat * 3600 - 169_028.66) / 10_000, l = (lon * 3600 - 26_782.5) / 10_000;
        double e = 2_600_072.37 + 211_455.93 * l - 10_938.51 * l * p - 0.36 * l * p * p - 44.54 * l * l * l;
        double n = 1_200_147.07 + 308_807.95 * p + 3_745.25 * l * l + 76.63 * p * p - 194.56 * l * l * p + 119.79 * p * p * p;
        return new double[] {e, n};
    }
}
