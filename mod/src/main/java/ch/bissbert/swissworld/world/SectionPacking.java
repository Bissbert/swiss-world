package ch.bissbert.swissworld.world;

import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;

/**
 * Fast path for saving single-value sections. Owner: mod-core.
 *
 * <p>A Switzerland chunk has 254 sections, and ~98 % of their block containers (87 % of the biome ones) hold a
 * single value. Vanilla's {@link PalettedContainer#pack} still unpacks 4096 ids into a new array, remaps them and
 * builds a HashMapPalette for each, ~6 µs per container, which made saving a Swiss chunk ~10× as expensive as a
 * vanilla one. For a single-value palette its result is always one palette entry and no data (zero bits), which is
 * returned here directly; {@code PalettedContainerMixin} calls this first. Off with
 * {@code -Dswissworld.vanillaPack=true} (A/B checks).
 */
public final class SectionPacking {
    public static final boolean ENABLED = !Boolean.getBoolean("swissworld.vanillaPack");

    private SectionPacking() {}

    /** The packed form of a single-value container, exactly as vanilla packs it; null for any other container. */
    @Nullable
    public static <T> PalettedContainerRO.PackedData<T> packSingleValue(PalettedContainer<T> container) {
        container.acquire();
        try {
            if (!(container.data.palette() instanceof SingleValuePalette<T> single)) return null;
            return new PalettedContainerRO.PackedData<>(List.of(single.valueFor(0)), Optional.empty());
        } finally {
            container.release();
        }
    }
}
