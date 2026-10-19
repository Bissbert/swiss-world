package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Roads, paths, rail beds + vanilla rails, bridges, tunnels, dams from OCLS/OELV/OFLG (swd-format §5). Owner: mod-surface.
 * The deck is the overlay's surface block: OELV, else the terrain top (after banks). Below a raised deck that is not a
 * bridge goes an embankment; above a sunken deck that is not a tunnel, a cutting. Rails, kerbs, railings sit one above.
 */
public final class OverlayStage implements GenStage {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState FILL = Blocks.STONE.defaultBlockState();
    private static final BlockState BEAM = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState BALLAST = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState BALLAST_EDGE = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState DAM_BODY = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState SMOOTH = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState KERB = Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    private static final BlockState TUNNEL_LIGHT = Blocks.GLOWSTONE.defaultBlockState();
    /** Clear height of a tunnel above its floor. */
    private static final int TUNNEL_HEIGHT = 6;
    private static final int LIGHT_SPACING = 37;

    @Override
    public void apply(GenContext ctx) {
        ChunkAccess chunk = ctx.chunk;
        ColumnGrid g = ColumnGrid.of(ctx);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx, z = z0 + lz;
                if (g.present(x, z) && (g.overlay(x, z) != 0 || g.flag(x, z, ColumnGrid.DAM_BODY))) column(chunk, g, x, z, pos);
            }
        }
    }

    private static void column(ChunkAccess chunk, ColumnGrid g, int x, int z, BlockPos.MutableBlockPos pos) {
        int cls = g.overlay(x, z);
        boolean bridge = g.flag(x, z, ColumnGrid.BRIDGE), tunnel = g.flag(x, z, ColumnGrid.TUNNEL);
        boolean wet = g.source(x, z) != ColumnGrid.NONE;
        boolean dam = cls == OverlayPlan.DAM_CREST || g.flag(x, z, ColumnGrid.DAM_BODY);
        // a road drawn across a lake without its own height would drown or float: the bridge or dam flag is missing
        if (wet && !bridge && !dam && !g.elevated(x, z)) return;
        int top = g.top(x, z), deck = g.deck(x, z);

        if (g.flag(x, z, ColumnGrid.DAM_BODY)) {
            for (int y = g.ground(x, z) + 1; y < deck; y++) set(chunk, pos, x, y, z, DAM_BODY);
        } else if (bridge) {
            set(chunk, pos, x, deck - 1, z, BEAM);
            if (OverlayPlan.pier(g, x, z)) {
                for (int y = g.ground(x, z) + 1; y <= deck - 2; y++) set(chunk, pos, x, y, z, BEAM);
            }
        } else if (deck > top) {
            for (int y = top + 1; y < deck; y++) set(chunk, pos, x, y, z, FILL);
        }

        if (tunnel) {
            int roof = deck + TUNNEL_HEIGHT;
            // under a lake the bed stays closed
            int clear = wet ? Math.min(roof, g.ground(x, z) - 1) : roof;
            for (int y = deck + 1; y <= clear; y++) set(chunk, pos, x, y, z, AIR);
            if (!wet && top > roof + 1 && Math.floorMod(3 * x + 5 * z, LIGHT_SPACING) == 0) {
                set(chunk, pos, x, roof + 1, z, TUNNEL_LIGHT);
            }
        } else if (deck < top && !wet) {
            // never cut below a neighbour's water: that wall holds it
            int keep = Math.max(deck, WaterPlan.neighbourSource(g, x, z));
            for (int y = keep + 1; y <= top; y++) set(chunk, pos, x, y, z, AIR);
        }

        if (cls == 0) return;
        set(chunk, pos, x, deck, z, surface(g, x, z, cls, deck));
        if (OverlayPlan.railing(g, x, z)) {
            set(chunk, pos, x, deck + 1, z, railing(g, x, z));
        } else if (OverlayPlan.railCentre(g, x, z)) {
            set(chunk, pos, x, deck + 1, z, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.valueOf(OverlayPlan.railShape(g, x, z).name())));
        } else if (cls == 10) {
            set(chunk, pos, x, deck + 1, z, KERB);
        } else if (cls <= 4 && g.flag(x, z, ColumnGrid.EDGE)) {
            set(chunk, pos, x, deck + 1, z, KERB);
        }
    }

    private static BlockState surface(ColumnGrid g, int x, int z, int cls, int deck) {
        return switch (cls) {
            case 1, 2 -> Blocks.GRAY_CONCRETE.defaultBlockState();
            case 3 -> Blocks.POLISHED_ANDESITE.defaultBlockState();
            case 4 -> Blocks.ANDESITE.defaultBlockState();
            case 5 -> ColumnHash.below(g.east(x), g.north(z), 5, 3) == 0
                    ? Blocks.GRAVEL.defaultBlockState()
                    : Blocks.COARSE_DIRT.defaultBlockState();
            case 6 -> Blocks.DIRT_PATH.defaultBlockState();
            case 7 -> steps(g, x, z, deck);
            case OverlayPlan.RAIL_BED, OverlayPlan.RACK_BED -> g.flag(x, z, ColumnGrid.EDGE) ? BALLAST_EDGE : BALLAST;
            case 13 -> Blocks.GRASS_BLOCK.defaultBlockState();
            default -> SMOOTH; // 10 platform, 11 dam crest, 12 runway, 14 parking
        };
    }

    /** Stairs climbing towards the highest step neighbour; a landing (no higher neighbour) is a full block. */
    private static BlockState steps(ColumnGrid g, int x, int z, int deck) {
        Direction best = null;
        int bestDeck = deck;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int nx = x + d.getStepX(), nz = z + d.getStepZ();
            if (g.overlay(nx, nz) != 7) continue;
            int nd = g.deck(nx, nz);
            if (nd > bestDeck) {
                bestDeck = nd;
                best = d;
            }
        }
        return best == null
                ? Blocks.STONE_BRICKS.defaultBlockState()
                : Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, best);
    }

    /** A wall joined to the railing columns beside it; a post where it is not a straight run. */
    private static BlockState railing(ColumnGrid g, int x, int z) {
        boolean n = OverlayPlan.railing(g, x, z - 1), s = OverlayPlan.railing(g, x, z + 1);
        boolean e = OverlayPlan.railing(g, x + 1, z), w = OverlayPlan.railing(g, x - 1, z);
        boolean straight = n && s && !e && !w || e && w && !n && !s;
        return Blocks.STONE_BRICK_WALL.defaultBlockState()
                .setValue(WallBlock.UP, !straight)
                .setValue(WallBlock.NORTH_WALL, n ? WallSide.LOW : WallSide.NONE)
                .setValue(WallBlock.SOUTH_WALL, s ? WallSide.LOW : WallSide.NONE)
                .setValue(WallBlock.EAST_WALL, e ? WallSide.LOW : WallSide.NONE)
                .setValue(WallBlock.WEST_WALL, w ? WallSide.LOW : WallSide.NONE);
    }

    private static void set(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState s) {
        chunk.setBlockState(pos.set(x, y, z), s, false);
    }
}
