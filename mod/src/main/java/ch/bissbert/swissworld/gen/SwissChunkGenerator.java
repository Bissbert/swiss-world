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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
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
 * <p>{@link #fillFromNoise} returns a future that completes only when every cell the chunk touches is loaded or known
 * to be ocean ({@link CellCache}); a missing cell is requested from the sidecar and the chunk simply stays pending.
 * Nothing blocks a thread meanwhile, and the pipeline then runs on the worldgen background executor, never on the
 * cache's I/O threads. Biomes are filled here too, from the same cells (the BIOMES step is a no-op), so they are
 * never baked from a guess. At shutdown, or when the server thread is stuck loading a pending chunk
 * ({@link ch.bissbert.swissworld.world.StallGuard}), pending chunks complete empty and are downgraded on save
 * ({@link ch.bissbert.swissworld.world.AbortedChunks}).
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

    /** Cells are loaded for the chunk plus this many columns around it, so stages can look at neighbour columns. */
    public static final int CELL_MARGIN = 8;

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
        CellCache cache = SwissRuntime.cells();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        Map<Long, CompletableFuture<CellCache.Result>> needed = new HashMap<>();
        for (int x : new int[] {x0 - CELL_MARGIN, x0 + 15 + CELL_MARGIN}) {
            for (int z : new int[] {z0 - CELL_MARGIN, z0 + 15 + CELL_MARGIN}) {
                int e = SwissCoords.cellKm(coords.east(x)), n = SwissCoords.cellKm(coords.north(z));
                needed.computeIfAbsent(CellCache.key(e, n), k -> cache.get(e, n));
            }
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(needed.values().toArray(CompletableFuture[]::new));
        boolean waits = !all.isDone();
        if (waits) SwissRuntime.PENDING_CHUNKS.incrementAndGet();
        return all.thenApplyAsync(v -> {
            if (waits) SwissRuntime.PENDING_CHUNKS.decrementAndGet();
            Map<Long, SwdCell> cells = new HashMap<>();
            for (var f : needed.entrySet()) {
                CellCache.Result r = f.getValue().join();
                if (r.status() == CellCache.Status.ABORTED) {
                    SwissRuntime.markAborted(chunk.getPos());
                    return chunk;
                }
                if (r.cell() != null) cells.put(f.getKey(), r.cell());
            }
            SwissRuntime.clearAborted(chunk.getPos());
            long t0 = System.nanoTime();
            GenContext ctx = new GenContext(chunk, coords, (e, n) -> cells.get(CellCache.key(e, n)));
            if (getBiomeSource() instanceof SwissBiomeSource biomes) {
                chunk.fillBiomesFromNoise(biomes.resolver(ctx), randomState.sampler());
            }
            for (GenStage stage : PIPELINE) {
                stage.apply(ctx);
            }
            SwissRuntime.recordChunk(System.nanoTime() - t0);
            return chunk;
        }, Util.backgroundExecutor());
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
    }
}
