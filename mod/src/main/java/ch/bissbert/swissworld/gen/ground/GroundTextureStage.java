package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dresses natural ground by zone ({@link GroundTexture}): runs after Loot, before Vegetation. Owner: mod-texture.
 * Touches only columns without water, building or surface overlay ({@link ZoneMap#natural}), and there only a top block
 * that an earlier stage left as plain ground (grass, dirt, coarse dirt, stone, gravel, mud), plus up to
 * {@link GroundTexture#UNDER} plain blocks below it. Decks, buildings, water, bridges and tunnels are never touched.
 */
public final class GroundTextureStage implements GenStage {
    /** How far above and below the TERR top the real top may lie (banks, earthwork cuts and fills). */
    static final int SCAN = 16;

    @Override
    public void apply(GenContext ctx) {
        apply(zones(ctx), BlockAccess.of(ctx.chunk));
    }

    /** The chunk's zones, computed once per chunk (the biome resolver usually asks first). */
    public static ZoneMap zones(GenContext ctx) {
        int x0 = ctx.chunk.getPos().getMinBlockX(), z0 = ctx.chunk.getPos().getMinBlockZ();
        return ctx.memo(ZoneMap.class, () -> ZoneMap.of(ctx.coords, ctx::cellAt, x0, z0));
    }

    public static void apply(ZoneMap zm, BlockAccess b) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = zm.x0 + lx, z = zm.z0 + lz;
                if (!zm.natural(x, z)) continue;
                int y = top(b, x, z, ColumnGrid.y(zm.metres(x, z)));
                if (y == ColumnGrid.NONE || !plain(b.get(x, y, z).getBlock())) continue;
                Block top = GroundTexture.top(zm, x, z);
                if (top == null) continue;
                b.set(x, y, z, top.defaultBlockState());
                for (int d = 1; d <= GroundTexture.UNDER; d++) {
                    if (!plain(b.get(x, y - d, z).getBlock())) break;
                    b.set(x, y - d, z, GroundTexture.under(top, d));
                }
            }
        }
    }

    /** The highest solid block within {@link #SCAN} of {@code ground}, or NONE if water (or nothing) is on top. */
    static int top(BlockAccess b, int x, int z, int ground) {
        for (int y = ground + SCAN; y >= ground - SCAN; y--) {
            BlockState s = b.get(x, y, z);
            if (s.isAir()) continue;
            return s.getFluidState().isEmpty() ? y : ColumnGrid.NONE;
        }
        return ColumnGrid.NONE;
    }

    /** Ground as TerrainStage, WaterStage banks and earthworks leave it: the only blocks this stage replaces. */
    static boolean plain(Block b) {
        return b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.COARSE_DIRT || b == Blocks.STONE || b == Blocks.GRAVEL
                || b == Blocks.MUD;
    }
}
