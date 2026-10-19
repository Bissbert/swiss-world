package ch.bissbert.swissworld.world;

import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.SwissWorld;
import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.SwissChunkGenerator;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Fills loaded empty placeholders with their real terrain, in place. Owner: mod-core.
 *
 * <p>A chunk whose cells were given up ({@link StallGuard}, shutdown) completes empty and is marked in {@link
 * SwissRuntime#abortedChunks}. Unloaded, it is saved downgraded ({@link AbortedChunks}) and generates again on the
 * next load. While it stays loaded (a player stands in it), this asks for its cells again; once they are there the
 * chunk is generated off-thread into a fresh {@link ProtoChunk} exactly like the noise step does, then swapped into
 * the loaded chunk on the server thread: sections, block entities, post-processing, biome decoration (ores, snow),
 * heightmaps and POIs; then the chunk is relit like a new one, and every client that already has the empty version
 * gets the new chunk packet.
 *
 * <p>Repair waits until all 8 neighbours are loaded: decoration writes into them, and loading one here would block
 * the server thread. A placeholder at the edge of the loaded area therefore stays empty until a player comes
 * closer (or is regenerated when next loaded); a player's own chunk always has its neighbours loaded.
 * Known simplification: light that crossed the chunk border before (e.g. sky light from the empty chunk into a
 * neighbour's cave) is not recomputed in the neighbours.
 */
public final class PlaceholderRepair {
    /** Look for loaded placeholders this often. */
    static final int SCAN_TICKS = 20;
    /** At most this many chunks are swapped in per tick. */
    static final int APPLY_PER_TICK = 2;
    /** At most this many chunks are waiting for cells or generating at once. */
    static final int MAX_JOBS = 256;
    static final long RETRY_MS = 2_000;

    private static final Map<Long, Job> JOBS = new HashMap<>();
    private static final Queue<Job> GENERATED = new ConcurrentLinkedQueue<>();
    /** Swapped in, light and resend still to come: players are not put down on these yet. */
    private static final Set<Long> SETTLING = ConcurrentHashMap.newKeySet();

    private static final class Job {
        final ChunkPos pos;
        volatile ProtoChunk result;
        volatile long retryAt;
        volatile boolean running = true;

        Job(ChunkPos pos) {
            this.pos = pos;
        }
    }

    private PlaceholderRepair() {}

    /** A chunk whose real terrain is in place and whose clients have it (or will get it with their next batch). */
    public static boolean settled(ChunkPos pos) {
        return !SwissRuntime.wasAborted(pos) && !SETTLING.contains(pos.toLong());
    }

    public static void clear() {
        JOBS.clear();
        GENERATED.clear();
        SETTLING.clear();
    }

    /** Server thread, every tick, for the Switzerland level. */
    public static void tick(ServerLevel level) {
        for (int i = 0; i < APPLY_PER_TICK; i++) {
            Job job = GENERATED.poll();
            if (job == null) break;
            JOBS.remove(job.pos.toLong(), job);
            try {
                apply(level, job);
            } catch (RuntimeException e) {
                SwissWorld.LOGGER.error("Could not fill in placeholder chunk {}", job.pos, e);
            }
        }
        if (level.getServer().getTickCount() % SCAN_TICKS == 0) scan(level);
    }

    private static void scan(ServerLevel level) {
        ServerChunkCache source = level.getChunkSource();
        long now = Util.getMillis();
        for (Iterator<Map.Entry<Long, Job>> it = JOBS.entrySet().iterator(); it.hasNext(); ) {
            Job j = it.next().getValue();
            if (!SwissRuntime.wasAborted(j.pos) || source.getChunkNow(j.pos.x, j.pos.z) == null) it.remove();
        }
        for (long key : SwissRuntime.abortedChunks()) {
            if (JOBS.size() >= MAX_JOBS) break;
            Job old = JOBS.get(key);
            if (old != null && (old.running || now < old.retryAt)) continue;
            ChunkPos pos = new ChunkPos(key);
            if (source.getChunkNow(pos.x, pos.z) == null || !neighboursLoaded(source, pos)) continue;
            Job job = new Job(pos);
            JOBS.put(key, job);
            start(level, job);
        }
    }

    private static boolean neighboursLoaded(ServerChunkCache source, ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && source.getChunkNow(pos.x + dx, pos.z + dz) == null) return false;
            }
        }
        return true;
    }

    /** Asks for the cells again (the cache shares one request per cell) and generates once they are all there. */
    private static void start(ServerLevel level, Job job) {
        if (!(level.getChunkSource().getGenerator() instanceof SwissChunkGenerator generator)) return;
        SwissCoords coords = SwissRuntime.lockOrigin();
        CellCache cache = SwissRuntime.cells();
        var randomState = level.getChunkSource().randomState();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Map<Long, CompletableFuture<CellCache.Result>> needed = SwissChunkGenerator.requestCells(cache, coords, job.pos);
        CompletableFuture.allOf(needed.values().toArray(CompletableFuture[]::new)).thenRunAsync(() -> {
            Map<Long, SwdCell> cells = SwissChunkGenerator.loadedCells(needed);
            if (cells == null) { // given up again (another stall): ask again later
                job.retryAt = Util.getMillis() + RETRY_MS;
                job.running = false;
                return;
            }
            ProtoChunk proto = new ProtoChunk(job.pos, UpgradeData.EMPTY, level, biomes, null);
            generator.fill(proto, coords, cells, randomState);
            job.result = proto;
            GENERATED.add(job);
        }, Util.backgroundExecutor()).exceptionally(err -> {
            SwissWorld.LOGGER.error("Could not generate placeholder chunk {} again", job.pos, err);
            job.retryAt = Util.getMillis() + RETRY_MS * 15;
            job.running = false;
            return null;
        });
    }

    private static void apply(ServerLevel level, Job job) {
        ChunkPos pos = job.pos;
        ServerChunkCache source = level.getChunkSource();
        LevelChunk chunk = source.getChunkNow(pos.x, pos.z);
        // unloaded meanwhile (saved downgraded, generates again when loaded) or generated normally after a reload
        if (chunk == null || !SwissRuntime.wasAborted(pos)) return;
        if (!neighboursLoaded(source, pos)) return; // the next scan starts over
        ProtoChunk proto = job.result;

        for (BlockPos p : new ArrayList<>(chunk.getBlockEntities().keySet())) chunk.removeBlockEntity(p);
        LevelChunkSection[] to = chunk.getSections(), from = proto.getSections();
        System.arraycopy(from, 0, to, 0, to.length);
        for (BlockEntity be : proto.getBlockEntities().values()) chunk.addAndRegisterBlockEntity(be);
        for (var tag : proto.getBlockEntityNbts().values()) chunk.setBlockEntityNbt(tag);
        ShortList[] post = proto.getPostProcessing();
        for (int i = 0; i < post.length; i++) {
            if (post[i] != null) for (short s : post[i]) chunk.addPackedPostProcess(s, i);
        }
        // the worldgen ones too: decoration below places features by them, as on a proto chunk
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        chunk.postProcessGeneration();
        // decoration reaches into the neighbours too; vanilla would prime their worldgen heightmaps lazily (and,
        // in a dev run, log an error for each)
        EnumSet<Heightmap.Types> wg = EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk n = source.getChunkNow(pos.x + dx, pos.z + dz);
                if (n != null && !n.hasPrimedHeightmap(Heightmap.Types.OCEAN_FLOOR_WG)) Heightmap.primeHeightmaps(n, wg);
            }
        }
        source.getGenerator().applyBiomeDecoration(level, chunk, level.structureManager());
        for (int i = 0; i < to.length; i++) {
            level.getPoiManager().checkConsistencyWithBlocks(SectionPos.of(pos, level.getSectionYFromSectionIndex(i)), to[i]);
        }
        chunk.setUnsaved(true);
        SwissRuntime.clearAborted(pos);
        SETTLING.add(pos.toLong());

        // relight like a new chunk: drop the old (all sky) light, then initialize and propagate again
        ThreadedLevelLightEngine light = source.getLightEngine();
        chunk.initializeLightSources();
        light.updateChunkStatus(pos);
        light.initializeLight(chunk, false)
                .thenCompose(c -> light.lightChunk(c, false))
                .whenCompleteAsync((c, err) -> {
                    SETTLING.remove(pos.toLong());
                    if (err != null) {
                        SwissWorld.LOGGER.error("Could not relight chunk {}", pos, err);
                    } else if (source.getChunkNow(pos.x, pos.z) == chunk) {
                        resend(level, chunk);
                    }
                }, level.getServer());
        light.tryScheduleUpdate();
        SwissWorld.LOGGER.info("Filled in placeholder chunk {} ({} left)", pos, SwissRuntime.abortedCount());
    }

    /** To every player that already got the empty chunk; players it is still queued for get the new one anyway. */
    private static void resend(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        var chunkMap = level.getChunkSource().chunkMap;
        ChunkHolder holder = chunkMap.getVisibleChunkIfPresent(pos.toLong());
        if (holder == null || holder.getChunkToSend() == null) return; // not sent to anyone yet
        ClientboundLevelChunkWithLightPacket packet = null;
        for (ServerPlayer p : chunkMap.getPlayers(pos, false)) {
            if (p.connection.chunkSender.isPending(pos.toLong())) continue;
            if (packet == null) packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            p.connection.send(packet);
        }
    }
}
