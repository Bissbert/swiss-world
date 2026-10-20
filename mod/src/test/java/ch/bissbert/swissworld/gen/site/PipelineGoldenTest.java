package ch.bissbert.swissworld.gen.site;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/**
 * Block hashes of whole generated chunks at the playtest spots (needs SWISSWORLD_SWEEP_STORE). GOLDEN_OUT=file writes
 * them; GOLDEN_IN=file compares and lists the chunks that differ. A refactoring tool, not a regression gate: the hashes
 * change with every intended change to the output.
 */
class PipelineGoldenTest {
    /** Chunk boxes cx0, cz0, cx1, cz1: Interlaken Ost rail yard, Aare bridge, forest Rugen, Innertkirchen, Faulhorn. */
    static final int[][] AREAS = {{0, -38, 7, -33}, {62, -56, 66, -53}, {-40, 48, -36, 51}, {1720, -153, 1723, -151},
            {561, 148, 563, 150}};

    static List<ChunkPos> chunks() {
        List<ChunkPos> out = new ArrayList<>();
        for (int[] a : AREAS) {
            for (int cz = a[1]; cz <= a[3]; cz++) for (int cx = a[0]; cx <= a[2]; cx++) out.add(new ChunkPos(cx, cz));
        }
        return out;
    }

    @Test
    void chunkHashes() throws Exception {
        String dir = System.getenv("SWISSWORLD_SWEEP_STORE");
        String out = System.getenv("GOLDEN_OUT"), in = System.getenv("GOLDEN_IN");
        assumeTrue(dir != null && (out != null || in != null), "SWISSWORLD_SWEEP_STORE and GOLDEN_OUT or GOLDEN_IN not set");
        Pipeline p = new Pipeline(Path.of(dir));
        Map<String, Long> hashes = new TreeMap<>();
        long t0 = System.nanoTime();
        for (ChunkPos pos : chunks()) {
            ProtoChunk c = Pipeline.chunk(pos);
            Pipeline.run(p.context(c, SitePlans.compute(p::cell, Pipeline.ORIGIN, SitePlan.tile(pos.getMinBlockX()),
                    SitePlan.tile(pos.getMinBlockZ()))));
            hashes.put(pos.x + "," + pos.z, Pipeline.hash(c));
        }
        System.out.printf("golden: %d chunks in %d ms%n", hashes.size(), (System.nanoTime() - t0) / 1_000_000);
        if (out != null) {
            List<String> lines = new ArrayList<>();
            hashes.forEach((k, v) -> lines.add(k + " " + v));
            Files.write(Path.of(out), lines);
        }
        if (in != null) {
            List<String> differ = new ArrayList<>();
            for (String l : Files.readAllLines(Path.of(in))) {
                String[] a = l.split(" ");
                Long h = hashes.get(a[0]);
                if (h == null || h != Long.parseLong(a[1])) differ.add(a[0]);
            }
            System.out.println("golden: " + differ.size() + " chunks differ: " + differ);
        }
    }
}
