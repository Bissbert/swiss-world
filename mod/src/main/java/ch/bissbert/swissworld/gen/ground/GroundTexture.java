package ch.bissbert.swissworld.gen.ground;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which vanilla blocks dress the natural ground: first the fringes (rail ballast, path edges, shores), then the
 * material class of the column (soil, dry grass, bare earth, rock, scree) from the orthophoto (GMAT) where the cell has
 * it, else from the {@link Landform} (slope, convexity, cliffs above); rock and scree come from the lithology's family,
 * shaded by a large-scale {@link #tone}, soil from the zone's palette. The tables are in NOTES-mod-texture.md.
 *
 * <p>Everything is keyed by world position, so every chunk gives the same answer.
 */
public final class GroundTexture {
    private GroundTexture() {}

    static final int SALT_PATCH = 51, SALT_MOD = 52, SALT_ROCK = 53, SALT_EDGE = 54, SALT_TONE = 55, SALT_SCREE = 56;
    /** Blocks under the top that get the matching under-layer. */
    public static final int UNDER = 3;

    /** The material class of a column: what the landform (or the orthophoto, GMAT) says lies on top. */
    enum Material { SOIL, DRY_GRASS, BARE_SOIL, ROCK, SCREE }

    /**
     * Bare rock and scree of each lithology, ordered dark to light: {@link #tone} picks along the list, so tone moves in
     * large smooth patches and neighbouring tones are neighbouring shades of one family.
     */
    private static final Map<Lith, Palette> ROCK = new EnumMap<>(Lith.class), SCREE = new EnumMap<>(Lith.class);
    private static final Map<Zone, Palette> ZONE = new EnumMap<>(Zone.class);

    static {
        Block st = Blocks.STONE, an = Blocks.ANDESITE, cob = Blocks.COBBLESTONE, gr = Blocks.GRAVEL, tuff = Blocks.TUFF,
                cd = Blocks.COARSE_DIRT;
        // limestone: light greys, calcite only at the very lightest tone
        ROCK.put(Lith.LIMESTONE, Palette.of(st, 15, an, 30, Blocks.SMOOTH_STONE, 22, Blocks.DIORITE, 28, Blocks.CALCITE, 5));
        SCREE.put(Lith.LIMESTONE, Palette.of(cob, 25, gr, 50, Blocks.DIORITE, 25));
        // crystalline (granite, gneiss): mid to dark greys
        ROCK.put(Lith.CRYSTALLINE, Palette.of(tuff, 15, st, 45, an, 40));
        SCREE.put(Lith.CRYSTALLINE, Palette.of(cob, 35, gr, 40, an, 25));
        // schist and flysch: dark grey-brown
        ROCK.put(Lith.SCHIST, Palette.of(Blocks.COBBLED_DEEPSLATE, 20, Blocks.DEEPSLATE, 30, tuff, 35, st, 15));
        SCREE.put(Lith.SCHIST, Palette.of(Blocks.COBBLED_DEEPSLATE, 30, tuff, 30, gr, 40));
        // molasse: grey-beige sandstone and conglomerate
        ROCK.put(Lith.MOLASSE, Palette.of(st, 30, Blocks.DRIPSTONE_BLOCK, 25, an, 33, Blocks.SANDSTONE, 12));
        SCREE.put(Lith.MOLASSE, Palette.of(cd, 25, cob, 30, gr, 45));
        // quaternary: gravel and coarse earth, no bedrock
        ROCK.put(Lith.QUATERNARY, Palette.of(cd, 30, cob, 35, gr, 35));
        SCREE.put(Lith.QUATERNARY, Palette.of(cd, 35, cob, 15, gr, 50));
        ROCK.put(Lith.UNKNOWN, Palette.of(tuff, 20, st, 50, an, 30));
        SCREE.put(Lith.UNKNOWN, Palette.of(cob, 40, gr, 60));

        Block g = Blocks.GRASS_BLOCK, moss = Blocks.MOSS_BLOCK, pod = Blocks.PODZOL, root = Blocks.ROOTED_DIRT,
                mud = Blocks.MUD;
        // soil only: rock and scree come from the landform, never as speckles in a meadow
        put(Zone.HAY_MEADOW, g, 97, cd, 3);
        put(Zone.PASTURE, g, 95, cd, 5);
        put(Zone.ALPINE_PASTURE, g, 97, cd, 3);
        put(Zone.ALPINE_TURF, moss, 10, g, 87, cd, 3);
        put(Zone.DRY_GRASSLAND, g, 82, cd, 18);
        put(Zone.WET_MEADOW, g, 66, moss, 14, mud, 20);
        put(Zone.RAISED_BOG, g, 22, moss, 48, mud, 30);
        put(Zone.FLOODPLAIN, g, 72, mud, 8, cd, 10, gr, 10);
        put(Zone.ARABLE, g, 100); // rows below
        put(Zone.ORCHARD, g, 94, cd, 6);
        put(Zone.VINEYARD, g, 45, cd, 55);
        put(Zone.GARDEN, g, 100); // paths below
        put(Zone.SPORTS, g, 100);
        put(Zone.INDUSTRIAL, cd, 25, gr, 55, an, 20);
        put(Zone.BEECH, g, 50, root, 20, cd, 15, moss, 15);
        put(Zone.MIXED_BROADLEAF, g, 50, root, 15, pod, 15, moss, 10, cd, 10);
        put(Zone.SPRUCE, g, 20, moss, 20, pod, 45, cd, 15);
        put(Zone.FIR_SPRUCE, g, 25, moss, 25, pod, 40, cd, 10);
        put(Zone.LARCH_PINE, g, 45, moss, 15, pod, 28, cd, 12);
        put(Zone.DRY_PINE, g, 40, pod, 18, cd, 42);
        put(Zone.RIPARIAN, g, 40, root, 10, moss, 20, mud, 20, Blocks.CLAY, 10);
        put(Zone.CHESTNUT, g, 50, root, 25, cd, 25);
        put(Zone.KRUMMHOLZ, g, 50, moss, 25, pod, 25);
        put(Zone.FOREST_EDGE, g, 80, root, 10, cd, 10);
        put(Zone.CLEARING, g, 70, root, 10, cd, 20);
        put(Zone.AVALANCHE_TRACK, g, 82, cd, 18);
        put(Zone.REED_SHORE, g, 40, mud, 40, Blocks.CLAY, 20);
        put(Zone.SNOW_PATCH, g, 90, cd, 10);
        // shores of streams and lakes in loose ground: their own beds
        put(Zone.RIVERBED_GRAVEL, Blocks.SAND, 10, gr, 75, cob, 15);
        put(Zone.ALPINE_LAKE_SHORE, g, 20, gr, 55, cob, 25);
    }

    private static void put(Zone z, Object... pairs) {
        ZONE.put(z, Palette.of(pairs));
    }

    /** The soil palette of a zone (null for rock, loose and ice zones without one). */
    public static Palette palette(Zone z) {
        return ZONE.get(z);
    }

    /** The bare rock family of a lithology, dark to light. */
    public static Palette rock(Lith l) {
        return ROCK.get(l);
    }

    /** The scree family of a lithology, dark to light. */
    public static Palette scree(Lith l) {
        return SCREE.get(l);
    }

    /** Shore fringe by water kind (WKND) and altitude. */
    private static final Palette LAKE_SHORE = Palette.of(Blocks.SAND, 35, Blocks.GRAVEL, 40, Blocks.CLAY, 10, Blocks.MUD, 15);
    private static final Palette HIGH_SHORE = Palette.of(Blocks.GRAVEL, 70, Blocks.COBBLESTONE, 30);
    private static final Palette RIVER_SHORE = Palette.of(Blocks.SAND, 20, Blocks.GRAVEL, 50, Blocks.COARSE_DIRT, 15, Blocks.MUD, 15);
    private static final Palette STREAM_SHORE = Palette.of(Blocks.MUD, 35, Blocks.GRAVEL, 35, Blocks.COARSE_DIRT, 30);
    private static final Palette PATH_EDGE = Palette.of(Blocks.DIRT_PATH, 35, Blocks.COARSE_DIRT, 65);
    private static final Palette BALLAST = Palette.of(Blocks.GRAVEL, 65, Blocks.ANDESITE, 15, Blocks.COARSE_DIRT, 20);
    /** Bare earth (GMAT 4: fields, tracks, slips), dark to light. */
    private static final Palette BARE = Palette.of(Blocks.COARSE_DIRT, 45, Blocks.DIRT, 35, Blocks.ROOTED_DIRT, 20);
    /** Steep spots in tended land are embankments and dry-stone walls, not bedrock. */
    private static final Palette EMBANKMENT = Palette.of(Blocks.COARSE_DIRT, 50, Blocks.COBBLESTONE, 25, Blocks.GRAVEL, 25);

    /**
     * Rock tone 0 (dark) .. 1 (light) of a column: large smooth patches (56 m and 17 m) with a little 5 m grain,
     * stretched to fill the range. Picks along a family, so a face shades from one grey to the next, never speckled.
     */
    static double tone(long e, long n) {
        double raw = 0.6 * Noise.value(e, n, 56, SALT_TONE) + 0.3 * Noise.value(e, n, 17, SALT_TONE + 1)
                + 0.1 * Noise.value(e, n, 5, SALT_TONE + 2);
        return Landform.clamp(0.5 + (raw - 0.5) * 1.8, 0, 0.999);
    }

    /** Boundary noise 0..1 for the rock and scree edges: 12 m lobes with a 5 m fray. */
    static double edge(long e, long n, int salt) {
        double raw = 0.65 * Noise.value(e, n, 12, salt) + 0.35 * Noise.value(e, n, 5, salt + 1);
        return Landform.clamp(0.5 + (raw - 0.5) * 2.2, 0, 1);
    }

    /** Zones whose steep spots are embankments and walls. */
    static boolean tended(Zone z) {
        return z == Zone.GARDEN || z == Zone.ORCHARD || z == Zone.VINEYARD || z == Zone.ARABLE || z == Zone.SPORTS;
    }

    /** The zone's own scree share (loose ground) given whether a real cliff stands above. */
    static double screePrior(Zone z, boolean cliff, double convex) {
        return switch (z) {
            case SCREE_CONE -> cliff ? 0.9 : 0.3;
            case DEBRIS_CHANNEL -> 0.75;
            case BOULDER_FIELD -> 0.8;
            case FOREFIELD -> 0.8;
            case MORAINE -> 0.45 + 0.25 * convex;
            case FIRN -> 0.6;
            case SCHIST_SLOPE -> 0.35;
            default -> 0;
        };
    }

    /**
     * The material class from the landform: rock where {@link Landform} says the slope and convexity have shed the
     * soil, scree in cones below cliffs, gullies and loose zones, soil elsewhere. Boundary noise frays the edges only.
     */
    static Material landform(ZoneMap zm, int x, int z, Zone zone, long e, long n) {
        Zone.Group group = zone.group;
        double slope = zm.smoothSlope(x, z), conv = Landform.convex(zm.convexity(x, z), zm.tpi(x, z));
        Belt belt = ZoneId.belt(zm.id(x, z));
        int cliffDist = zm.cliffDist(x, z);
        boolean forest = group == Zone.Group.FOREST;
        double rock = switch (group) {
            case ROCK -> switch (zone) {
                case KARST -> Math.max(Landform.cliffRock(slope, conv), Landform.clamp(0.75 + 0.5 * conv, 0, 1));
                case SCHIST_SLOPE -> Landform.cliffRock(slope - 6, conv);
                // a mapped cliff is mostly bare, but where the smoothed ground is gentle (a bench, a mislabelled step)
                // it keeps turf; convex knobs stay bare lower down
                default -> Math.max(Landform.cliffRock(slope, conv),
                        0.62 * Landform.smooth((slope - 26 + 10 * Math.max(0, conv)) / 10));
            };
            case LOOSE -> zone == Zone.BOULDER_FIELD ? 0.25 + 0.3 * Math.max(0, conv) : Landform.cliffRock(slope - 12, conv);
            case SNOW -> zone == Zone.FIRN ? Landform.cliffRock(slope, conv) : Landform.softRock(slope, conv, false, belt);
            default -> Landform.softRock(slope, conv, forest, belt);
        };
        if (Landform.over(rock, edge(e, n, SALT_EDGE))) return Material.ROCK;
        double prior = screePrior(zone, cliffDist < ZoneMap.NO_CLIFF, conv);
        double scree = Landform.scree(slope, conv, cliffDist, belt, prior, forest);
        if (Landform.over(scree, edge(e, n, SALT_SCREE))) return Material.SCREE;
        return Material.SOIL;
    }

    /** The material class the orthophoto (GMAT) gives an open, rock or loose column, or null to ask the landform. */
    static Material gmat(int cls) {
        return switch (cls) {
            case 2 -> Material.SOIL;
            case 3 -> Material.DRY_GRASS;
            case 4 -> Material.BARE_SOIL;
            case 5, 6 -> Material.ROCK;
            case 7 -> Material.SCREE;
            default -> null;
        };
    }

    private static Belt belt(int id) {
        return ZoneId.belt((short) id);
    }

    /** Soil of a zone without its own soil palette (rock and loose ground): turf by belt. */
    private static Palette soil(Zone zone, Belt belt) {
        Palette p = ZONE.get(zone);
        if (p != null && zone.group != Zone.Group.LOOSE) return p;
        return belt == Belt.ALPINE || belt == Belt.NIVAL ? ZONE.get(Zone.ALPINE_TURF) : ZONE.get(Zone.ALPINE_PASTURE);
    }

    /**
     * The new top block of a natural column, or null to leave it.
     */
    public static Block top(ZoneMap zm, int x, int z) {
        Block t = rawTop(zm, x, z);
        // a grass block shows its brown dirt side on every step of a slope (the contour stripes): on turf steps a share of
        // the tops is moss, which is green on all sides
        if (t == Blocks.GRASS_BLOCK && zm.drop(x, z) >= 1 && turfFace(zm.zone(x, z), t)
                && Noise.value(zm.east(x), zm.north(z), 4, SALT_PATCH + 11) < 0.7) return Blocks.MOSS_BLOCK;
        return t;
    }

    private static Block rawTop(ZoneMap zm, int x, int z) {
        Zone zone = zm.zone(x, z);
        Zone.Group group = zone.group;
        if (group == Zone.Group.NONE || group == Zone.Group.WATER || group == Zone.Group.URBAN && zone != Zone.INDUSTRIAL
                || group == Zone.Group.ICE) return null;
        long e = zm.east(x), n = zm.north(z);
        double v = Noise.patch(e, n, SALT_PATCH), v2 = Noise.patch(e, n, SALT_MOD);
        if (zone == Zone.INDUSTRIAL) return ZONE.get(zone).pick(v);
        boolean soft = group == Zone.Group.OPEN || group == Zone.Group.FOREST;
        if (soft) {
            int rail = zm.railDist(x, z);
            if (rail <= 2 && v2 < (rail == 1 ? 0.6 : 0.25)) return BALLAST.pick(v);
            int path = zm.pathDist(x, z);
            // the verge of a road over rock and scree is that rock and scree (photo), not earth
            int ph = zm.gmat(x, z) & 15;
            if (path <= 2 && v2 < (path == 1 ? 0.45 : 0.15) && ph != 5 && ph != 6 && ph != 7) return PATH_EDGE.pick(v);
            int wd = zm.waterDist(x, z);
            if (wd <= 2 && zone != Zone.REED_SHORE && v2 < (wd == 1 ? 0.7 : 0.35)) {
                Palette p = switch (zm.waterKind(x, z)) {
                    case 1, 4, 5 -> zm.metres(x, z) >= 1500 ? HIGH_SHORE : LAKE_SHORE;
                    case 2 -> RIVER_SHORE;
                    default -> STREAM_SHORE;
                };
                return p.pick(v);
            }
        }
        if (zone == Zone.RIVERBED_GRAVEL || zone == Zone.ALPINE_LAKE_SHORE) return ZONE.get(zone).pick(v);
        int id = zm.id(x, z);
        Lith lith = ZoneId.lith((short) id);
        int gm = zm.gmat(x, z), cls = gm & 15;
        double tone = tone(e, n);
        Material m = null;
        boolean photo = false;
        if (group == Zone.Group.OPEN || group == Zone.Group.ROCK || group == Zone.Group.LOOSE) {
            m = gmat(cls);
            if (m != null) {
                photo = true;
                double t = (gm >>> 4) / 15.0;
                tone = cls == 5 ? 0.55 + 0.44 * t : cls == 6 ? 0.45 * t : t;
                // a 2 m pixel of green on a near-vertical face is a tuft on a ledge, not a meadow: the face is rock
                if ((m == Material.SOIL || m == Material.DRY_GRASS || m == Material.BARE_SOIL)
                        && Landform.over(Landform.faceRock(zm.smoothSlope(x, z), belt(id)), edge(e, n, SALT_EDGE))) {
                    m = Material.ROCK;
                    tone = tone(e, n);
                }
            }
        }
        if (m == null) m = landform(zm, x, z, zone, e, n);
        // a little grain within the tone: neighbouring shades of the family, in patches of a few metres
        double grain = Landform.clamp(tone + 0.3 * (Noise.patch(e, n, SALT_ROCK) - 0.5), 0, 0.999);
        // tended ground has embankments where only the landform says rock; where the photo shows grey it is grey
        boolean wall = tended(zone) && !photo;
        switch (m) {
            case ROCK:
                return (wall ? EMBANKMENT : ROCK.get(lith)).pick(wall ? v : grain);
            case SCREE:
                return (wall ? EMBANKMENT : SCREE.get(lith)).pick(wall ? v : grain);
            case BARE_SOIL:
                return BARE.pick(grain);
            case DRY_GRASS:
                // yellow-brown turf in the high belts is alpine pasture, not a dry south-slope grassland
                return ZONE.get(belt(id) == Belt.COLLINE || belt(id) == Belt.MONTANE ? Zone.DRY_GRASSLAND : Zone.ALPINE_PASTURE).pick(v);
            default:
                break;
        }
        if (zone == Zone.ARABLE) {
            // crop rows: two rows of turned earth, one of grass, east-west
            return Math.floorMod(n, 3) == 0 ? Blocks.GRASS_BLOCK : Blocks.COARSE_DIRT;
        }
        if (zone == Zone.GARDEN && Math.abs(Noise.value(e, n, 9, SALT_MOD + 1) - 0.5) < 0.025) return Blocks.DIRT_PATH;
        return soil(zone, ZoneId.belt((short) id)).pick(v);
    }

    /** Smoothed slope from which a soil column's side face is rock, not earth (a turf ledge on a cliff). */
    public static final double FACE_ROCK = 45;

    /**
     * Earth for the riser of a soil column below its {@link #UNDER} layer: dirt with patches of coarse and rooted dirt
     * (3D noise, so the patches are blobs on the face, never bands along the contour lines).
     */
    public static BlockState riser(long e, long n, int y) {
        double b = Noise.value3(e, n, y, 5, SALT_MOD + 3);
        return (b < 0.3 ? Blocks.COARSE_DIRT : b > 0.75 ? Blocks.ROOTED_DIRT : Blocks.DIRT).defaultBlockState();
    }

    /** Soil tops whose exposed side faces are turf-coloured moss with a little earth, not a brown wall. */
    static boolean turfFace(Zone zone, Block top) {
        return (top == Blocks.GRASS_BLOCK || top == Blocks.MOSS_BLOCK) && (zone.group == Zone.Group.OPEN || zone.group == Zone.Group.LOOSE || zone.group == Zone.Group.ROCK) && !tended(zone)
                && zone != Zone.WET_MEADOW && zone != Zone.RAISED_BOG && zone != Zone.FLOODPLAIN && zone != Zone.REED_SHORE;
    }

    /**
     * The block of a soil column's side face {@code d} blocks under its top ({@code y} is its height), or null for the
     * default earth. Only for blocks that show ({@code exposed}): on steep ground (smoothed slope over {@link #FACE_ROCK})
     * the face below the first soil block is the lithology's rock, so a turf ledge on a cliff is a ledge, not a brown
     * wall; on turf (grass, moss) the face is mostly moss in 4 m blobs with earth in between (moss does not decay under
     * the top block as a grass block would).
     */
    public static BlockState face(ZoneMap zm, int x, int z, Block top, int d, int y, boolean exposed) {
        if (!exposed || !soil(top) || top == Blocks.MUD || top == Blocks.CLAY) return null;
        long e = zm.east(x), n = zm.north(z);
        if (zm.smoothSlope(x, z) >= FACE_ROCK && d >= 2) {
            Lith lith = ZoneId.lith((short) zm.id(x, z));
            double t = Landform.clamp(tone(e, n) + 0.3 * (Noise.value3(e, n, y, 4, SALT_ROCK) - 0.5), 0, 0.999);
            return ROCK.get(lith).pick(t).defaultBlockState();
        }
        if (turfFace(zm.zone(x, z), top)) {
            double v = Noise.value3(e, n, y, 4, SALT_PATCH + 7);
            return (v < 0.5 ? Blocks.MOSS_BLOCK : v < 0.8 ? Blocks.DIRT : v < 0.92 ? Blocks.COARSE_DIRT : Blocks.ROOTED_DIRT)
                    .defaultBlockState();
        }
        return null;
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
        if (top == Blocks.COBBLED_DEEPSLATE) return Blocks.DEEPSLATE.defaultBlockState();
        return (top == Blocks.COBBLESTONE ? Blocks.STONE : top).defaultBlockState();
    }

    /** Topsoil blocks (grass, earth, moss), which want earth below. */
    public static boolean soil(Block b) {
        return b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL || b == Blocks.MOSS_BLOCK || b == Blocks.DIRT_PATH
                || b == Blocks.DIRT || b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT || b == Blocks.MUD || b == Blocks.CLAY;
    }
}
