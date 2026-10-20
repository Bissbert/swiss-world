package ch.bissbert.swissworld.gen.ground;

/**
 * Ground zones (zone taxonomy §C), 6-bit ids. The ordinal is the id: never reorder, only append (ids are packed into
 * {@link ZoneId} and may be stored by other workstreams).
 */
public enum Zone {
    NONE(Group.NONE),
    // open land
    HAY_MEADOW(Group.OPEN), PASTURE(Group.OPEN), ALPINE_PASTURE(Group.OPEN), ALPINE_TURF(Group.OPEN),
    DRY_GRASSLAND(Group.OPEN), WET_MEADOW(Group.OPEN), RAISED_BOG(Group.OPEN), FLOODPLAIN(Group.OPEN),
    ARABLE(Group.OPEN), ORCHARD(Group.OPEN), VINEYARD(Group.OPEN), GARDEN(Group.OPEN), URBAN_SEALED(Group.URBAN),
    INDUSTRIAL(Group.URBAN), SPORTS(Group.OPEN),
    // forest
    BEECH(Group.FOREST), MIXED_BROADLEAF(Group.FOREST), SPRUCE(Group.FOREST), FIR_SPRUCE(Group.FOREST),
    LARCH_PINE(Group.FOREST), DRY_PINE(Group.FOREST), RIPARIAN(Group.FOREST), CHESTNUT(Group.FOREST),
    KRUMMHOLZ(Group.FOREST), FOREST_EDGE(Group.FOREST), CLEARING(Group.FOREST), AVALANCHE_TRACK(Group.OPEN),
    // rock and loose ground
    LIMESTONE_CLIFF(Group.ROCK), CRYSTALLINE_CLIFF(Group.ROCK), SCHIST_SLOPE(Group.ROCK), KARST(Group.ROCK),
    BOULDER_FIELD(Group.LOOSE), SCREE_CONE(Group.LOOSE), MORAINE(Group.LOOSE), FOREFIELD(Group.LOOSE),
    DEBRIS_CHANNEL(Group.LOOSE), RIVERBED_GRAVEL(Group.LOOSE), REED_SHORE(Group.OPEN), ALPINE_LAKE_SHORE(Group.LOOSE),
    // snow and ice
    SNOW_PATCH(Group.SNOW), FIRN(Group.SNOW), GLACIER(Group.ICE), DEBRIS_GLACIER(Group.ICE), CREVASSED(Group.ICE),
    /** Not in the taxonomy: a water column (lake, river bed). */
    WATER(Group.WATER);

    public enum Group { NONE, OPEN, URBAN, FOREST, ROCK, LOOSE, SNOW, ICE, WATER }

    public final Group group;
    private static final Zone[] ALL = values();

    Zone(Group group) {
        this.group = group;
    }

    public int id() {
        return ordinal();
    }

    public static Zone byId(int id) {
        return id >= 0 && id < ALL.length ? ALL[id] : NONE;
    }

    public boolean forest() {
        return group == Group.FOREST;
    }

    public boolean glacier() {
        return group == Group.ICE;
    }

    static {
        if (ALL.length > 64) throw new IllegalStateException("zone ids are 6 bits");
    }
}
