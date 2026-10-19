package ch.bissbert.swissworld.gen;

import ch.bissbert.swissworld.cell.SwdCell;

/**
 * The {@code swissworld:} biomes (PLAN.md §9) and how a column picks one. Owner: mod-core.
 * Datapack files: {@code data/swissworld/worldgen/biome/<id>.json}. Pure logic, no game classes, so it is unit-testable.
 */
public enum SwissBiome {
    URBAN, COLLINE, COLLINE_FOREST, MONTANE_FOREST, SUBALPINE_FOREST, ALPINE_MEADOW, ROCK, SCREE, GLACIER, LAKE, RIVER,
    /** Outside Switzerland. */
    OCEAN;

    /** Upper limits of the forest belts and of open meadow, metres. */
    public static final double COLLINE_MAX = 800, MONTANE_MAX = 1500, MEADOW_MIN = 1800;

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Land cover (SURF, docs/swd-format.md §4), water kind (WKND) and real terrain height h (m) of one column. */
    public static SwissBiome classify(int surf, int waterKind, double h) {
        switch (waterKind) {
            case 1, 4, 5 -> {
                return LAKE;
            }
            case 2, 3, 6 -> {
                return RIVER;
            }
            default -> {
            }
        }
        return switch (surf) {
            case 4 -> ROCK;
            case 5 -> SCREE;
            case 6 -> GLACIER;
            case 7 -> LAKE;
            case 8 -> RIVER;
            case 10, 11 -> URBAN;
            case 1, 2, 3, 14 -> h < COLLINE_MAX ? COLLINE_FOREST : h < MONTANE_MAX ? MONTANE_FOREST : SUBALPINE_FOREST;
            case 17 -> ALPINE_MEADOW;
            default -> h >= MEADOW_MIN ? ALPINE_MEADOW : COLLINE;
        };
    }

    /** One column of a cell. */
    public static SwissBiome classify(SwdCell cell, int dx, int dz) {
        if (!cell.inside(dx, dz)) return OCEAN;
        return classify(cell.surf(dx, dz), cell.waterKind(dx, dz), cell.terrain(dx + 0.5, dz + 0.5));
    }

    /** Without a cell (not loaded yet): a guess from the real height only. Never used for generated chunks. */
    public static SwissBiome fallback(double h) {
        if (h < MEADOW_MIN) return COLLINE;
        if (h < 2600) return ALPINE_MEADOW;
        if (h < 3200) return ROCK;
        return GLACIER;
    }
}
