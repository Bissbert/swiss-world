package ch.bissbert.swissworld.gen;

import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.building.BuildingStage;
import ch.bissbert.swissworld.gen.building.FurnishStage;
import ch.bissbert.swissworld.gen.building.LootStage;
import ch.bissbert.swissworld.gen.ground.GroundTextureStage;
import ch.bissbert.swissworld.gen.ground.SnowStage;
import ch.bissbert.swissworld.gen.site.GenStats;
import ch.bissbert.swissworld.gen.site.SitePlan;
import ch.bissbert.swissworld.gen.site.SitePlans;
import ch.bissbert.swissworld.gen.site.WriteTrace;
import ch.bissbert.swissworld.gen.stage.TerrainStage;
import ch.bissbert.swissworld.gen.surface.OverlayStage;
import ch.bissbert.swissworld.gen.surface.VegetationStage;
import ch.bissbert.swissworld.gen.surface.WaterStage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Generates the Switzerland dimension from .swd cells. Owner: mod-core.
 *
 * <p>{@link #fillFromNoise} returns a future that completes only when the {@link SitePlan} of the chunk's tile is
 * computed, which waits until every cell it reads is loaded or known to be ocean ({@link CellCache}); a missing cell is
 * requested from the sidecar and the chunk simply stays pending.
 * Nothing blocks a thread meanwhile, and the pipeline then runs on the worldgen background executor, never on the
 * cache's I/O threads. Biomes are filled here too, from the same cells (the BIOMES step is a no-op), so they are
 * never baked from a guess. At shutdown, or when the server thread is stuck loading a pending chunk
 * ({@link ch.bissbert.swissworld.world.StallGuard}), pending chunks complete empty and are downgraded on save
 * ({@link ch.bissbert.swissworld.world.AbortedChunks}); while loaded, such a placeholder is regenerated in place once
 * its cells arrive ({@link ch.bissbert.swissworld.world.PlaceholderRepair}).
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
            new GroundTextureStage(),
            new VegetationStage(),
            new SnowStage());

    /** Real sea level 193 m (lowest point of Switzerland) -> y = -1807. Ocean outside the border. */
    public static final int SEA_LEVEL_Y = (int) SwissCoords.toY(193);

    /**
     * How far around a chunk its stages may read cell data: the chunk lies in a {@link SitePlan} tile whose cells are
     * loaded with {@link SitePlan#MARGIN} around the tile, so at least this much around every chunk of it. The widest
     * look-out of a per-chunk grid is {@link ch.bissbert.swissworld.gen.surface.TransportPlan#MARGIN}; the shared
     * tile grid itself has {@link SitePlan#MARGIN}.
     */
    public static final int CELL_MARGIN = SitePlan.MARGIN;

    public SwissChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
        // filled in fillFromNoise once the cells are there
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        // lock before reading the origin, so no chunk can be generated with an origin that changes afterwards
        SwissCoords coords = SwissRuntime.lockOrigin();
        long t0 = System.nanoTime();
        CompletableFuture<SitePlan> plan = SitePlans.forColumn(SwissRuntime.cells(), coords,
                chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), Util.backgroundExecutor());
        boolean waits = !plan.isDone();
        if (waits) SwissRuntime.PENDING_CHUNKS.incrementAndGet();
        return plan.thenApplyAsync(site -> {
            if (waits) SwissRuntime.PENDING_CHUNKS.decrementAndGet();
            GenStats.record(GenStats.PLAN_WAIT, System.nanoTime() - t0);
            if (site == null) {
                // an empty placeholder: kept off disk (AbortedChunks), replaced once loaded (PlaceholderRepair)
                SwissRuntime.markAborted(chunk.getPos());
                return chunk;
            }
            SwissRuntime.clearAborted(chunk.getPos());
            fill(chunk, coords, site, randomState);
            return chunk;
        }, Util.backgroundExecutor());
    }

    /**
     * Biomes and the whole {@link #PIPELINE} for one chunk of the tile {@code site} plans; every cell any stage reads
     * is loaded with it. Any thread.
     */
    public void fill(ChunkAccess chunk, SwissCoords coords, SitePlan site, RandomState randomState) {
        long t0 = System.nanoTime();
        long c0 = GenStats.cpu();
        GenContext ctx = new GenContext(chunk, coords, site::cell, site);
        if (getBiomeSource() instanceof SwissBiomeSource biomes) {
            chunk.fillBiomesFromNoise(biomes.resolver(ctx), randomState.sampler());
        }
        long t = System.nanoTime();
        GenStats.record(GenStats.BIOMES, t - t0);
        GenStats.record(GenStats.CPU_BIOMES, GenStats.cpu() - c0);
        long traced = 0;
        for (int i = 0; i < PIPELINE.size(); i++) {
            GenStage stage = PIPELINE.get(i);
            Object before = WriteTrace.ENABLED && i > 0 ? WriteTrace.snapshot(chunk) : null;
            stage.apply(ctx);
            long now = System.nanoTime();
            GenStats.record(STAGE_NAMES.get(i), now - t);
            if (before != null) {
                WriteTrace.diff(chunk, before, i);
                long after = System.nanoTime(); // the trace's own cost is not the stage's, nor the chunk's
                traced += after - now;
                now = after;
            }
            t = now;
        }
        long total = System.nanoTime() - t0 - traced;
        GenStats.record(GenStats.CPU_CHUNK, GenStats.cpu() - c0);
        SwissRuntime.recordChunk(total);
        GenStats.chunk(total);
    }

    /** Stage names by pipeline index, for {@link GenStats} and {@link WriteTrace}. */
    public static final List<String> STAGE_NAMES = PIPELINE.stream().map(s -> s.getClass().getSimpleName()).toList();

    static {
        WriteTrace.stages(STAGE_NAMES);
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

    /** Non-blocking: from the cell if it is in memory, else ocean (coverage outside, or not loaded yet). */
    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        SwissCoords c = SwissRuntime.coords();
        long e = c.east(x), n = c.north(z);
        SwdCell cell = SwissRuntime.cells().getIfPresent(SwissCoords.cellKm(e), SwissCoords.cellKm(n));
        TerrainStage.Column col = TerrainStage.column(cell, SwissCoords.dx(e), SwissCoords.dz(n));
        if (type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG) return col.top() + 1;
        return Math.max(col.highest(), waterTop(cell, SwissCoords.dx(e), SwissCoords.dz(n))) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        SwissCoords c = SwissRuntime.coords();
        long e = c.east(x), n = c.north(z);
        int dx = SwissCoords.dx(e), dz = SwissCoords.dz(n);
        SwdCell cell = SwissRuntime.cells().getIfPresent(SwissCoords.cellKm(e), SwissCoords.cellKm(n));
        TerrainStage.Column col = TerrainStage.column(cell, dx, dz);
        int minY = height.getMinBuildHeight();
        int top = Math.min(height.getMaxBuildHeight() - 1, Math.max(col.highest(), waterTop(cell, dx, dz)));
        BlockState[] states = new BlockState[Math.max(0, top - minY + 1)];
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int y = minY; y <= top; y++) {
            BlockState s = y == minY ? Blocks.BEDROCK.defaultBlockState() : col.at(y);
            states[y - minY] = s != null ? s : water;
        }
        return new NoiseColumn(minY, states);
    }

    /** Top water block from WLVL (placed by WaterStage), or Integer.MIN_VALUE. */
    private static int waterTop(SwdCell cell, int dx, int dz) {
        if (cell == null) return Integer.MIN_VALUE;
        double w = cell.water(dx, dz);
        return Double.isNaN(w) ? Integer.MIN_VALUE : (int) Math.floor(SwissCoords.toY(w));
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        SwissCoords c = SwissRuntime.coords();
        info.add("Swiss: E " + c.east(pos.getX()) + " N " + c.north(pos.getZ()) + " h " + Math.round(SwissCoords.toMetres(pos.getY())) + " m");
        info.add("Swiss " + GenStats.compact());
    }

    /** Run vanilla's own FEATURES step ({@code -Dswissworld.vanillaFeatures=true}), for A/B checks against {@link FastDecoration}. */
    private static final boolean VANILLA_FEATURES = Boolean.getBoolean("swissworld.vanillaFeatures");

    /**
     * FEATURES step (ores, snow top layer): not part of {@link #fill}, so timed here ({@code features},
     * {@code cpu-features}). Vanilla's code with a cheaper biome set, see {@link FastDecoration}.
     */
    @Override
    public void applyBiomeDecoration(net.minecraft.world.level.WorldGenLevel level, ChunkAccess chunk,
                                     net.minecraft.world.level.StructureManager sm) {
        long t0 = System.nanoTime(), c0 = GenStats.cpu();
        if (VANILLA_FEATURES) super.applyBiomeDecoration(level, chunk, sm);
        else FastDecoration.apply(this, level, chunk, sm);
        GenStats.record(GenStats.FEATURES, System.nanoTime() - t0);
        GenStats.record(GenStats.CPU_FEATURES, GenStats.cpu() - c0);
    }

    /** Vanilla's FEATURES step, whatever {@code -Dswissworld.vanillaFeatures} says (for {@code DecoCheck}). */
    public void applyBiomeDecorationVanilla(net.minecraft.world.level.WorldGenLevel level, ChunkAccess chunk,
                                            net.minecraft.world.level.StructureManager sm) {
        super.applyBiomeDecoration(level, chunk, sm);
    }
}
