package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import ch.bissbert.swissworld.gen.surface.ColumnHash;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Snow and glacier ice from the zones: the last stage, after Vegetation. Owner: mod-texture.
 *
 * <p>Natural columns get {@link ZoneMap#snow} eighths of snow on their top: whole snow blocks, then a snow layer;
 * plants under it are replaced, a tree or post keeps its column clear, and grass or podzol below turn snowy.
 * Glacier columns (SURF 6) get their body here: a firn cap of snow blocks where snow lies (snow layers cannot rest on
 * ice), bare packed ice in the ablation zone below the snow line, rubble on debris-covered ice and open crevasses on
 * steep ice; blue ice deeper.
 */
public final class SnowStage implements GenStage {
    private static final int SALT_ICE = 23, SALT_CREVASSE = 61, SALT_DEBRIS = 62;

    @Override
    public void apply(GenContext ctx) {
        apply(GroundTextureStage.zones(ctx), BlockAccess.of(ctx.chunk));
    }

    public static void apply(ZoneMap zm, BlockAccess b) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = zm.x0 + lx, z = zm.z0 + lz;
                Zone zone = zm.zone(x, z);
                int ground = ColumnGrid.y(zm.metres(x, z));
                if (zone.glacier()) {
                    if (zm.natural(x, z)) glacier(zm, b, x, z, zone);
                    continue;
                }
                int snow = zm.snow(x, z);
                if (snow == 0 || !zm.natural(x, z)) continue;
                cover(b, x, z, ground, snow);
            }
        }
    }

    /** Puts {@code snow} eighths on the column's top; false if the column has no place for it. */
    static boolean cover(BlockAccess b, int x, int z, int ground, int snow) {
        int y = ground + GroundTextureStage.SCAN;
        BlockState s = null;
        for (; y >= ground - GroundTextureStage.SCAN; y--) {
            s = b.get(x, y, z);
            if (s.isAir() || plant(s)) continue;
            break;
        }
        if (s == null || y < ground - GroundTextureStage.SCAN) return false;
        if (!s.getFluidState().isEmpty() || !holdsSnow(s)) return false;
        int full = snow / 8, layers = snow % 8;
        for (int i = 1; i <= full; i++) b.set(x, y + i, z, Blocks.SNOW_BLOCK.defaultBlockState());
        if (layers > 0) b.set(x, y + full + 1, z, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
        // a plant that stood above the snow is buried: clear what is left of it
        for (int i = full + (layers > 0 ? 2 : 1); i <= full + 2; i++) {
            if (plant(b.get(x, y + i, z))) b.set(x, y + i, z, Blocks.AIR.defaultBlockState());
        }
        if (s.hasProperty(SnowyDirtBlock.SNOWY)) b.set(x, y, z, s.setValue(SnowyDirtBlock.SNOWY, true));
        return true;
    }

    /** Small plants VegetationStage sets on the ground; snow replaces them. */
    static boolean plant(BlockState s) {
        return s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.MOSS_CARPET) || s.is(Blocks.SWEET_BERRY_BUSH)
                || s.is(BlockTags.SMALL_FLOWERS) || s.is(Blocks.SNOW);
    }

    /** A snow layer survives on top (vanilla: a full top face, and not ice). */
    static boolean holdsSnow(BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE || b == Blocks.MUD || b == Blocks.DIRT_PATH
                || b == Blocks.FARMLAND) return false;
        if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FENCES)) return false;
        return GroundTexture.soil(b) || b == Blocks.SNOW_BLOCK || b == Blocks.GRAVEL || b == Blocks.SAND || rock(b);
    }

    private static final java.util.Set<Block> ROCKS = new java.util.HashSet<>();

    static {
        for (Lith l : Lith.values()) {
            Palette p = GroundTexture.rock(l);
            for (int i = 0; i < p.size(); i++) ROCKS.add(p.block(i));
        }
        ROCKS.add(Blocks.STONE);
        ROCKS.add(Blocks.COBBLESTONE);
    }

    private static boolean rock(Block b) {
        return ROCKS.contains(b);
    }

    /**
     * Transverse crevasses: one-metre slots across the flow (the smoothed downhill direction), every
     * {@link #CREVASSE_SPACING} m along it, broken into lengths by noise. Keyed by world position.
     */
    static boolean crevasse(long e, long n, int downhill) {
        // unit flow vector of the eighth-turn direction; LV95 north is -z, so flow (fx, fz) runs along (e, -n)
        int fx = new int[] {1, 1, 0, -1, -1, -1, 0, 1}[downhill], fz = new int[] {0, 1, 1, 1, 0, -1, -1, -1}[downhill];
        long t = fx * e - fz * n;
        int spacing = fx != 0 && fz != 0 ? CREVASSE_SPACING * 3 / 2 : CREVASSE_SPACING; // diagonal steps are longer
        return Math.floorMod(t, spacing) == 0 && Noise.value(e, n, 13, SALT_CREVASSE) > 0.42;
    }

    static final int CREVASSE_SPACING = 6;

    /** The glacier body: {@code 3..8} blocks of the column from the top down. */
    static void glacier(ZoneMap zm, BlockAccess b, int x, int z, Zone zone) {
        long e = zm.east(x), n = zm.north(z);
        int ground = ColumnGrid.y(zm.metres(x, z));
        int y = GroundTextureStage.top(b, x, z, ground);
        if (y == ColumnGrid.NONE) return;
        int depth = 3 + ColumnHash.below(e, n, SALT_ICE, 6);
        int snow = zm.snow(x, z);
        // firn: one snow block per 8 eighths, at least one where any snow lies (layers cannot rest on ice)
        int firn = snow == 0 ? 0 : Math.min(depth - 1, Math.max(1, snow / 8));
        boolean crevasse = zone == Zone.CREVASSED && crevasse(e, n, zm.downhill(x, z));
        for (int i = 0; i < depth; i++) {
            BlockState s;
            if (crevasse && i < depth - 1) s = Blocks.AIR.defaultBlockState();
            else if (i < firn) s = Blocks.SNOW_BLOCK.defaultBlockState();
            else if (i == 0 && zone == Zone.DEBRIS_GLACIER) {
                s = (Noise.patch(e, n, SALT_DEBRIS) < 0.65 ? Blocks.GRAVEL : Blocks.COBBLESTONE).defaultBlockState();
            } else if (i <= firn + 1) s = Blocks.PACKED_ICE.defaultBlockState();
            else s = Blocks.BLUE_ICE.defaultBlockState();
            b.set(x, y - i, z, s);
        }
    }
}
