package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import ch.bissbert.swissworld.gen.site.Claim;
import ch.bissbert.swissworld.gen.site.SitePlan;
import ch.bissbert.swissworld.gen.site.SiteWriter;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Trees, bushes, ground cover, orchard and vineyard rows by SURF class. Owner: mod-surface.
 * Trees come from {@link TreePlan}: each chunk places the in-chunk blocks of every tree within reach, logs before
 * leaves, and only into air, before the ground cover (which then knows where the crowns shade the ground). Crowns
 * stay off building columns and clear of overlays ({@value TreePlan#OVERLAY_CLEARANCE} blocks above the deck).
 * Everything is keyed by world position, never by RNG state.
 */
public final class VegetationStage implements GenStage {
    private static final int SALT_COVER = 21, SALT_FLOWER = 22, SALT_PODZOL = 24;
    /** No grass or flowers above this. */
    private static final double COVER_TOP = 2600;
    private static final double ALPINE = 1500;
    private static final Block[] FLOWERS = {Blocks.DANDELION, Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET};
    private static final Block[] ALPINE_FLOWERS = {Blocks.CORNFLOWER, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.ALLIUM};

    @Override
    public void apply(GenContext ctx) {
        ChunkAccess chunk = ctx.chunk;
        SitePlan site = ctx.site();
        SiteWriter w = SiteWriter.of(ctx, SiteWriter.Stage.VEGETATION);
        ColumnGrid g = site != null ? site.grid() : ColumnGrid.of(ctx);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<TreePlan.Tree> trees = site != null ? site.trees(x0, z0) : TreePlan.trees(g, TreeData.of(ctx), x0, z0, x0 + 15, z0 + 15);
        List<List<TreePlan.Part>> shapes = trees.stream().map(TreePlan::shape).toList();
        // columns under a crown (leaves at least 3 blocks up): shade plants there
        boolean[] shade = new boolean[256];
        for (boolean logs : new boolean[] {true, false}) {
            for (int i = 0; i < trees.size(); i++) {
                TreePlan.Tree t = trees.get(i);
                int base = g.ground(t.x(), t.z()) + 1;
                if (!fits(site, t, shapes.get(i), base)) continue;
                for (TreePlan.Part p : shapes.get(i)) {
                    if (p.log() != logs) continue;
                    int x = t.x() + p.dx(), z = t.z() + p.dz(), y = base + p.dy();
                    if (x < x0 || x > x0 + 15 || z < z0 || z > z0 + 15) continue;
                    if (g.hasBuilding(x, z) || TreePlan.surfaceOverlay(g, x, z) && y <= g.deck(x, z) + TreePlan.OVERLAY_CLEARANCE) continue;
                    pos.set(x, y, z);
                    if (!chunk.getBlockState(pos).isAir() || !w.allowed(x, y, z)) continue;
                    w.set(x, y, z, block(t.species(), p));
                    if (!p.log() && y >= g.ground(x, z) + 3) shade[(z - z0) * 16 + x - x0] = true;
                }
            }
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx, z = z0 + lz;
                if (g.present(x, z)) column(chunk, w, g, x, z, shade[lz * 16 + lx], pos);
            }
        }
    }

    /**
     * A tree is planted only where it fits the site: on natural ground (or an earthwork or bank), with no part in a
     * structure, building or landing column below its crown clearance, no part under the deck of a way or bridge or
     * the surface of water, and no log over a way or water. Leaves hanging high over a way are only trimmed.
     * Decided from the site plan's claims (tile + PAD covers every part), so every chunk the tree reaches agrees.
     */
    static boolean fits(SitePlan site, TreePlan.Tree t, List<TreePlan.Part> shape, int base) {
        if (site == null) return true;
        Claim at = site.claim(t.x(), t.z());
        if (at != Claim.FREE && at != Claim.EARTHWORK && at != Claim.BANK) return false;
        for (TreePlan.Part p : shape) {
            int x = t.x() + p.dx(), z = t.z() + p.dz(), y = base + p.dy();
            if (!site.covers(x, z)) continue;
            Claim c = site.claim(x, z);
            int g = site.ground(x, z);
            switch (c) {
                case FREE, EARTHWORK, BANK -> {}
                case STRUCTURE, BUILDING, LANDING -> {
                    if (y <= g + SiteWriter.CROWN_CLEARANCE) return false;
                }
                default -> {
                    if (y < g || p.log() && y <= g + SiteWriter.CROWN_CLEARANCE) return false;
                }
            }
        }
        return true;
    }

    private static BlockState block(TreePlan.Species s, TreePlan.Part p) {
        Block log = switch (s) {
            case BIRCH -> Blocks.BIRCH_LOG;
            case SPRUCE, LARCH, PINE -> Blocks.SPRUCE_LOG;
            default -> Blocks.OAK_LOG;
        };
        if (p.log()) return log.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.valueOf(p.axis().name()));
        Block leaves = switch (s) {
            case BIRCH -> Blocks.BIRCH_LEAVES;
            case SPRUCE, LARCH, PINE -> Blocks.SPRUCE_LEAVES;
            default -> Blocks.OAK_LEAVES;
        };
        return p.distance() == 0
                ? leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
                : leaves.defaultBlockState().setValue(LeavesBlock.DISTANCE, p.distance());
    }

    /**
     * Ground cover, rows and ice of one column. Plants go only onto grass with air above. Forest floor in the shade of
     * a crown gets ferns and moss (podzol under conifers), no flowers; gaps get grass and flowers.
     */
    private static void column(ChunkAccess chunk, SiteWriter w, ColumnGrid g, int x, int z, boolean shade, BlockPos.MutableBlockPos pos) {
        int surf = g.surf(x, z);
        if (TreePlan.surfaceOverlay(g, x, z) || g.hasBuilding(x, z) || g.source(x, z) != ColumnGrid.NONE) return;
        if (!w.treeGround(x, z)) return;
        int ground = g.ground(x, z);
        if (g.top(x, z) != ground) return;
        long e = g.east(x), n = g.north(z);
        // glacier ice is SnowStage's (ground zones)
        if (surf == 6) return;
        // any earthy ground GroundTextureStage left: grass, podzol, moss, coarse or rooted dirt, mud (all #dirt)
        BlockState groundState = chunk.getBlockState(pos.set(x, ground, z));
        if (!groundState.is(BlockTags.DIRT)) return;
        if (!chunk.getBlockState(pos.set(x, ground + 1, z)).isAir()) return;
        double m = g.metres(x, z);
        double r = ColumnHash.unit(e, n, SALT_COVER);
        BlockState plant = switch (surf) {
            case 0, 11, 17 -> {
                if (m >= COVER_TOP) yield null;
                if (r < 0.03) {
                    Block[] set = m >= ALPINE ? ALPINE_FLOWERS : FLOWERS;
                    yield set[ColumnHash.below(e, n, SALT_FLOWER, set.length)].defaultBlockState();
                }
                yield r < 0.25 ? Blocks.SHORT_GRASS.defaultBlockState() : null;
            }
            case 1, 2, 3 -> {
                if (!shade) {
                    if (r < 0.02 && m < COVER_TOP) yield FLOWERS[ColumnHash.below(e, n, SALT_FLOWER, FLOWERS.length)].defaultBlockState();
                    yield r < 0.3 ? Blocks.SHORT_GRASS.defaultBlockState() : r < 0.36 ? Blocks.FERN.defaultBlockState() : null;
                }
                if (surf != 1 && groundState.is(Blocks.GRASS_BLOCK) && ColumnHash.unit(e, n, SALT_PODZOL) < 0.35) {
                    w.set(x, ground, z, Blocks.PODZOL.defaultBlockState());
                }
                yield r < 0.1 ? Blocks.FERN.defaultBlockState() : r < 0.18 ? Blocks.MOSS_CARPET.defaultBlockState() : null;
            }
            case 14 -> r < 0.2 ? Blocks.SHORT_GRASS.defaultBlockState() : r < 0.23 ? Blocks.SWEET_BERRY_BUSH.defaultBlockState() : null;
            case 12 -> r < 0.3 ? Blocks.SHORT_GRASS.defaultBlockState() : null;
            case 13 -> vine(e, n);
            default -> null;
        };
        if (plant != null) w.set(x, ground + 1, z, plant);
    }

    /** Vineyard: east-west rows every 3 m, a post every 5 m, vines (persistent leaves) between. */
    private static BlockState vine(long e, long n) {
        if (Math.floorMod(n, 3) != 0) return null;
        return Math.floorMod(e, 5) == 0
                ? Blocks.OAK_FENCE.defaultBlockState()
                : Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }
}
