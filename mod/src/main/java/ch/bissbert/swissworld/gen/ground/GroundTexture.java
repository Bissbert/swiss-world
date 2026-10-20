package ch.bissbert.swissworld.gen.ground;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which vanilla blocks dress the natural ground of each zone: a {@link Palette} per zone and per lithology, plus the
 * local modifiers (steep ground turns to rock, scree under cliffs, shore fringes by water kind, trodden path edges,
 * ballast spill beside rails, crop rows). The table is in NOTES-mod-texture.md ("Palettes").
 *
 * <p>Blocks are picked by {@link Noise#patch} keyed by world position: patches of a few metres, no checkerboards, and
 * the same answer in every chunk.
 */
public final class GroundTexture {
    private GroundTexture() {}

    static final int SALT_PATCH = 51, SALT_MOD = 52, SALT_ROCK = 53;
    /** Blocks under the top that get the matching under-layer. */
    public static final int UNDER = 3;

    private static final Map<Lith, Palette> ROCK = new EnumMap<>(Lith.class);
    private static final Map<Zone, Palette> ZONE = new EnumMap<>(Zone.class);

    static {
        ROCK.put(Lith.UNKNOWN, Palette.of(Blocks.STONE, 50, Blocks.ANDESITE, 25, Blocks.COBBLESTONE, 13, Blocks.TUFF, 12));
        ROCK.put(Lith.LIMESTONE, Palette.of(Blocks.CALCITE, 40, Blocks.DIORITE, 30, Blocks.SMOOTH_STONE, 15, Blocks.STONE, 15));
        ROCK.put(Lith.CRYSTALLINE, Palette.of(Blocks.STONE, 45, Blocks.ANDESITE, 30, Blocks.GRANITE, 15, Blocks.COBBLESTONE, 10));
        ROCK.put(Lith.SCHIST, Palette.of(Blocks.TUFF, 40, Blocks.COBBLED_DEEPSLATE, 25, Blocks.DEEPSLATE, 15, Blocks.STONE, 20));
        ROCK.put(Lith.MOLASSE, Palette.of(Blocks.SANDSTONE, 20, Blocks.GRANITE, 30, Blocks.STONE, 25, Blocks.COBBLESTONE, 25));
        ROCK.put(Lith.QUATERNARY, Palette.of(Blocks.GRAVEL, 50, Blocks.COARSE_DIRT, 30, Blocks.COBBLESTONE, 20));

        Block g = Blocks.GRASS_BLOCK, cd = Blocks.COARSE_DIRT, gr = Blocks.GRAVEL, moss = Blocks.MOSS_BLOCK,
                pod = Blocks.PODZOL, root = Blocks.ROOTED_DIRT, mud = Blocks.MUD, R = null; // R: the lithology's rock
        put(Zone.HAY_MEADOW, g, 97, cd, 3);
        put(Zone.PASTURE, g, 90, cd, 7, gr, 3);
        put(Zone.ALPINE_PASTURE, g, 78, cd, 8, gr, 6, R, 8);
        put(Zone.ALPINE_TURF, moss, 8, g, 44, cd, 8, gr, 16, R, 24);
        put(Zone.DRY_GRASSLAND, g, 72, cd, 18, R, 10);
        put(Zone.WET_MEADOW, g, 66, moss, 14, mud, 20);
        put(Zone.RAISED_BOG, g, 22, moss, 48, mud, 30);
        put(Zone.FLOODPLAIN, g, 72, mud, 8, cd, 10, gr, 10);
        put(Zone.ARABLE, g, 100); // rows below
        put(Zone.ORCHARD, g, 94, cd, 6);
        put(Zone.VINEYARD, g, 45, cd, 55);
        put(Zone.GARDEN, g, 100); // paths below
        put(Zone.SPORTS, g, 100);
        put(Zone.INDUSTRIAL, cd, 25, gr, 55, Blocks.ANDESITE, 20);
        put(Zone.BEECH, g, 50, root, 20, cd, 15, moss, 15);
        put(Zone.MIXED_BROADLEAF, g, 50, root, 15, pod, 15, moss, 10, cd, 10);
        put(Zone.SPRUCE, g, 20, moss, 20, pod, 45, cd, 15);
        put(Zone.FIR_SPRUCE, g, 25, moss, 25, pod, 40, cd, 10);
        put(Zone.LARCH_PINE, g, 40, moss, 15, pod, 25, cd, 10, R, 10);
        put(Zone.DRY_PINE, g, 35, pod, 15, cd, 35, R, 15);
        put(Zone.RIPARIAN, g, 40, root, 10, moss, 20, mud, 20, Blocks.CLAY, 10);
        put(Zone.CHESTNUT, g, 50, root, 25, cd, 25);
        put(Zone.KRUMMHOLZ, g, 40, moss, 20, pod, 20, R, 20);
        put(Zone.FOREST_EDGE, g, 80, root, 10, cd, 10);
        put(Zone.CLEARING, g, 70, root, 10, cd, 20);
        put(Zone.AVALANCHE_TRACK, g, 55, cd, 15, gr, 15, R, 15);
        put(Zone.LIMESTONE_CLIFF, R, 100);
        put(Zone.CRYSTALLINE_CLIFF, R, 100);
        put(Zone.SCHIST_SLOPE, R, 85, gr, 15);
        put(Zone.KARST, g, 12, R, 88);
        put(Zone.BOULDER_FIELD, moss, 5, gr, 20, Blocks.COBBLESTONE, 15, R, 60);
        put(Zone.SCREE_CONE, gr, 60, Blocks.COBBLESTONE, 15, R, 25);
        put(Zone.MORAINE, g, 10, cd, 25, gr, 45, R, 20);
        put(Zone.FOREFIELD, cd, 15, gr, 50, Blocks.COBBLESTONE, 10, R, 25);
        put(Zone.DEBRIS_CHANNEL, cd, 30, gr, 50, R, 20);
        put(Zone.RIVERBED_GRAVEL, Blocks.SAND, 10, gr, 75, R, 15);
        put(Zone.REED_SHORE, g, 40, mud, 40, Blocks.CLAY, 20);
        put(Zone.ALPINE_LAKE_SHORE, g, 20, gr, 50, R, 30);
        // under snow: the ground the snow lies on
        put(Zone.SNOW_PATCH, g, 78, cd, 8, gr, 6, R, 8);
        put(Zone.FIRN, gr, 40, R, 60);
    }

    private static void put(Zone z, Object... pairs) {
        ZONE.put(z, Palette.of(pairs));
    }

    public static Palette palette(Zone z) {
        return ZONE.get(z);
    }

    public static Palette rock(Lith l) {
        return ROCK.get(l);
    }

    /** Shore fringe by water kind (WKND) and altitude. */
    private static final Palette LAKE_SHORE = Palette.of(Blocks.SAND, 35, Blocks.GRAVEL, 40, Blocks.CLAY, 10, Blocks.MUD, 15);
    private static final Palette HIGH_SHORE = Palette.of(Blocks.GRAVEL, 70, Blocks.COBBLESTONE, 30);
    private static final Palette RIVER_SHORE = Palette.of(Blocks.SAND, 20, Blocks.GRAVEL, 50, Blocks.COARSE_DIRT, 15, Blocks.MUD, 15);
    private static final Palette STREAM_SHORE = Palette.of(Blocks.MUD, 35, Blocks.GRAVEL, 35, Blocks.COARSE_DIRT, 30);
    private static final Palette PATH_EDGE = Palette.of(Blocks.DIRT_PATH, 35, Blocks.COARSE_DIRT, 65);
    private static final Palette BALLAST = Palette.of(Blocks.GRAVEL, 65, Blocks.ANDESITE, 15, Blocks.COARSE_DIRT, 20);
    private static final Palette SCREE = Palette.of(Blocks.GRAVEL, 70, Blocks.COBBLESTONE, 30);

    /**
     * The new top block of a natural column, or null to leave it. {@code current} is the top the earlier stages left.
     */
    public static Block top(ZoneMap zm, int x, int z) {
        Zone zone = zm.zone(x, z);
        Zone.Group group = zone.group;
        if (group == Zone.Group.NONE || group == Zone.Group.WATER || group == Zone.Group.URBAN && zone != Zone.INDUSTRIAL
                || group == Zone.Group.ICE) return null;
        long e = zm.east(x), n = zm.north(z);
        Lith lith = ZoneId.lith(zm.id(x, z));
        double v = Noise.patch(e, n, SALT_PATCH), v2 = Noise.patch(e, n, SALT_MOD);
        boolean soft = group == Zone.Group.OPEN || group == Zone.Group.FOREST;
        double slope = zm.slope(x, z);

        if (soft) {
            int rail = zm.railDist(x, z);
            if (rail <= 2 && v2 < (rail == 1 ? 0.6 : 0.25)) return BALLAST.pick(v);
            int path = zm.pathDist(x, z);
            if (path <= 2 && v2 < (path == 1 ? 0.45 : 0.15)) return PATH_EDGE.pick(v);
            int wd = zm.waterDist(x, z);
            if (wd <= 2 && zone != Zone.REED_SHORE && v2 < (wd == 1 ? 0.7 : 0.35)) {
                Palette p = switch (zm.waterKind(x, z)) {
                    case 1, 4, 5 -> zm.metres(x, z) >= 1500 ? HIGH_SHORE : LAKE_SHORE;
                    case 2 -> RIVER_SHORE;
                    default -> STREAM_SHORE;
                };
                return p.pick(v);
            }
            // steep ground sheds its soil: rock from 33 degrees (38 under trees), all rock by 45 (50)
            double from = group == Zone.Group.FOREST ? 38 : 33;
            double rock = ClimateLines.smooth((slope - from) / 12);
            if (Noise.patch(e, n, SALT_ROCK) < rock) return ROCK.get(lith).pick(v);
            if (zm.cliffAbove(x, z) && slope < 40 && v2 < 0.4) return SCREE.pick(v);
            if (zone == Zone.ARABLE) {
                // crop rows: two rows of turned earth, one of grass, east-west
                return Math.floorMod(n, 3) == 0 ? Blocks.GRASS_BLOCK : Blocks.COARSE_DIRT;
            }
            if (zone == Zone.GARDEN && Math.abs(Noise.value(e, n, 9, SALT_MOD + 1) - 0.5) < 0.025) return Blocks.DIRT_PATH;
        }
        Block b = ZONE.get(zone).pick(v);
        return b == null ? ROCK.get(lith).pick(Noise.patch(e, n, SALT_ROCK)) : b;
    }

    /** The block under a top block, {@code depth} 1..{@link #UNDER} below it. */
    public static BlockState under(Block top, int depth) {
        if (top == Blocks.SAND) return (depth == 1 ? Blocks.SAND : Blocks.SANDSTONE).defaultBlockState();
        if (top == Blocks.GRAVEL) return (depth == 1 ? Blocks.GRAVEL : Blocks.STONE).defaultBlockState();
        if (top == Blocks.MUD) return (depth == 1 ? Blocks.MUD : Blocks.DIRT).defaultBlockState();
        if (top == Blocks.CLAY) return (depth == 1 ? Blocks.CLAY : Blocks.DIRT).defaultBlockState();
        if (top == Blocks.ROOTED_DIRT) return (depth == 1 ? Blocks.ROOTED_DIRT : Blocks.DIRT).defaultBlockState();
        if (top == Blocks.COARSE_DIRT) return (depth == 1 ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState();
        if (soil(top)) return Blocks.DIRT.defaultBlockState();
        // rock: the same rock below, but stone under loose cobble
        return (top == Blocks.COBBLESTONE || top == Blocks.COBBLED_DEEPSLATE ? Blocks.STONE : top).defaultBlockState();
    }

    /** Topsoil blocks (grass, earth, moss), which want earth below. */
    public static boolean soil(Block b) {
        return b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL || b == Blocks.MOSS_BLOCK || b == Blocks.DIRT_PATH
                || b == Blocks.DIRT || b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT || b == Blocks.MUD || b == Blocks.CLAY;
    }
}
