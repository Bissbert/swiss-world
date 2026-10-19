package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
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
    private static final BlockState BANK = Blocks.STONE.defaultBlockState();
    private static final BlockState BANK_TOP = Blocks.GRAVEL.defaultBlockState();

    @Override
    public void apply(GenContext ctx) {
        ChunkAccess chunk = ctx.chunk;
        ColumnGrid g = ColumnGrid.of(ctx);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx, z = z0 + lz;
                int bottom = g.ground(x, z) + 1;
                WaterPlan.Fill f = WaterPlan.fill(g, x, z);
                if (f != null) {
                    for (int y = bottom; y <= f.sourceTop(); y++) chunk.setBlockState(pos.set(x, y, z), WATER, false);
                    for (int y = f.sourceTop() + 1; y < f.fallTop(); y++) chunk.setBlockState(pos.set(x, y, z), FALLING, false);
                    if (f.falls()) chunk.setBlockState(pos.set(x, f.fallTop(), z), f.fedTop() ? FLOWING : FALLING, false);
                    continue;
                }
                int bank = WaterPlan.bankTop(g, x, z);
                for (int y = bottom; y <= bank; y++) {
                    chunk.setBlockState(pos.set(x, y, z), y == bank ? BANK_TOP : BANK, false);
                }
            }
        }
    }
}
