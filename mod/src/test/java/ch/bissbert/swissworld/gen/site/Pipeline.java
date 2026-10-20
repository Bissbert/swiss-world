package ch.bissbert.swissworld.gen.site;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import ch.bissbert.swissworld.gen.SwissChunkGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.ticks.ProtoChunkTicks;

/** Runs the generation pipeline on proto chunks from real store cells, without a server (biomes left out). */
final class Pipeline {
    static final SwissCoords ORIGIN = new SwissCoords(2_632_900, 1_170_600);
    static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-2032, 4064);
    private static Holder<Biome> plains;
    private static CrudeIncrementalIntIdentityHashBiMap<Holder<Biome>> biomes;

    final Path store;
    private final Map<Long, java.util.Optional<SwdCell>> cells = new ConcurrentHashMap<>();

    Pipeline(Path store) {
        this.store = store;
        boot();
    }

    static synchronized void boot() {
        if (plains != null) return;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        plains = Holder.direct(null);
        biomes = CrudeIncrementalIntIdentityHashBiMap.create(1);
        biomes.add(plains);
    }

    /** The store's cell, read once; null if the store has none (ocean, outside). */
    SwdCell cell(int eKm, int nKm) {
        return cells.computeIfAbsent(((long) eKm << 32) | (nKm & 0xFFFFFFFFL), k -> {
            Path p = store.resolve("cells").resolve(eKm + "_" + nKm + ".swd");
            if (!Files.isRegularFile(p)) return java.util.Optional.empty();
            try {
                return java.util.Optional.of(SwdReader.read(p));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).orElse(null);
    }

    static ProtoChunk chunk(ChunkPos pos) {
        LevelChunkSection[] s = new LevelChunkSection[HEIGHT.getSectionsCount()];
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < s.length; i++) {
            s[i] = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES),
                    new PalettedContainer<>(biomes, plains, PalettedContainer.Strategy.SECTION_BIOMES));
        }
        return new ProtoChunk(pos, UpgradeData.EMPTY, s, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), HEIGHT, null, null);
    }

    /** The context the generator gives a chunk: its tile's site plan, or none ({@code site} null). */
    GenContext context(ProtoChunk chunk, SitePlan site) {
        return new GenContext(chunk, ORIGIN, this::cell, site);
    }

    static void run(GenContext ctx) {
        for (GenStage s : SwissChunkGenerator.PIPELINE) s.apply(ctx);
    }

    /** A hash of every block of the chunk, and of its block entities' positions. */
    static long hash(ProtoChunk c) {
        long h = 1125899906842597L;
        LevelChunkSection[] s = c.getSections();
        for (LevelChunkSection sec : s) {
            if (sec.hasOnlyAir()) {
                h = h * 31 + 7;
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) h = h * 31 + Block.BLOCK_STATE_REGISTRY.getId(sec.getBlockState(x, y, z));
                }
            }
        }
        for (var p : new java.util.TreeSet<>(c.getBlockEntitiesPos())) h = h * 31 + p.asLong();
        return h;
    }
}
