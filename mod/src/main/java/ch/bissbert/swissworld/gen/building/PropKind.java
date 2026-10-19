package ch.bissbert.swissworld.gen.building;

/** Furniture pieces; FurnishStage maps them to vanilla blocks, LootStage places the containers. */
public enum PropKind {
    CHEST, BARREL, SMOKER, FURNACE, CRAFTING_TABLE, CAULDRON, BOOKSHELF, BED, TABLE, CHAIR, FLOWER_POT, COUNTER,
    CARTOGRAPHY_TABLE, LECTERN, ANVIL, GRINDSTONE, SMITHING_TABLE, CANDLE, PEW, HAY, COMPOSTER;

    public boolean container() {
        return this == CHEST || this == BARREL;
    }
}
