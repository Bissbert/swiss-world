package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.bissbert.swissworld.cell.SwdCell;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cost of the trees of a dense forest chunk: planning (placement and sites) and growing, printed as BENCH lines. */
class TreeBenchTest {
    @Test
    void denseForestChunkStaysCheap() {
        for (int surf : new int[] {2, 1}) {
            SwdCell c = Cells.flat(surf == 2 ? 1200 : 700);
            Arrays.fill(c.surf(), (byte) surf);
            long trees = 0, blocks = 0, plan = 0, grow = 0;
            int chunks = 0;
            // the first rounds warm the JIT up, the last one counts
            for (int rep = 0; rep < 3; rep++) {
                trees = blocks = plan = grow = 0;
                chunks = 0;
                for (int cx = -400; cx < 0; cx += 16) {
                    for (int cz = -304; cz < 304; cz += 16) {
                        long t0 = System.nanoTime();
                        ColumnGrid g = Cells.grid(c, cx, cz);
                        List<TreePlan.Tree> ts = TreePlan.trees(g, cx, cz, cx + 15, cz + 15);
                        long t1 = System.nanoTime();
                        for (TreePlan.Tree t : ts) blocks += TreePlan.shape(t).size();
                        long t2 = System.nanoTime();
                        plan += t1 - t0;
                        grow += t2 - t1;
                        trees += ts.size();
                        chunks++;
                    }
                }
            }
            double ms = (plan + grow) / 1e6 / chunks;
            System.out.printf("BENCH surf %d: %.1f trees/chunk, plan %.3f ms/chunk, grow %.3f ms/chunk, %.1f us/tree, %d blocks/tree%n",
                    surf, trees / (double) chunks, plan / 1e6 / chunks, grow / 1e6 / chunks, grow / 1e3 / trees, blocks / trees);
            // generous, so a busy machine does not fail the build; the budget is about 3 ms
            assertTrue(ms < 20, "trees cost " + ms + " ms per chunk");
        }
    }
}
