package ch.bissbert.swissworld.gen;

import com.mojang.serialization.MapCodec;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** Biomes from land cover and altitude. Owner: mod-core. Stub: one biome everywhere. */
public final class SwissBiomeSource extends BiomeSource {
    public static final MapCodec<SwissBiomeSource> CODEC =
            Biome.CODEC.fieldOf("default_biome").xmap(SwissBiomeSource::new, s -> s.defaultBiome);

    private final Holder<Biome> defaultBiome;

    public SwissBiomeSource(Holder<Biome> defaultBiome) {
        this.defaultBiome = defaultBiome;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(defaultBiome);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        return defaultBiome;
    }
}
