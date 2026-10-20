package ch.bissbert.swissworld.gen.site;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Dev-only "who wrote this block" trace (system property {@code swissworld.trace=true}). Before each stage after the
 * first, the chunk's sections are copied; after it, every block that changed is credited to that stage. Per position
 * the chain of writers is kept (up to 15 stages), so overwrites show in order; blocks no later stage touched are the
 * first stage's (terrain). Diffing needs no hook in the stages, so no writer can be missed. Kept for the last
 * {@value #CHUNKS} chunks. Query: {@code /swiss admin who <pos>}. Owner: mod-siteplan.
 */
public final class WriteTrace {
    public static final boolean ENABLED = Boolean.getBoolean("swissworld.trace");
    static final int CHUNKS = 4096;
    /** Chunk -> (packed local position -> chain of stage indices, 4 bits each, newest lowest). */
    private static final Map<Long, Int2LongOpenHashMap> TRACES = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Int2LongOpenHashMap> eldest) {
            return size() > CHUNKS;
        }
    };
    private static volatile List<String> stages = List.of();

    private WriteTrace() {}

    /** Names of the stages by index, as the generator runs them. */
    public static void stages(List<String> names) {
        stages = List.copyOf(names);
    }

    /** A copy of every non-empty section, to diff after a stage. */
    public static Object snapshot(ChunkAccess chunk) {
        LevelChunkSection[] s = chunk.getSections();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] copy = new PalettedContainer[s.length];
        for (int i = 0; i < s.length; i++) {
            if (!s[i].hasOnlyAir()) copy[i] = s[i].getStates().copy();
        }
        return copy;
    }

    /** Credits stage {@code stage} with every block that differs from {@code before}. */
    @SuppressWarnings("unchecked")
    public static void diff(ChunkAccess chunk, Object before, int stage) {
        PalettedContainer<BlockState>[] old = (PalettedContainer<BlockState>[]) before;
        LevelChunkSection[] s = chunk.getSections();
        Int2LongOpenHashMap map;
        long key = chunk.getPos().toLong();
        synchronized (TRACES) {
            map = TRACES.computeIfAbsent(key, k -> new Int2LongOpenHashMap());
        }
        synchronized (map) {
            for (int i = 0; i < s.length; i++) {
                if (old[i] == null && s[i].hasOnlyAir()) continue;
                PalettedContainer<BlockState> now = s[i].getStates();
                int sy = chunk.getSectionYFromSectionIndex(i);
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState a = old[i] == null ? null : old[i].get(x, y, z);
                            BlockState b = now.get(x, y, z);
                            if (a == b || a == null && b.isAir()) continue;
                            int p = pack(x, (sy << 4) + y, z);
                            long chain = map.get(p);
                            map.put(p, (chain << 4) | (stage & 15));
                        }
                    }
                }
            }
        }
    }

    static int pack(int lx, int y, int lz) {
        return (y + 4096) << 8 | (lz & 15) << 4 | (lx & 15);
    }

    /** The stages that wrote the block at {@code pos}, oldest first; null if its chunk was not traced. */
    public static List<String> writers(BlockPos pos) {
        Int2LongOpenHashMap map;
        synchronized (TRACES) {
            map = TRACES.get(new ChunkPos(pos).toLong());
        }
        if (map == null) return null;
        long chain;
        synchronized (map) {
            chain = map.get(pack(pos.getX(), pos.getY(), pos.getZ()));
        }
        List<String> names = stages, out = new ArrayList<>();
        for (; chain != 0; chain >>>= 4) {
            int i = (int) (chain & 15);
            out.add(0, i < names.size() ? names.get(i) : "#" + i);
        }
        if (out.isEmpty() && !names.isEmpty()) out.add(names.get(0));
        return out;
    }

    public static int tracedChunks() {
        synchronized (TRACES) {
            return TRACES.size();
        }
    }
}
