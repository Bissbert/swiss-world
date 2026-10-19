package ch.bissbert.swissworld.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SwissBiomeTest {
    private static final Path FIX = Path.of(System.getProperty("swissworld.fixtures", "../fixtures"));

    @Test
    void waterKindWinsOverLandCover() {
        assertEquals(SwissBiome.LAKE, SwissBiome.classify(2, 1, 600));
        assertEquals(SwissBiome.LAKE, SwissBiome.classify(0, 4, 600));
        assertEquals(SwissBiome.RIVER, SwissBiome.classify(0, 3, 600));
        assertEquals(SwissBiome.RIVER, SwissBiome.classify(4, 6, 2500));
    }

    @Test
    void forestBeltsByAltitude() {
        assertEquals(SwissBiome.COLLINE_FOREST, SwissBiome.classify(1, 0, 600));
        assertEquals(SwissBiome.MONTANE_FOREST, SwissBiome.classify(2, 0, 1200));
        assertEquals(SwissBiome.SUBALPINE_FOREST, SwissBiome.classify(3, 0, 1900));
    }

    @Test
    void openLandByAltitudeAndCover() {
        assertEquals(SwissBiome.COLLINE, SwissBiome.classify(0, 0, 600));
        assertEquals(SwissBiome.ALPINE_MEADOW, SwissBiome.classify(0, 0, 2000));
        assertEquals(SwissBiome.ALPINE_MEADOW, SwissBiome.classify(17, 0, 1400));
        assertEquals(SwissBiome.GLACIER, SwissBiome.classify(6, 0, 3000));
        assertEquals(SwissBiome.URBAN, SwissBiome.classify(10, 0, 560));
        assertEquals(SwissBiome.ROCK, SwissBiome.classify(4, 0, 2800));
        assertEquals(SwissBiome.SCREE, SwissBiome.classify(5, 0, 2400));
    }

    @Test
    void fixtureColumns() throws Exception {
        SwdCell cell = SwdReader.read(FIX.resolve("synthetic_2632_1170.swd"));
        assertEquals(SwissBiome.OCEAN, SwissBiome.classify(cell, 995, 10)); // INSD 0 at dx >= 990
        assertEquals(SwissBiome.COLLINE_FOREST, SwissBiome.classify(cell, 550, 10)); // conifer strip, ~650 m
        assertEquals(SwissBiome.LAKE, SwissBiome.classify(cell, 50, 900));
    }
}
