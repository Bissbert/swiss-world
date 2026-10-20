package ch.bissbert.swissworld.gen.ground;

/**
 * The zone rules: one column's metrics in, one {@link Zone} out. Pure, no Minecraft classes; {@link ZoneMap} gathers
 * the metrics (all from cell data within {@link ZoneMap#REACH} columns), so a column classifies the same in every chunk.
 * The rules are listed in NOTES-mod-texture.md ("Zone rules").
 */
public final class GroundZones {
    private GroundZones() {}

    /** No such neighbour within reach. */
    public static final int FAR = 99;

    /** Metrics of one column. Mutable and reused while a chunk is classified. */
    public static final class Input {
        public boolean present = true, wet, building;
        public int surf, waterKind, overlay, flags;
        /** Metres. */
        public double h;
        public double slope, northness, hollow, tpi;
        /** Canopy height in metres, -1 unknown. */
        public int canopy = -1;
        /** Distances (columns, rounded) to the nearest wet, glacier, non-forest and forest column within reach. */
        public int waterDist = FAR, glacierDist = FAR, openDist = FAR, forestDist = FAR;
        /** Kind (WKND) of the nearest water. */
        public int nearWaterKind;
        /** A cliff (slope over 45 degrees) stands above this column within reach. */
        public boolean cliffAbove;
        public Region region = Region.UNKNOWN;
        public Belt belt = Belt.UNKNOWN;
        public Lith lith = Lith.UNKNOWN;
        public double treeline = 2000, snowLine = 2800;
        /** Snow depth in eighths ({@link Snow}). */
        public int snow;
        /** TOPO flow code (0 none, 1 gully, 2 channel, 3 wet hollow), -1 unknown (no TOPO). */
        public int flow = -1;
        /** Raw HABT byte (bog bits, Aue type in bits 3-5), -1 unknown (no HABT). */
        public int habitat = -1;
    }

    public static final int FLOW_GULLY = 1, FLOW_CHANNEL = 2, FLOW_HOLLOW = 3;
    public static final int HAB_RAISED_BOG = 0x01, HAB_BOG_MARGIN = 0x02, HAB_FEN = 0x04;
    public static final int AUE_GLACIER_FOREFIELD = 1, AUE_ALPINE_PLAIN = 2, AUE_RIVER = 3, AUE_DELTA = 4, AUE_LAKE_SHORE = 5;

    public static Zone zone(Input c) {
        if (!c.present) return Zone.NONE;
        if (c.wet) return Zone.WATER;
        if (c.surf == 6) return glacier(c);
        if (c.building || c.surf == 10) return Zone.URBAN_SEALED;
        Zone z = c.habitat > 0 ? habitat(c) : null;
        if (z == null) z = land(c);
        if (c.snow >= 12 && z.group != Zone.Group.ROCK) return Zone.FIRN;
        if (c.snow > 0 && c.snow < 8 && c.hollow > 1 && c.h < c.snowLine) return Zone.SNOW_PATCH;
        return z;
    }

    private static Zone glacier(Input c) {
        // crevasses open where the ice bends over a convex break of slope; steeper than 45 degrees is an ice wall
        if (c.slope > 25 && c.slope < 45 && c.hollow < -0.15) return Zone.CREVASSED;
        if (c.snow < 4 && c.openDist <= 4) return Zone.DEBRIS_GLACIER;
        return Zone.GLACIER;
    }

    private static boolean forestSurf(int surf) {
        return surf == 1 || surf == 2 || surf == 3;
    }

    /**
     * The federal mire and floodplain inventories (HABT), where known: they win over the land-cover guesses, but not
     * over cultivated land (fields, orchards, vineyards, gardens) and not on steep ground. Null = no say.
     */
    private static Zone habitat(Input c) {
        int hab = c.habitat, aue = hab >>> 3 & 7;
        boolean tilled = c.surf == 11 || c.surf == 12 || c.surf == 13 || c.surf == 16;
        if (tilled) return null;
        if (forestSurf(c.surf) || c.surf == 14) {
            // forest in a floodplain is riparian (Auenwald); bog forest keeps its forest zone
            return (aue == AUE_RIVER || aue == AUE_DELTA) && c.h < 1300 && c.slope < 15 ? Zone.RIPARIAN : null;
        }
        if ((hab & HAB_RAISED_BOG) != 0 && c.slope < 12) return Zone.RAISED_BOG;
        if ((hab & (HAB_BOG_MARGIN | HAB_FEN)) != 0 && c.slope < 15) return Zone.WET_MEADOW;
        switch (aue) {
            case AUE_GLACIER_FOREFIELD -> {
                if (c.slope < 25) return c.tpi > 2 ? Zone.MORAINE : Zone.FOREFIELD;
            }
            case AUE_ALPINE_PLAIN -> {
                if (c.slope < 10) return c.waterDist <= 4 || c.surf == 5 || c.surf == 15 ? Zone.RIVERBED_GRAVEL : null;
            }
            case AUE_RIVER, AUE_DELTA -> {
                if (c.slope < 8) return c.surf == 15 || c.surf == 8 ? Zone.RIVERBED_GRAVEL : Zone.FLOODPLAIN;
            }
            case AUE_LAKE_SHORE -> {
                if (c.slope < 8) {
                    if (c.h >= 1500) return Zone.ALPINE_LAKE_SHORE;
                    return c.waterDist <= 6 ? Zone.REED_SHORE : Zone.WET_MEADOW;
                }
            }
            default -> {
            }
        }
        return null;
    }

    private static Zone land(Input c) {
        boolean alpine = c.belt == Belt.ALPINE || c.belt == Belt.NIVAL;
        switch (c.surf) {
            case 4 -> {
                return rock(c);
            }
            case 5 -> {
                return loose(c);
            }
            case 7, 8, 15 -> {
                if (c.waterKind == 1 || c.waterKind == 4 || c.waterKind == 5 || c.nearWaterKind == 1 && c.waterDist <= 3) {
                    return c.h >= 1500 ? Zone.ALPINE_LAKE_SHORE : Zone.RIVERBED_GRAVEL;
                }
                return Zone.RIVERBED_GRAVEL;
            }
            case 9 -> {
                return c.h > 900 && c.slope < 4 && c.waterDist > 3 ? Zone.RAISED_BOG : Zone.WET_MEADOW;
            }
            case 11 -> {
                return Zone.GARDEN;
            }
            case 12 -> {
                return Zone.ORCHARD;
            }
            case 13 -> {
                return Zone.VINEYARD;
            }
            case 16 -> {
                return Zone.ARABLE;
            }
            default -> {
            }
        }
        // grass on a wall is rock
        if (c.slope > 50 || c.slope > 42 && alpine) return cliff(c);
        if (forestSurf(c.surf) || c.surf == 14) return forest(c, alpine);
        return open(c, alpine);
    }

    private static Zone rock(Input c) {
        if (c.slope < 25 && c.cliffAbove) return Zone.SCREE_CONE;
        if (c.slope < 18) {
            if (c.lith == Lith.LIMESTONE) return Zone.KARST;
            return nearGlacier(c) ? Zone.FOREFIELD : Zone.BOULDER_FIELD;
        }
        return cliff(c);
    }

    private static Zone cliff(Input c) {
        return switch (c.lith) {
            case LIMESTONE -> Zone.LIMESTONE_CLIFF;
            case SCHIST, MOLASSE -> Zone.SCHIST_SLOPE;
            case CRYSTALLINE -> Zone.CRYSTALLINE_CLIFF;
            // unknown: by region, crystalline massifs in the central Alps and the south, limestone on the edges
            default -> switch (c.region) {
                case WEST_CENTRAL, EAST_CENTRAL, SOUTH_FLANK -> Zone.CRYSTALLINE_CLIFF;
                case NORTH_FLANK -> c.h > 2500 ? Zone.CRYSTALLINE_CLIFF : Zone.LIMESTONE_CLIFF;
                default -> Zone.LIMESTONE_CLIFF;
            };
        };
    }

    /** Glacier in reach, or loose ground within 500 m below the snow line (where the glaciers of 1850 reached). */
    private static boolean nearGlacier(Input c) {
        return c.glacierDist != FAR || c.h > c.snowLine - 500;
    }

    private static Zone loose(Input c) {
        if (c.glacierDist != FAR) return c.tpi > 2 ? Zone.MORAINE : Zone.FOREFIELD;
        if (c.slope >= 20) {
            // TOPO flow where known (a gully or channel drains through), else a local hollow stands in
            boolean channel = c.flow >= 0 ? c.flow == FLOW_GULLY || c.flow == FLOW_CHANNEL : c.hollow > 1.5;
            return channel && c.slope < 40 && c.belt != Belt.NIVAL ? Zone.DEBRIS_CHANNEL : Zone.SCREE_CONE;
        }
        if (nearGlacier(c)) return c.tpi > 2 ? Zone.MORAINE : Zone.FOREFIELD;
        return Zone.BOULDER_FIELD;
    }

    private static Zone forest(Input c, boolean alpine) {
        if (alpine || c.surf == 14 && c.belt == Belt.SUBALPINE) return Zone.KRUMMHOLZ;
        if (c.waterDist <= 4 && (c.nearWaterKind == 2 || c.nearWaterKind == 3) && c.h < 1300 && c.slope < 15) return Zone.RIPARIAN;
        if (c.canopy == 0 || c.canopy > 0 && c.canopy < 2) return c.slope > 30 && c.belt != Belt.COLLINE ? Zone.AVALANCHE_TRACK : Zone.CLEARING;
        if (c.openDist <= 2 || c.surf == 14) return Zone.FOREST_EDGE;
        double south = Math.max(0, -c.northness) * Math.min(1, c.slope / 20);
        switch (c.belt) {
            case COLLINE -> {
                if (c.region == Region.SOUTH_FLANK) return Zone.CHESTNUT;
                if (c.surf == 2) return south > 0.5 && c.slope > 20 ? Zone.DRY_PINE : Zone.SPRUCE;
                return c.surf == 1 ? Zone.BEECH : Zone.MIXED_BROADLEAF;
            }
            case MONTANE -> {
                if (south > 0.5 && c.slope > 20 && (c.region == Region.WEST_CENTRAL || c.region == Region.EAST_CENTRAL)) return Zone.DRY_PINE;
                if (c.surf == 1) return c.region == Region.SOUTH_FLANK && c.h < 1000 ? Zone.CHESTNUT : Zone.BEECH;
                if (c.region == Region.WEST_CENTRAL || c.region == Region.EAST_CENTRAL) return Zone.SPRUCE;
                return Zone.FIR_SPRUCE;
            }
            default -> {
                // subalpine: larch and stone pine in the inner Alps and in the upper 200 m, spruce elsewhere
                boolean inner = c.region == Region.WEST_CENTRAL || c.region == Region.EAST_CENTRAL || c.region == Region.SOUTH_FLANK;
                return inner || c.h > c.treeline - 200 ? Zone.LARCH_PINE : Zone.SPRUCE;
            }
        }
    }

    private static Zone open(Input c, boolean alpine) {
        if (c.waterDist <= 2 && (c.nearWaterKind == 1 || c.nearWaterKind == 4 || c.nearWaterKind == 5)) {
            if (c.h >= 1500) return Zone.ALPINE_LAKE_SHORE;
            if (c.slope < 8) return Zone.REED_SHORE;
        }
        if (c.waterDist <= 6 && c.nearWaterKind == 2 && c.slope < 5 && c.h < 1000) return Zone.FLOODPLAIN;
        // a wet hollow (TOPO: valley floor, water draining in, barely any outflow) below the alpine belt
        if (c.flow == FLOW_HOLLOW && !alpine && c.slope < 4) return Zone.WET_MEADOW;
        if (c.cliffAbove && c.slope > 22 && c.slope < 40 && c.belt != Belt.COLLINE) return Zone.SCREE_CONE;
        if (alpine) {
            if (c.slope > 30 || c.h > c.treeline + 450 || c.tpi > 4) return Zone.ALPINE_TURF;
            return Zone.ALPINE_PASTURE;
        }
        if ((c.belt == Belt.SUBALPINE || c.belt == Belt.MONTANE) && c.slope > 32 && c.forestDist <= 6) return Zone.AVALANCHE_TRACK;
        if (c.surf == 17) return Zone.ALPINE_PASTURE;
        double south = Math.max(0, -c.northness) * Math.min(1, c.slope / 20);
        if (south > 0.6 && c.slope > 22 && c.belt != Belt.SUBALPINE) return Zone.DRY_GRASSLAND;
        if (c.belt == Belt.SUBALPINE) return Zone.PASTURE;
        return c.slope < 8 ? Zone.HAY_MEADOW : Zone.PASTURE;
    }
}
