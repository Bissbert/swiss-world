package ch.bissbert.swissworld.gen.stage;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import ch.bissbert.swissworld.gen.SwissChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Ground column from TERR + SURF, flat ocean where there is no cell or INSD = 0. Owner: mod-core.
 * Minimal version so the other stages have ground to build on; mod-core optimises it (section fills) and refines surfaces.
 */
public final class TerrainStage implements GenStage {
    public static final int OCEAN_FLOOR_DEPTH = 30;

    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    @Override
    public void apply(GenContext ctx) {
        ChunkAccess chunk = ctx.chunk;
        int minY = chunk.getMinBuildHeight();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx, z = z0 + lz;
                pos.set(x, minY, z);
                chunk.setBlockState(pos, BEDROCK, false);
                SwdCell cell = ctx.cellAt(x, z);
                int dx = SwissCoords.dx(ctx.coords.east(x)), dz = SwissCoords.dz(ctx.coords.north(z));
                if (cell == null || !cell.inside(dx, dz)) {
                    ocean(chunk, pos, x, z, minY);
                    continue;
                }
                int top = (int) Math.floor(SwissCoords.toY(cell.terrain(dx + 0.5, dz + 0.5)));
                BlockState surface = surface(cell.surf(dx, dz));
                BlockState under = surface == STONE || surface == Blocks.GRAVEL.defaultBlockState() ? STONE : DIRT;
                for (int y = minY + 1; y <= top; y++) {
                    BlockState s = y == top ? surface : y > top - 4 ? under : STONE;
                    chunk.setBlockState(pos.set(x, y, z), s, false);
                }
            }
        }
    }

    private static void ocean(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, int minY) {
        int sea = SwissChunkGenerator.SEA_LEVEL_Y;
        int floor = sea - OCEAN_FLOOR_DEPTH;
        for (int y = minY + 1; y <= sea; y++) {
            BlockState s = y < floor - 3 ? STONE : y <= floor ? SAND : WATER;
            chunk.setBlockState(pos.set(x, y, z), s, false);
        }
    }

    /** SURF class -> top block (docs/swd-format.md §4). Water columns get their bed here; WaterStage adds the water. */
    static BlockState surface(int surf) {
        return switch (surf) {
            case 4 -> STONE;
            case 5, 15 -> Blocks.GRAVEL.defaultBlockState();
            case 6 -> Blocks.SNOW_BLOCK.defaultBlockState();
            case 7, 8 -> Blocks.GRAVEL.defaultBlockState();
            case 9 -> Blocks.MUD.defaultBlockState();
            case 10 -> Blocks.STONE.defaultBlockState();
            case 16 -> Blocks.COARSE_DIRT.defaultBlockState();
            default -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
    }
}
