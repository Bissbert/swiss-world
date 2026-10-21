package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.gen.site.SiteWriter;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import ch.bissbert.swissworld.gen.stage.TerrainStage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Lakes, rivers, waterfalls from WLVL/WKND, plus banks where water would spill. Owner: mod-surface.
 * Layout is decided by {@link WaterPlan}. Blocks go in with {@code setBlockState(.., false)} on the proto chunk, which
 * schedules no fluid ticks, and no position is marked for post-processing, so nothing flows after generation.
 */
public final class WaterStage implements GenStage {
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState FALLING = WATER.setValue(LiquidBlock.LEVEL, 8);
    private static final BlockState FLOWING = WATER.setValue(LiquidBlock.LEVEL, 1);
    private static final BlockState FILL = Blocks.DIRT.defaultBlockState();
    private static final BlockState BANK_TOP = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    /** SURF classes of water and river gravel (docs/swd-format.md §4): what a bank should not copy. */
    private static final int SURF_LAKE = 7, SURF_RIVER = 8, SURF_GRAVEL = 15;

    /**
     * The block a bank or a step corner is built of: the ground the column already shows (grass on a meadow, stone
     * in a rock gully), so a bank blends into what is around it. Gravel only where that ground would fall.
     */
    static BlockState natural(BlockState old) {
        if (old.is(Blocks.GRASS_BLOCK) || old.is(Blocks.PODZOL) || old.is(Blocks.MYCELIUM) || old.is(Blocks.MOSS_BLOCK)) return GRASS;
        if (old.isAir() || !old.isSolid() || old.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return BANK_TOP;
        return old;
    }

    /** What lies under a bank's top block: earth under grass or gravel, the same rock under rock. */
    private static BlockState under(BlockState top) {
        return top == GRASS || top == BANK_TOP ? FILL : top;
    }

    /**
     * The top block of the dry land beside a column: the nearest column within {@link WaterPlan#BANK_REACH} + 1 with
     * no water whose surface is no water or river gravel class, or null where there is none. A bank or step corner
     * built of it continues the meadow or rock around it instead of laying a gravel strip into the grass.
     */
    static BlockState landBeside(ColumnGrid g, int x, int z) {
        for (int r = 1; r <= WaterPlan.BANK_REACH + 1; r++) {
            for (int j = -r; j <= r; j++) {
                for (int i = -r; i <= r; i++) {
                    if (Math.max(Math.abs(i), Math.abs(j)) != r || !g.present(x + i, z + j)) continue;
                    if (WaterPlan.sourceLevel(g, x + i, z + j) != ColumnGrid.NONE) continue;
                    int s = g.surf(x + i, z + j);
                    if (s == SURF_LAKE || s == SURF_RIVER || s == SURF_GRAVEL) continue;
                    return natural(TerrainStage.surface(s));
                }
            }
        }
        return null;
    }

    @Override
    public void apply(GenContext ctx) {
        ChunkAccess chunk = ctx.chunk;
        ColumnGrid g = ctx.site() != null ? ctx.site().grid() : ColumnGrid.of(ctx);
        SiteWriter w = SiteWriter.of(ctx, SiteWriter.Stage.WATER);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx, z = z0 + lz;
                int bottom = g.ground(x, z) + 1;
                BlockState land = landBeside(g, x, z);
                BlockState ground = land != null ? land : natural(chunk.getBlockState(pos.set(x, bottom - 1, z)));
                int[] face = WaterPlan.bedFace(g, x, z);
                if (face != null) {
                    // the bed's stone where deeper water beside it shows it: gravel, laid bottom-up on solid ground
                    for (int y = face[0]; y <= face[1]; y++) {
                        if (chunk.getBlockState(pos.set(x, y, z)).is(Blocks.STONE)
                                && chunk.getBlockState(pos.set(x, y - 1, z)).isSolid()) {
                            w.set(x, y, z, BANK_TOP);
                        }
                    }
                }
                WaterPlan.Fill f = WaterPlan.fill(g, x, z);
                if (f != null && f.pour()) {
                    // a step's inner corner: falling water from its pit up to the flowing top, no source to turn
                    for (int y = f.floor(); y < f.fallTop(); y++) w.set(x, y, z, FALLING);
                    w.set(x, f.fallTop(), z, FLOWING);
                    continue;
                }
                if (f != null) {
                    for (int y = bottom; y <= f.sourceTop(); y++) w.set(x, y, z, WATER);
                    for (int y = f.sourceTop() + 1; y < f.fallTop(); y++) w.set(x, y, z, FALLING);
                    if (f.falls()) w.set(x, f.fallTop(), z, FLOWING);
                    continue;
                }
                int bank = WaterPlan.bankTop(g, x, z);
                for (int y = bottom; y <= bank; y++) {
                    // the ground's own top block on earth: a bank side that shows in the water is no stone wall
                    w.set(x, y, z, y == bank ? ground : under(ground));
                }
            }
        }
    }
}
