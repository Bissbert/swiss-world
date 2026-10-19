package ch.bissbert.swissworld.gen.building;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Vanilla block set for one building (PLAN §8); variants within a style are picked by gid. */
public record Palette(Block wall, Block base, Block foundation, Block floor, Block roofStairs, Block roofSlab,
                      Block roof, Block pane, Block door, Block partition, Block steps, Block chair, Block table,
                      Block counter, Block bed) {

    private static final Block[] BEDS = {Blocks.RED_BED, Blocks.WHITE_BED, Blocks.BLUE_BED, Blocks.GREEN_BED,
            Blocks.LIGHT_GRAY_BED, Blocks.BROWN_BED};

    public static Palette of(Style style, long gid) {
        int v = Hash.pick(gid, 21, 3);
        Block bed = BEDS[Hash.pick(gid, 22, BEDS.length)];
        return switch (style) {
            case CHALET -> new Palette(v == 0 ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_PLANKS, Blocks.STONE_BRICKS,
                    Blocks.COBBLESTONE, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB,
                    Blocks.DARK_OAK_PLANKS, Blocks.GLASS_PANE, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_PLANKS,
                    Blocks.COBBLESTONE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_SLAB, bed);
            case TOWN_HOUSE, HOTEL -> new Palette(
                    v == 0 ? Blocks.WHITE_CONCRETE : v == 1 ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.CALCITE,
                    Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.OAK_PLANKS,
                    Hash.pick(gid, 23, 2) == 0 ? Blocks.BRICK_STAIRS : Blocks.GRANITE_STAIRS,
                    Hash.pick(gid, 23, 2) == 0 ? Blocks.BRICK_SLAB : Blocks.GRANITE_SLAB,
                    Hash.pick(gid, 23, 2) == 0 ? Blocks.BRICKS : Blocks.POLISHED_GRANITE, Blocks.GLASS_PANE,
                    Blocks.OAK_DOOR, Blocks.WHITE_TERRACOTTA, Blocks.STONE_BRICK_STAIRS, Blocks.OAK_STAIRS,
                    Blocks.OAK_FENCE, Blocks.OAK_SLAB, bed);
            case OLD_TOWN -> new Palette(v == 2 ? Blocks.WHITE_TERRACOTTA : Blocks.CALCITE, Blocks.STONE_BRICKS,
                    Blocks.STONE_BRICKS, Blocks.DARK_OAK_PLANKS, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB, Blocks.BRICKS,
                    Blocks.GLASS_PANE, Blocks.DARK_OAK_DOOR, Blocks.CALCITE, Blocks.STONE_BRICK_STAIRS,
                    Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_SLAB, bed);
            case MODERN -> new Palette(v == 0 ? Blocks.WHITE_CONCRETE : v == 1 ? Blocks.SMOOTH_QUARTZ
                    : Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.STONE, Blocks.BIRCH_PLANKS,
                    Blocks.POLISHED_ANDESITE_STAIRS, Blocks.SMOOTH_STONE_SLAB, Blocks.GRAY_CONCRETE,
                    Blocks.GLASS_PANE, Blocks.BIRCH_DOOR, Blocks.WHITE_CONCRETE, Blocks.POLISHED_ANDESITE_STAIRS,
                    Blocks.BIRCH_STAIRS, Blocks.BIRCH_FENCE, Blocks.SMOOTH_STONE_SLAB, bed);
            case BARN -> new Palette(v == 0 ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_PLANKS,
                    Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COARSE_DIRT, Blocks.SPRUCE_STAIRS,
                    Blocks.SPRUCE_SLAB, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_DOOR,
                    Blocks.SPRUCE_PLANKS, Blocks.COBBLESTONE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE,
                    Blocks.SPRUCE_SLAB, bed);
            case CHURCH -> new Palette(v == 0 ? Blocks.CALCITE : Blocks.WHITE_CONCRETE, Blocks.STONE_BRICKS,
                    Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.DEEPSLATE_TILE_STAIRS,
                    Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILES, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE,
                    Blocks.DARK_OAK_DOOR, Blocks.CALCITE, Blocks.STONE_BRICK_STAIRS, Blocks.DARK_OAK_STAIRS,
                    Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_SLAB, bed);
            case INDUSTRIAL -> new Palette(v == 0 ? Blocks.BRICKS : Blocks.LIGHT_GRAY_CONCRETE, Blocks.GRAY_CONCRETE,
                    Blocks.STONE, Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE_STAIRS, Blocks.SMOOTH_STONE_SLAB,
                    Blocks.POLISHED_ANDESITE, Blocks.GLASS_PANE, Blocks.SPRUCE_DOOR, Blocks.GRAY_CONCRETE,
                    Blocks.STONE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE, Blocks.SMOOTH_STONE_SLAB, bed);
            case TANK -> new Palette(v == 0 ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.WHITE_CONCRETE, Blocks.GRAY_CONCRETE,
                    Blocks.STONE, Blocks.SMOOTH_STONE, Blocks.STONE_STAIRS, Blocks.SMOOTH_STONE_SLAB, Blocks.SMOOTH_STONE,
                    Blocks.GLASS_PANE, Blocks.IRON_DOOR, Blocks.GRAY_CONCRETE, Blocks.STONE_STAIRS, Blocks.STONE_STAIRS,
                    Blocks.IRON_BARS, Blocks.SMOOTH_STONE_SLAB, bed);
            case GREENHOUSE -> new Palette(Blocks.GLASS, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.COARSE_DIRT,
                    Blocks.STONE_BRICK_STAIRS, Blocks.SMOOTH_STONE_SLAB, Blocks.GLASS, Blocks.GLASS_PANE,
                    Blocks.BIRCH_DOOR, Blocks.SPRUCE_PLANKS, Blocks.STONE_BRICK_STAIRS, Blocks.SPRUCE_STAIRS,
                    Blocks.SPRUCE_FENCE, Blocks.SPRUCE_SLAB, bed);
            case WALL -> new Palette(v == 0 ? Blocks.STONE_BRICKS : v == 1 ? Blocks.COBBLESTONE : Blocks.STONE,
                    Blocks.STONE_BRICKS, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS,
                    Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICKS, Blocks.GLASS_PANE, Blocks.OAK_DOOR,
                    Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.COBBLESTONE_WALL,
                    Blocks.STONE_BRICK_SLAB, bed);
            case BRIDGE -> new Palette(Blocks.SPRUCE_PLANKS, Blocks.STRIPPED_SPRUCE_LOG, Blocks.COBBLESTONE,
                    Blocks.SPRUCE_PLANKS, v == 0 ? Blocks.DARK_OAK_STAIRS : Blocks.SPRUCE_STAIRS,
                    v == 0 ? Blocks.DARK_OAK_SLAB : Blocks.SPRUCE_SLAB,
                    v == 0 ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_DOOR,
                    Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE,
                    Blocks.SPRUCE_SLAB, bed);
            case CANOPY -> new Palette(Blocks.SPRUCE_PLANKS, v == 0 ? Blocks.STRIPPED_SPRUCE_LOG : Blocks.SPRUCE_LOG,
                    Blocks.COBBLESTONE, Blocks.SPRUCE_PLANKS, v == 2 ? Blocks.SMOOTH_STONE_SLAB : Blocks.SPRUCE_STAIRS,
                    v == 2 ? Blocks.SMOOTH_STONE_SLAB : Blocks.SPRUCE_SLAB,
                    v == 2 ? Blocks.SMOOTH_STONE : Blocks.SPRUCE_PLANKS, Blocks.GLASS_PANE, Blocks.SPRUCE_DOOR,
                    Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_FENCE,
                    Blocks.SPRUCE_SLAB, bed);
            case COMMERCIAL -> new Palette(v == 0 ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.SMOOTH_SANDSTONE,
                    Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.GRANITE_STAIRS,
                    Blocks.GRANITE_SLAB, Blocks.POLISHED_GRANITE, Blocks.GLASS_PANE, Blocks.BIRCH_DOOR,
                    Blocks.WHITE_CONCRETE, Blocks.STONE_BRICK_STAIRS, Blocks.BIRCH_STAIRS, Blocks.BIRCH_FENCE,
                    Blocks.BIRCH_SLAB, bed);
        };
    }
}
