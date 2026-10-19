package ch.bissbert.swissworld.gen;

import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.building.BuildingStage;
import ch.bissbert.swissworld.gen.building.FurnishStage;
import ch.bissbert.swissworld.gen.building.LootStage;
import ch.bissbert.swissworld.gen.stage.TerrainStage;
import ch.bissbert.swissworld.gen.surface.OverlayStage;
import ch.bissbert.swissworld.gen.surface.VegetationStage;
import ch.bissbert.swissworld.gen.surface.WaterStage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Generates the Switzerland dimension from .swd cells. Owner: mod-core.
 * Skeleton: loads the chunk's cells that already exist in the store (async), then runs the pipeline.
 * Missing cells generate as ocean. M1 (mod-core) asks the sidecar for missing cells and waits for them instead,
 * so a placeholder is never generated or saved inside coverage.
 */
public final class SwissChunkGenerator extends ChunkGenerator {
    public static final MapCodec<SwissChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource)
    ).apply(i, i.stable(SwissChunkGenerator::new)));

    /** Fixed order. Each workstream owns its stages; add new stages here only in agreement with mod-core. */
    public static final List<GenStage> PIPELINE = List.of(
            new TerrainStage(),
            new WaterStage(),
            new OverlayStage(),
            new BuildingStage(),
            new FurnishStage(),
            new LootStage(),
            new VegetationStage());

    /** Real sea level 193 m (lowest point of Switzerland) -> y = -1807. Ocean outside the border. */
    public static final int SEA_LEVEL_Y = (int) SwissCoords.toY(193);

    public SwissChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        SwissCoords coords = SwissRuntime.coords();
        CellCache cache = SwissRuntime.cells();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        Map<Long, CompletableFuture<Optional<SwdCell>>> needed = new HashMap<>();
        for (int x : new int[] {x0, x0 + 15}) {
            for (int z : new int[] {z0, z0 + 15}) {
                int e = SwissCoords.cellKm(coords.east(x)), n = SwissCoords.cellKm(coords.north(z));
                needed.computeIfAbsent(CellCache.key(e, n), k -> cache.get(e, n));
            }
        }
        List<CompletableFuture<Optional<SwdCell>>> futures = new ArrayList<>(needed.values());
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(v -> {
            Map<Long, SwdCell> cells = new HashMap<>();
            for (var f : needed.entrySet()) f.getValue().join().ifPresent(c -> cells.put(f.getKey(), c));
            GenContext ctx = new GenContext(chunk, coords, (e, n) -> cells.get(CellCache.key(e, n)));
            for (GenStage stage : PIPELINE) {
                stage.apply(ctx);
            }
            return chunk;
        });
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }

    @Override
    public int getGenDepth() {
        return 4064;
    }

    @Override
    public int getSeaLevel() {
        return SEA_LEVEL_Y;
    }

    @Override
    public int getMinY() {
        return -2032;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        // non-blocking: only cells already in memory; mod-core refines this (water, overlays)
        SwissCoords c = SwissRuntime.coords();
        long e = c.east(x), n = c.north(z);
        SwdCell cell = SwissRuntime.cells().getIfPresent(SwissCoords.cellKm(e), SwissCoords.cellKm(n));
        if (cell == null || !cell.inside(SwissCoords.dx(e), SwissCoords.dz(n))) return SEA_LEVEL_Y + 1;
        return (int) Math.floor(SwissCoords.toY(cell.terrain(SwissCoords.dx(e) + 0.5, SwissCoords.dz(n) + 0.5))) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        return new NoiseColumn(height.getMinBuildHeight(), new BlockState[0]);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
    }
}
