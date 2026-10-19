package ch.bissbert.swissworld.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.net.SidecarClient.Place;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpSidecarClientPlacesTest {
    @Test
    void parsesResultsAndSkipsIncompleteOnes() {
        List<Place> l = HttpSidecarClient.parsePlaces(JsonParser.parseString("""
                {"results":[
                  {"name":"Genève","kind":"city","e":2500226,"n":1118241,"h":376,"matched":"Genf"},
                  {"name":"Grimselpass","kind":"pass","e":2668880,"n":1157032,"h":null},
                  {"name":"broken","kind":"pass","e":2668880},
                  {"name":"Nameless kind","e":1,"n":2}
                ]}""").getAsJsonObject());
        assertEquals(3, l.size());
        assertEquals(new Place("Genève", "city", 2500226, 1118241, 376, "Genf"), l.get(0));
        assertTrue(Double.isNaN(l.get(1).h()));
        assertNull(l.get(1).matched());
        assertEquals("place", l.get(2).kind());
        assertEquals(List.of(), HttpSidecarClient.parsePlaces(JsonParser.parseString("{}").getAsJsonObject()));
    }
}
