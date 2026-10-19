package ch.bissbert.swissworld.gen;

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
import java.util.List;
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
 * Skeleton: runs the stage pipeline with no cells (everything outside coverage) and returns at once.
 * M1 makes fillFromNoise wait asynchronously for the chunk's cells before running the pipeline.
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
        GenContext ctx = new GenContext(chunk, new SwissCoords(2_632_900, 1_170_600), (e, n) -> null);
        for (GenStage stage : PIPELINE) {
            stage.apply(ctx);
        }
        return CompletableFuture.completedFuture(chunk);
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
        return SEA_LEVEL_Y + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        return new NoiseColumn(height.getMinBuildHeight(), new BlockState[0]);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
    }
}
