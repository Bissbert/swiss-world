package ch.bissbert.swissworld.gen;

import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.SwissWorld;
import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.Coverage;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Biomes from land cover and altitude ({@link SwissBiome}). Owner: mod-core.
 *
 * <p>Biomes are per column (the same at every y). Generated chunks get theirs from {@link #resolver}, which reads the
 * chunk's loaded cells. {@link #getNoiseBiome} is for everything else (e.g. {@code /locate}, spawn checks on
 * ungenerated positions): non-blocking, the cell if it is in memory, else a guess from y.
 */
public final class SwissBiomeSource extends BiomeSource {
    public static final MapCodec<SwissBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            RegistryOps.retrieveGetter(Registries.BIOME)
    ).apply(i, i.stable(SwissBiomeSource::new)));

    private final Holder<Biome>[] biomes;

    @SuppressWarnings("unchecked")
    public SwissBiomeSource(HolderGetter<Biome> getter) {
        SwissBiome[] all = SwissBiome.values();
        biomes = new Holder[all.length];
        for (SwissBiome b : all) biomes[b.ordinal()] = getter.getOrThrow(key(b));
    }

    public static ResourceKey<Biome> key(SwissBiome b) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(SwissWorld.MOD_ID, b.id()));
    }

    public Holder<Biome> holder(SwissBiome b) {
        return biomes[b.ordinal()];
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Arrays.stream(biomes);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int bx = QuartPos.toBlock(x) + 2, bz = QuartPos.toBlock(z) + 2;
        SwissCoords c = SwissRuntime.coords();
        long e = c.east(bx), n = c.north(bz);
        int eKm = SwissCoords.cellKm(e), nKm = SwissCoords.cellKm(n);
        CellCache cells = SwissRuntime.cells();
        SwdCell cell = cells.getIfPresent(eKm, nKm);
        if (cell != null) return holder(SwissBiome.classify(cell, SwissCoords.dx(e), SwissCoords.dz(n)));
        if (cells.coverageState(eKm, nKm) == Coverage.OUTSIDE) return holder(SwissBiome.OCEAN);
        return holder(SwissBiome.fallback(SwissCoords.toMetres(QuartPos.toBlock(y))));
    }

    /** For a chunk being generated: biomes from its cells (all loaded), quart centres. */
    public BiomeResolver resolver(GenContext ctx) {
        return (x, y, z, sampler) -> {
            int bx = QuartPos.toBlock(x) + 2, bz = QuartPos.toBlock(z) + 2;
            SwdCell cell = ctx.cellAt(bx, bz);
            if (cell == null) return holder(SwissBiome.OCEAN);
            return holder(SwissBiome.classify(cell, SwissCoords.dx(ctx.coords.east(bx)), SwissCoords.dz(ctx.coords.north(bz))));
        };
    }
}
