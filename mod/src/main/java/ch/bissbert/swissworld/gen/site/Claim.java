package ch.bissbert.swissworld.gen.site;

/**
 * What owns a block column in the {@link SitePlan}. The ordinal is the priority in which the plan resolves the site
 * (lower first: water, then rails, roads and paths, structures, buildings and their landings, then the ground graded
 * around all of them); a column has exactly one claim. Ordinals are stable: new claims are added at the end.
 */
public enum Claim {
    /** Water stands on the column (lake, river, stream, reservoir, pond, waterfall, reed/cane step corner); ocean. */
    WATER,
    /** Dry ground raised to the water beside it (WaterPlan#bankTop). */
    BANK,
    /** A rail way (bed, rack bed, rail under a road bridge), on the ground, on a bridge or over a tunnel. */
    RAIL,
    /** A road, platform, runway or parking area. */
    ROAD,
    /** A farm track, footpath, steps or sports pitch. */
    PATH,
    /** Bridge, tunnel profile or wall near the surface, dam: built, not ground. */
    STRUCTURE,
    /** A building's body or eaves. */
    BUILDING,
    /** In front of a door: the landing, the way out and its steps. */
    LANDING,
    /** Ground cut or filled to a way beside it (TransportPlan#earthwork). */
    EARTHWORK,
    /** Natural ground; also the surface over a deep tunnel. */
    FREE;

    private static final Claim[] VALUES = values();

    public static Claim of(int ordinal) {
        return VALUES[ordinal];
    }

    /** RAIL, ROAD or PATH. */
    public boolean way() {
        return this == RAIL || this == ROAD || this == PATH;
    }
}
