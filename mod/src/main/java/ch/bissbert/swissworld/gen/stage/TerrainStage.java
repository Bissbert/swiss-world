package ch.bissbert.swissworld.gen.stage;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import ch.bissbert.swissworld.gen.SwissChunkGenerator;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Ground column from TERR + SURF, flat ocean where there is no cell or INSD = 0. Owner: mod-core.
 *
 * <p>Every column is stone up to {@code top - 4}, three {@link Column#under} blocks, then the {@link Column#surface}
 * block at {@code top}; ocean columns are the same with a sand floor at {@code sea - 30} and water up to sea level.
 * Sections that are stone in every column are replaced by a single-value palette section (no per-block writes);
 * only the sections around the surface are written block by block. That is most of the speed: at Interlaken the
 * surface is ~1600 blocks above the floor, i.e. ~100 solid sections per chunk.
 */
public final class TerrainStage implements GenStage {
    public static final int OCEAN_FLOOR_DEPTH = 30;
    /** Blocks of {@link Column#under} between stone and the surface block. */
    public static final int UNDER_DEPTH = 3;

    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    /**
     * One ground column. {@code top} is the surface block's y; {@code waterTop} the highest water block this stage
     * places (ocean only, else {@link Integer#MIN_VALUE}; lakes and rivers are WaterStage's).
     */
    public record Column(int top, BlockState surface, BlockState under, int waterTop) {
        public static final Column OCEAN = new Column(
                SwissChunkGenerator.SEA_LEVEL_Y - OCEAN_FLOOR_DEPTH, SAND, SAND, SwissChunkGenerator.SEA_LEVEL_Y);

        public BlockState at(int y) {
            if (y < top - UNDER_DEPTH) return STONE;
            if (y < top) return under;
            if (y == top) return surface;
            return y <= waterTop ? WATER : null;
        }

        public int highest() {
            return Math.max(top, waterTop);
        }
    }

    /** The column at local (dx, dz) of {@code cell}; ocean for a null cell or INSD = 0. */
    public static Column column(SwdCell cell, int dx, int dz) {
        if (cell == null || !cell.inside(dx, dz)) return Column.OCEAN;
        int top = (int) Math.floor(SwissCoords.toY(cell.terrain(dx + 0.5, dz + 0.5)));
        BlockState surface = surface(cell.surf(dx, dz));
        BlockState under = surface == STONE || surface == GRAVEL ? STONE : DIRT;
        return new Column(top, surface, under, Integer.MIN_VALUE);
    }

    @Override
    public void apply(GenContext ctx) {
        ChunkAccess chunk = ctx.chunk;
        int minY = chunk.getMinBuildHeight();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        Column[] cols = new Column[256];
        int minTop = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                Column c = column(ctx.cellAt(x, z), SwissCoords.dx(ctx.coords.east(x)), SwissCoords.dz(ctx.coords.north(z)));
                cols[lz * 16 + lx] = c;
                minTop = Math.min(minTop, c.top());
                maxY = Math.max(maxY, c.highest());
            }
        }
        maxY = Math.min(maxY, chunk.getMaxBuildHeight() - 1);
        int solidTop = minTop - UNDER_DEPTH - 1; // highest y that is stone in every column

        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            int sy = chunk.getSectionYFromSectionIndex(i) << 4;
            if (sy > maxY) break;
            LevelChunkSection section = sections[i];
            int from = sy;
            if (sy + 15 <= solidTop) {
                sections[i] = section = new LevelChunkSection(
                        new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, STONE, PalettedContainer.Strategy.SECTION_STATES),
                        section.getBiomes());
                from = sy + 16; // nothing left to write, apart from bedrock below
            }
            if (sy <= minY && minY <= sy + 15) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) section.setBlockState(lx, minY - sy, lz, BEDROCK, false);
                }
                from = Math.max(from, minY + 1);
            }
            int to = Math.min(sy + 15, maxY);
            for (int lz = 0; from <= to && lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    Column c = cols[lz * 16 + lx];
                    int colTo = Math.min(to, c.highest());
                    for (int y = from; y <= colTo; y++) section.setBlockState(lx, y - sy, lz, c.at(y), false);
                }
            }
        }

        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Column c = cols[lz * 16 + lx];
                floor.update(lx, c.top(), lz, c.surface());
                surface.update(lx, c.highest(), lz, c.at(c.highest()));
            }
        }
    }

    /** SURF class -> top block (docs/swd-format.md §4). Water columns get their bed here; WaterStage adds the water. */
    public static BlockState surface(int surf) {
        return switch (surf) {
            case 4 -> STONE;
            case 5, 15 -> GRAVEL;
            case 6 -> Blocks.SNOW_BLOCK.defaultBlockState();
            case 7, 8 -> GRAVEL;
            case 9 -> Blocks.MUD.defaultBlockState();
            case 10 -> STONE;
            case 16 -> Blocks.COARSE_DIRT.defaultBlockState();
            default -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
    }
}
