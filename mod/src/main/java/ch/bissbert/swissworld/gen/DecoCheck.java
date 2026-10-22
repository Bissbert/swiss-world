package ch.bissbert.swissworld.gen;

import ch.bissbert.swissworld.SwissRegistries;
import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.cell.Wgs84;
import ch.bissbert.swissworld.gen.site.SitePlan;
import ch.bissbert.swissworld.gen.site.SitePlans;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * {@code /swiss admin decocheck <a> <b> [chunks]} (a, b as for {@code /swiss goto}): generates the square of
 * {@code 2*chunks+1} chunks around a place three times, with the full {@link SwissChunkGenerator#fill} pipeline, in
 * detached proto chunks (the world is not touched). Decorates set A with vanilla's {@code applyBiomeDecoration} and set B
 * with {@link FastDecoration}, in the same order, through real {@link WorldGenRegion}s of the Switzerland level, and
 * compares every block state of A and B (plus, for each decorated chunk, the biome set both paths collect). Set C is left
 * undecorated, only to show how many blocks decoration changed (a check that changed nothing would prove nothing). Fails
 * on any difference. Blocks the server thread while it runs (a few seconds); dev tool.
 */
public final class DecoCheck {
    private DecoCheck() {}

    private static final int WAIT_SECONDS = 120;

    /** Same holder for a chunk that is already there, whatever status is asked for. */
    private static final class Held extends GenerationChunkHolder {
        private final ChunkAccess chunk;

        Held(ChunkAccess chunk) {
            super(chunk.getPos());
            this.chunk = chunk;
        }

        @Override
        public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return chunk;
        }

        @Override
        public int getTicketLevel() {
            return 0;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }
    }

    public static int run(CommandSourceStack src, double a, double b, int radius) {
        ServerLevel level = src.getServer().getLevel(SwissRegistries.SWITZERLAND);
        if (level == null || !(level.getChunkSource().getGenerator() instanceof SwissChunkGenerator gen)) {
            src.sendFailure(Component.literal("DECOCHECK FAIL: no Switzerland level"));
            return 0;
        }
        SwissCoords coords = SwissRuntime.lockOrigin();
        double e, n;
        if (Math.abs(a) < 90 && Math.abs(b) < 180) {
            double[] en = Wgs84.toLv95(a, b);
            e = en[0];
            n = en[1];
        } else {
            e = a < 1_000_000 ? a + 2_000_000 : a;
            n = b < 1_000_000 ? b + 1_000_000 : b;
        }
        int cx = (int) Math.floor(e - coords.e0()) >> 4, cz = (int) Math.floor(coords.n0() - n) >> 4;
        int reach = radius + 1; // the decorated square plus the ring decoration writes into
        long t0 = System.nanoTime();
        try {
            ChunkAccess[][] sets = new ChunkAccess[3][];
            int side = 2 * reach + 1;
            for (int s = 0; s < 3; s++) sets[s] = new ChunkAccess[side * side];
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                    SitePlan site = SitePlans.forColumn(SwissRuntime.cells(), coords, pos.getMinBlockX(), pos.getMinBlockZ(),
                            Runnable::run).get(WAIT_SECONDS, TimeUnit.SECONDS);
                    if (site == null) {
                        src.sendFailure(Component.literal("DECOCHECK FAIL: no plan for chunk " + pos));
                        return 0;
                    }
                    for (int s = 0; s < 3; s++) {
                        ProtoChunk proto = new ProtoChunk(pos, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
                        gen.fill(proto, coords, site, level.getChunkSource().randomState());
                        proto.setPersistedStatus(ChunkStatus.CARVERS); // biomes are readable from BIOMES on
                        // as ChunkStatusTasks.generateNoise
                        Heightmap.primeHeightmaps(proto, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
                        sets[s][(dz + reach) * side + dx + reach] = proto;
                    }
                }
            }
            long gen0 = System.nanoTime();
            ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
            int badSets = 0;
            int decorated = 0;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int s = 0; s < 2; s++) {
                        ChunkAccess[] set = sets[s];
                        ChunkAccess center = set[(dz + reach) * side + dx + reach];
                        StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(cx + dx, cz + dz, 1,
                                (x, z) -> new Held(set[(z - cz + reach) * side + x - cx + reach]));
                        // as ChunkStatusTasks.generateFeatures
                        Heightmap.primeHeightmaps(center, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
                        WorldGenRegion region = new WorldGenRegion(level, cache, step, center);
                        var sm = level.structureManager().forWorldGenRegion(region);
                        if (s == 0) {
                            if (!sameBiomes(region, center.getPos())) badSets++;
                            gen.applyBiomeDecorationVanilla(region, center, sm);
                        } else {
                            FastDecoration.apply(gen, region, center, sm);
                        }
                    }
                    decorated++;
                }
            }
            long deco1 = System.nanoTime();
            int diffs = 0, changed = 0;
            List<String> first = new ArrayList<>();
            for (int i = 0; i < sets[0].length; i++) {
                ChunkAccess ca = sets[0][i], cb = sets[1][i], cc = sets[2][i];
                int minY = ca.getMinBuildHeight();
                for (int si = 0; si < ca.getSections().length; si++) {
                    LevelChunkSection sa = ca.getSections()[si], sb = cb.getSections()[si], sc = cc.getSections()[si];
                    if (sa.hasOnlyAir() && sb.hasOnlyAir() && sc.hasOnlyAir()) continue;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState va = sa.getBlockState(x, y, z), vb = sb.getBlockState(x, y, z), vc = sc.getBlockState(x, y, z);
                                if (va != vc) changed++;
                                if (va != vb) {
                                    if (diffs++ < 5) {
                                        first.add(String.format(Locale.ROOT, "%d,%d,%d vanilla %s fast %s", ca.getPos().getMinBlockX() + x,
                                                minY + si * 16 + y, ca.getPos().getMinBlockZ() + z, va, vb));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            String where = String.format(Locale.ROOT, "chunk %d,%d r=%d (%d decorated)", cx, cz, radius, decorated);
            String line = String.format(Locale.ROOT, "%s: %d blocks differ, %d biome sets differ, decoration changed %d blocks (gen %.1fs, decorate x2 %.1fs, compare %.1fs)",
                    where, diffs, badSets, changed, (gen0 - t0) / 1e9, (deco1 - gen0) / 1e9, (System.nanoTime() - deco1) / 1e9);
            boolean ok = diffs == 0 && badSets == 0 && changed > 0;
            if (ok) {
                src.sendSuccess(() -> Component.literal("DECOCHECK OK " + line), false);
            } else {
                src.sendFailure(Component.literal("DECOCHECK FAIL " + line + (changed == 0 ? " (decoration changed nothing: nothing was checked)" : "")));
                for (String f : first) src.sendFailure(Component.literal("  " + f));
            }
            return ok ? 1 : 0;
        } catch (Exception ex) {
            src.sendFailure(Component.literal("DECOCHECK FAIL " + ex));
            ex.printStackTrace();
            return 0;
        }
    }

    /** Vanilla's biome set (all sections) against the fast one (lowest section), as the feature step uses them. */
    private static boolean sameBiomes(WorldGenRegion region, ChunkPos pos) {
        Set<Holder<Biome>> all = FastDecoration.biomesOfAllSections(region, pos), fast = FastDecoration.biomesOf(region, pos);
        return all.equals(fast);
    }
}
