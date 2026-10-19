package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Trees, bushes, ground cover, orchard and vineyard rows, glacier ice by SURF class. Owner: mod-surface.
 * Trees come from {@link TreePlan}: each chunk places the in-chunk blocks of every tree within reach, logs before
 * leaves, and only into air, before the ground cover (which then knows where the crowns shade the ground). Crowns
 * stay off building columns and clear of overlays ({@value TreePlan#OVERLAY_CLEARANCE} blocks above the deck).
 * Everything is keyed by world position, never by RNG state.
 */
public final class VegetationStage implements GenStage {
    private static final int SALT_COVER = 21, SALT_FLOWER = 22, SALT_ICE = 23, SALT_PODZOL = 24;
    /** No grass or flowers above this. */
    private static final double COVER_TOP = 2600;
    private static final double ALPINE = 1500;
    private static final Block[] FLOWERS = {Blocks.DANDELION, Blocks.POPPY, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET};
    private static final Block[] ALPINE_FLOWERS = {Blocks.CORNFLOWER, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.ALLIUM};

    @Override
    public void apply(GenContext ctx) {
        ChunkAccess chunk = ctx.chunk;
        ColumnGrid g = ColumnGrid.of(ctx);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<TreePlan.Tree> trees = TreePlan.trees(g, TreeData.of(ctx), x0, z0, x0 + 15, z0 + 15);
        List<List<TreePlan.Part>> shapes = trees.stream().map(TreePlan::shape).toList();
        // columns under a crown (leaves at least 3 blocks up): shade plants there
        boolean[] shade = new boolean[256];
        for (boolean logs : new boolean[] {true, false}) {
            for (int i = 0; i < trees.size(); i++) {
                TreePlan.Tree t = trees.get(i);
                int base = g.ground(t.x(), t.z()) + 1;
                for (TreePlan.Part p : shapes.get(i)) {
                    if (p.log() != logs) continue;
                    int x = t.x() + p.dx(), z = t.z() + p.dz(), y = base + p.dy();
                    if (x < x0 || x > x0 + 15 || z < z0 || z > z0 + 15) continue;
                    if (g.hasBuilding(x, z) || TreePlan.surfaceOverlay(g, x, z) && y <= g.deck(x, z) + TreePlan.OVERLAY_CLEARANCE) continue;
                    pos.set(x, y, z);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    chunk.setBlockState(pos, block(t.species(), p), false);
                    if (!p.log() && y >= g.ground(x, z) + 3) shade[(z - z0) * 16 + x - x0] = true;
                }
            }
        }
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx, z = z0 + lz;
                if (g.present(x, z)) column(chunk, g, x, z, shade[lz * 16 + lx], pos);
            }
        }
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
    private static void column(ChunkAccess chunk, ColumnGrid g, int x, int z, boolean shade, BlockPos.MutableBlockPos pos) {
        int surf = g.surf(x, z);
        if (TreePlan.surfaceOverlay(g, x, z) || g.hasBuilding(x, z) || g.source(x, z) != ColumnGrid.NONE) return;
        int ground = g.ground(x, z);
        if (g.top(x, z) != ground) return;
        long e = g.east(x), n = g.north(z);
        if (surf == 6) {
            glacier(chunk, x, z, ground, 3 + ColumnHash.below(e, n, SALT_ICE, 6), pos);
            return;
        }
        if (!chunk.getBlockState(pos.set(x, ground, z)).is(Blocks.GRASS_BLOCK)) return;
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
                if (surf != 1 && ColumnHash.unit(e, n, SALT_PODZOL) < 0.35) chunk.setBlockState(pos.set(x, ground, z), Blocks.PODZOL.defaultBlockState(), false);
                yield r < 0.1 ? Blocks.FERN.defaultBlockState() : r < 0.18 ? Blocks.MOSS_CARPET.defaultBlockState() : null;
            }
            case 14 -> r < 0.2 ? Blocks.SHORT_GRASS.defaultBlockState() : r < 0.23 ? Blocks.SWEET_BERRY_BUSH.defaultBlockState() : null;
            case 12 -> r < 0.3 ? Blocks.SHORT_GRASS.defaultBlockState() : null;
            case 13 -> vine(e, n);
            default -> null;
        };
        if (plant != null) chunk.setBlockState(pos.set(x, ground + 1, z), plant, false);
    }

    /** Vineyard: east-west rows every 3 m, a post every 5 m, vines (persistent leaves) between. */
    private static BlockState vine(long e, long n) {
        if (Math.floorMod(n, 3) != 0) return null;
        return Math.floorMod(e, 5) == 0
                ? Blocks.OAK_FENCE.defaultBlockState()
                : Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    /** Snow on top, packed ice under it, blue ice deeper: {@code depth} blocks of the column replaced. */
    private static void glacier(ChunkAccess chunk, int x, int z, int ground, int depth, BlockPos.MutableBlockPos pos) {
        for (int i = 0; i < depth; i++) {
            BlockState s = i == 0 ? Blocks.SNOW_BLOCK.defaultBlockState()
                    : i <= 2 ? Blocks.PACKED_ICE.defaultBlockState()
                    : Blocks.BLUE_ICE.defaultBlockState();
            chunk.setBlockState(pos.set(x, ground - i, z), s, false);
        }
    }
}
