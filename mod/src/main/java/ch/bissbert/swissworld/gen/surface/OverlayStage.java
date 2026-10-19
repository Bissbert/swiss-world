package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LanternBlock;
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
 * Roads and rails follow the smoothed profile of {@link TransportPlan}: below a raised deck goes an embankment, above a
 * sunken one a cut, and the terrain beside them is graded to the deck ({@link TransportPlan#earthwork}). A way across
 * a stream gets a culvert; a tunnel is bored and lined. Steps, dams and grass keep the deck of {@link ColumnGrid#deck}.
 */
public final class OverlayStage implements GenStage {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState FILL = Blocks.STONE.defaultBlockState();
    private static final BlockState SOIL = Blocks.DIRT.defaultBlockState();
    private static final BlockState BEAM = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState LINING = Blocks.STONE_BRICKS.defaultBlockState();
    /** A stream bed that cannot fall into the tunnel under it. */
    private static final BlockState BED = Blocks.CLAY.defaultBlockState();
    private static final BlockState COVER = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState BALLAST = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState BALLAST_EDGE = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState DAM_BODY = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
    private static final BlockState SMOOTH = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState LANTERN = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
    private static final int NONE = ColumnGrid.NONE;

    @Override
    public void apply(GenContext ctx) {
        ChunkAccess chunk = ctx.chunk;
        ColumnGrid g = ColumnGrid.of(ctx, TransportPlan.MARGIN);
        TransportPlan t = new TransportPlan(g);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = x0 + lx, z = z0 + lz;
                BlockState top = chunk.getBlockState(pos.set(x, g.present(x, z) ? g.top(x, z) : 0, z));
                boolean done = t.build(x, z, (y, part) -> set(chunk, pos, x, y, z, block(t, x, z, part, y, top)));
                if (!done) column(chunk, t, x, z, pos);
            }
        }
    }

    private static BlockState block(TransportPlan t, int x, int z, TransportPlan.Part part, int y, BlockState top) {
        ColumnGrid g = t.grid();
        int cls = g.overlay(x, z);
        return switch (part) {
            case AIR -> AIR;
            case FILL -> FILL;
            case SOIL -> SOIL;
            case BEAM -> BEAM;
            case LINING -> LINING;
            case COVER -> COVER;
            case BALLAST -> g.flag(x, z, ColumnGrid.EDGE) ? BALLAST_EDGE : BALLAST;
            case SURFACE -> surface(g, x, z, cls, y);
            case FLOOR -> floor(t, x, z, y);
            case SLAB -> slab(cls);
            case RAIL -> rail(t, x, z);
            case RAILING -> railing(t, x, z);
            case LANTERN -> LANTERN;
            case TOP -> top.isAir() ? COVER : top;
            case BED -> top.isAir() || top.getBlock() instanceof FallingBlock ? BED : top;
        };
    }

    /** Steps, dams, grass and other overlay that keeps its {@link ColumnGrid#deck}. */
    private static void column(ChunkAccess chunk, TransportPlan t, int x, int z, BlockPos.MutableBlockPos pos) {
        ColumnGrid g = t.grid();
        int cls = g.overlay(x, z);
        boolean bridge = g.flag(x, z, ColumnGrid.BRIDGE);
        boolean wet = g.source(x, z) != ColumnGrid.NONE;
        boolean dam = cls == OverlayPlan.DAM_CREST || g.flag(x, z, ColumnGrid.DAM_BODY);
        // a path drawn across a lake without its own height would drown or float: the bridge or dam flag is missing
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
        if (deck < top && !wet) {
            // never cut below a neighbour's water: that wall holds it
            int keep = Math.max(deck, WaterPlan.neighbourSource(g, x, z));
            for (int y = keep + 1; y <= top; y++) set(chunk, pos, x, y, z, AIR);
        }
        if (cls == 0) return;
        set(chunk, pos, x, deck, z, surface(g, x, z, cls, deck));
        if (t.railing(x, z)) set(chunk, pos, x, deck + 1, z, railing(t, x, z));
    }

    /** A bore's floor under a way it took over: ballast in a rail tunnel, the road's surface in a road tunnel. */
    private static BlockState floor(TransportPlan t, int x, int z, int y) {
        int cls = t.boreClass(x, z);
        if (cls == OverlayPlan.RAIL_BED || cls == OverlayPlan.RACK_BED) return BALLAST;
        return cls == 7 ? LINING : surface(t.grid(), x, z, cls, y);
    }

    private static BlockState surface(ColumnGrid g, int x, int z, int cls, int deck) {
        return switch (cls) {
            case 1, 2 -> Blocks.STONE.defaultBlockState();
            case 3 -> Blocks.POLISHED_ANDESITE.defaultBlockState();
            case 4 -> Blocks.ANDESITE.defaultBlockState();
            case 5 -> ColumnHash.below(g.east(x), g.north(z), 5, 3) == 0
                    ? Blocks.GRAVEL.defaultBlockState()
                    : Blocks.COARSE_DIRT.defaultBlockState();
            case 6 -> Blocks.DIRT_PATH.defaultBlockState();
            case 7 -> steps(g, x, z, deck);
            case 13 -> Blocks.GRASS_BLOCK.defaultBlockState();
            default -> SMOOTH; // 10 platform, 11 dam crest, 12 runway, 14 parking
        };
    }

    /** The bottom slab of a road's material, for a half step. */
    private static BlockState slab(int cls) {
        BlockState s = switch (cls) {
            case 1, 2 -> Blocks.STONE_SLAB.defaultBlockState();
            case 3 -> Blocks.POLISHED_ANDESITE_SLAB.defaultBlockState();
            case 4 -> Blocks.ANDESITE_SLAB.defaultBlockState();
            default -> Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
        };
        return s.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    }

    private static BlockState rail(TransportPlan t, int x, int z) {
        return Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.valueOf(t.railShape(x, z).name()));
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
    private static BlockState railing(TransportPlan t, int x, int z) {
        boolean n = t.railing(x, z - 1), s = t.railing(x, z + 1);
        boolean e = t.railing(x + 1, z), w = t.railing(x - 1, z);
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
