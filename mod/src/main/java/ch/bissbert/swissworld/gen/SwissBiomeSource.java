package ch.bissbert.swissworld.gen;

import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.SwissWorld;
import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.Coverage;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.ground.ClimateLines;
import ch.bissbert.swissworld.gen.ground.GroundTextureStage;
import ch.bissbert.swissworld.gen.ground.Snow;
import ch.bissbert.swissworld.gen.ground.Zone;
import ch.bissbert.swissworld.gen.ground.ZoneMap;
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
        if (cell != null) {
            int dx = SwissCoords.dx(e), dz = SwissCoords.dz(n);
            // no neighbours here: snowy if well above the regional snow line and dry
            if (cell.inside(dx, dz) && Double.isNaN(cell.water(dx, dz)) && cell.surf(dx, dz) != 4
                    && cell.terrain(dx + 0.5, dz + 0.5) > ClimateLines.snowLine(e, n) + Snow.BAND) {
                return holder(SwissBiome.SNOWFIELD);
            }
            return holder(SwissBiome.classify(cell, dx, dz));
        }
        if (cells.coverageState(eKm, nKm) == Coverage.OUTSIDE) return holder(SwissBiome.OCEAN);
        return holder(SwissBiome.fallback(SwissCoords.toMetres(QuartPos.toBlock(y))));
    }

    /**
     * For a chunk being generated: biomes from its cells (all loaded), quart centres. A quart where the ground zones lay
     * snow on at least half the columns is {@link SwissBiome#SNOWFIELD}, unless any of its columns holds water: a cold
     * biome would freeze the sources there, and a frozen river source drains the water it feeds.
     */
    public BiomeResolver resolver(GenContext ctx) {
        // Biomes are y-independent, so a chunk has 16 distinct answers (4x4 quarts) but the fill asks once per section
        // and quart (254 x 64 calls). One resolver serves one chunk on one thread: a plain array memo, indexed by the
        // quart's column in the chunk.
        @SuppressWarnings("unchecked")
        Holder<Biome>[] memo = new Holder[16];
        return (x, y, z, sampler) -> {
            int i = (z & 3) * 4 + (x & 3);
            Holder<Biome> hit = memo[i];
            if (hit == null) memo[i] = hit = resolve(ctx, x, z);
            return hit;
        };
    }

    private Holder<Biome> resolve(GenContext ctx, int x, int z) {
        int bx = QuartPos.toBlock(x) + 2, bz = QuartPos.toBlock(z) + 2;
        SwdCell cell = ctx.cellAt(bx, bz);
        if (cell == null) return holder(SwissBiome.OCEAN);
        ZoneMap zm = GroundTextureStage.zones(ctx);
        int qx = QuartPos.toBlock(x) - zm.x0, qz = QuartPos.toBlock(z) - zm.z0;
        if (qx >= 0 && qz >= 0 && qx < 16 && qz < 16 && snowfield(zm, qx, qz)) return holder(SwissBiome.SNOWFIELD);
        return holder(SwissBiome.classify(cell, SwissCoords.dx(ctx.coords.east(bx)), SwissCoords.dz(ctx.coords.north(bz))));
    }

    /** At least half the quart's columns snowy, none wet. Local quart corner (qx, qz). */
    public static boolean snowfield(ZoneMap zm, int qx, int qz) {
        if (zm.snowyColumns(qx, qz) < 8) return false;
        for (int dz = 0; dz < 4; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                int x = zm.x0 + qx + dx, z = zm.z0 + qz + dz;
                if (zm.zone(x, z) == Zone.WATER || zm.waterDist(x, z) == 0) return false;
            }
        }
        return true;
    }
}
