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
    private static final BlockState LIP = WATER.setValue(LiquidBlock.LEVEL, 2);
    private static final BlockState REED = Blocks.SUGAR_CANE.defaultBlockState();
    private static final BlockState REED_BED = Blocks.SAND.defaultBlockState();
    private static final BlockState FILL = Blocks.DIRT.defaultBlockState();
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
                int[] face = WaterPlan.bedFace(g, x, z);
                if (face != null) {
                    // the bed's stone where deeper water beside it shows it: gravel, laid bottom-up on solid ground
                    for (int y = face[0]; y <= face[1]; y++) {
                        if (chunk.getBlockState(pos.set(x, y, z)).is(Blocks.STONE)
                                && chunk.getBlockState(pos.set(x, y - 1, z)).isSolid()) {
                            chunk.setBlockState(pos.set(x, y, z), BANK_TOP, false);
                        }
                    }
                }
                WaterPlan.Fill f = WaterPlan.fill(g, x, z);
                if (f != null && f.reed()) {
                    int s = f.sourceTop();
                    for (int y = bottom; y < s - 1; y++) chunk.setBlockState(pos.set(x, y, z), FILL, false);
                    chunk.setBlockState(pos.set(x, s - 1, z), REED_BED, false);
                    chunk.setBlockState(pos.set(x, s, z), REED, false);
                    chunk.setBlockState(pos.set(x, f.fallTop(), z), FLOWING, false);
                    continue;
                }
                if (f != null && f.cane()) {
                    int s = f.sourceTop();
                    for (int y = bottom; y < s; y++) chunk.setBlockState(pos.set(x, y, z), FILL, false);
                    chunk.setBlockState(pos.set(x, s, z), REED_BED, false);
                    for (int y = s + 1; y <= f.fallTop(); y++) chunk.setBlockState(pos.set(x, y, z), REED, false);
                    continue;
                }
                if (f != null) {
                    for (int y = bottom; y <= f.sourceTop(); y++) chunk.setBlockState(pos.set(x, y, z), WATER, false);
                    if (f.lip()) chunk.setBlockState(pos.set(x, f.sourceTop() + 1, z), LIP, false);
                    if (f.rock()) {
                        for (int y = f.sourceTop() + 1; y <= f.fallTop(); y++) chunk.setBlockState(pos.set(x, y, z), BANK, false);
                        continue;
                    }
                    for (int y = f.sourceTop() + 1; y < f.fallTop(); y++) chunk.setBlockState(pos.set(x, y, z), FALLING, false);
                    if (f.falls()) chunk.setBlockState(pos.set(x, f.fallTop(), z), FLOWING, false);
                    continue;
                }
                int bank = WaterPlan.bankTop(g, x, z);
                for (int y = bottom; y <= bank; y++) {
                    // earth under the gravel: a bank side that shows in the water is no stone wall
                    chunk.setBlockState(pos.set(x, y, z), y == bank ? BANK_TOP : FILL, false);
                }
            }
        }
    }
}
