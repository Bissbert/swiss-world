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
}
