package ch.bissbert.swissworld.gen.building;

import java.util.List;

/** Room purposes. Each has a loot table {@code swissworld:chests/<id>} and an ordered prop list (container first). */
public enum RoomType {
    KITCHEN("residential_kitchen", PropKind.BARREL, PropKind.SMOKER, PropKind.CRAFTING_TABLE, PropKind.CAULDRON,
            PropKind.TABLE, PropKind.CHAIR, PropKind.FLOWER_POT),
    LIVING("residential_living", PropKind.CHEST, PropKind.BOOKSHELF, PropKind.TABLE, PropKind.CHAIR, PropKind.BOOKSHELF,
            PropKind.CHAIR, PropKind.FLOWER_POT),
    BEDROOM("residential_bedroom", PropKind.CHEST, PropKind.BED, PropKind.BOOKSHELF, PropKind.FLOWER_POT),
    SHOP("shop", PropKind.BARREL, PropKind.COUNTER, PropKind.COUNTER, PropKind.COUNTER, PropKind.CARTOGRAPHY_TABLE,
            PropKind.FLOWER_POT),
    OFFICE("office", PropKind.CHEST, PropKind.LECTERN, PropKind.BOOKSHELF, PropKind.TABLE, PropKind.CHAIR,
            PropKind.BOOKSHELF),
    WORKSHOP("workshop", PropKind.CHEST, PropKind.CRAFTING_TABLE, PropKind.ANVIL, PropKind.FURNACE,
            PropKind.GRINDSTONE, PropKind.SMITHING_TABLE, PropKind.FURNACE),
    HOTEL("hotel", PropKind.CHEST, PropKind.BED, PropKind.TABLE, PropKind.CHAIR, PropKind.FLOWER_POT),
    CHURCH("church", PropKind.CHEST, PropKind.LECTERN, PropKind.CANDLE, PropKind.PEW, PropKind.PEW, PropKind.CANDLE,
            PropKind.PEW, PropKind.PEW),
    BARN("barn", PropKind.BARREL, PropKind.HAY, PropKind.HAY, PropKind.COMPOSTER, PropKind.HAY, PropKind.HAY),
    ALPINE_HUT("alpine_hut", PropKind.BARREL, PropKind.BED, PropKind.FURNACE, PropKind.CRAFTING_TABLE, PropKind.TABLE,
            PropKind.CHAIR),
    STORAGE("storage", PropKind.BARREL, PropKind.HAY, PropKind.COMPOSTER);

    public final String id;
    public final List<PropKind> props;

    RoomType(String id, PropKind... props) {
        this.id = id;
        this.props = List.of(props);
    }

    public String lootPath() {
        return "chests/" + id;
    }

    public static RoomType byId(String id) {
        for (RoomType t : values()) if (t.id.equals(id)) return t;
        return null;
    }

    /** Room purpose by style, storey and position in the storey's room list. */
    public static RoomType of(Style style, int floor, int idx, int count, double baseH) {
        return switch (style) {
            case CHURCH -> CHURCH;
            case BARN -> BARN;
            case INDUSTRIAL -> WORKSHOP;
            case GREENHOUSE, TANK, WALL, BRIDGE -> STORAGE;
            case COMMERCIAL -> floor == 0 ? SHOP : OFFICE;
            case HOTEL -> floor > 0 ? HOTEL : idx == 0 ? LIVING : KITCHEN;
            default -> {
                if (baseH >= 1600) yield ALPINE_HUT;
                if (floor == 0) yield count == 1 || idx == 1 ? KITCHEN : LIVING;
                yield idx == 1 && count >= 3 ? LIVING : BEDROOM;
            }
        };
    }
}
