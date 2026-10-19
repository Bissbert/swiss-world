package ch.bissbert.swissworld.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.junit.jupiter.api.Test;

/** Unit tests run without mixins, so {@link PalettedContainer#pack} here is vanilla's. */
class SectionPackingTest {
    /** A registry of 300 states (above the 8-bit palette limit) and 64 "biomes". */
    private static final Ids STATES = new Ids(300), BIOMES = new Ids(64);

    @Test
    void singleValueMatchesVanilla() {
        for (String v : List.of("s0", "s7", "s299")) {
            assertSame(new PalettedContainer<>(STATES, v, PalettedContainer.Strategy.SECTION_STATES),
                    STATES, PalettedContainer.Strategy.SECTION_STATES);
        }
        assertSame(new PalettedContainer<>(BIOMES, "s3", PalettedContainer.Strategy.SECTION_BIOMES),
                BIOMES, PalettedContainer.Strategy.SECTION_BIOMES);
    }

    @Test
    void refilledContainerIsLeftToVanilla() {
        PalettedContainer<String> c = new PalettedContainer<>(STATES, "s0", PalettedContainer.Strategy.SECTION_STATES);
        for (int i = 0; i < 4096; i++) c.getAndSetUnchecked(i & 15, i >> 8, (i >> 4) & 15, "s1");
        PalettedContainerRO.PackedData<String> vanilla = c.pack(STATES, PalettedContainer.Strategy.SECTION_STATES);
        PalettedContainerRO.PackedData<String> fast = SectionPacking.packSingleValue(c);
        // vanilla keeps the linear palette [s0, s1]; that is left to vanilla, which then packs it as [s1]
        assertNull(SectionPacking.packSingleValue(c));
        assertEquals(List.of("s1"), vanilla.paletteEntries());
        assertEquals(java.util.Optional.empty(), vanilla.storage().map(s -> s.toArray().length));
    }

    @Test
    void mixedContainerIsLeftToVanilla() {
        PalettedContainer<String> c = new PalettedContainer<>(STATES, "s0", PalettedContainer.Strategy.SECTION_STATES);
        c.getAndSetUnchecked(3, 4, 5, "s9");
        assertNull(SectionPacking.packSingleValue(c));
        // back to one value, but in a linear palette with two entries: still vanilla's job
        c.getAndSetUnchecked(3, 4, 5, "s0");
        assertNull(SectionPacking.packSingleValue(c));
    }

    private static void assertSame(PalettedContainer<String> c, Ids ids, PalettedContainer.Strategy strategy) {
        PalettedContainerRO.PackedData<String> fast = SectionPacking.packSingleValue(c);
        assertNotNull(fast);
        PalettedContainerRO.PackedData<String> vanilla = c.pack(ids, strategy);
        assertEquals(vanilla.paletteEntries(), fast.paletteEntries());
        assertArrayEquals(vanilla.storage().map(s -> s.toArray()).orElse(null),
                fast.storage().map(s -> s.toArray()).orElse(null));
        // and the serialised form the chunk codec writes
        Codec<PalettedContainer<String>> codec = PalettedContainer.codecRW(ids, Codec.STRING, strategy, "s0");
        assertEquals(codec.encodeStart(JsonOps.INSTANCE, c).getOrThrow(), json(fast));
    }

    /** What the container codec writes for packed data: {palette: [...], data: [...]?}. */
    private static com.google.gson.JsonElement json(PalettedContainerRO.PackedData<String> p) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        com.google.gson.JsonArray pal = new com.google.gson.JsonArray();
        p.paletteEntries().forEach(pal::add);
        o.add("palette", pal);
        p.storage().ifPresent(s -> {
            com.google.gson.JsonArray d = new com.google.gson.JsonArray();
            s.forEach(d::add);
            o.add("data", d);
        });
        return o;
    }

    private record Ids(int n) implements IdMap<String> {
        @Override
        public int getId(String value) {
            return Integer.parseInt(value.substring(1));
        }

        @Override
        public String byId(int id) {
            return id >= 0 && id < n ? "s" + id : null;
        }

        @Override
        public int size() {
            return n;
        }

        @Override
        public Iterator<String> iterator() {
            return IntStream.range(0, n).mapToObj(i -> "s" + i).iterator();
        }
    }
}
