package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Interior: stair core or ladder, partition walls, lights and props (containers are LootStage's). Owner: mod-content. */
public final class FurnishStage implements GenStage {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Block[] FLOWERS = {Blocks.POTTED_RED_TULIP, Blocks.POTTED_CORNFLOWER, Blocks.POTTED_FERN,
            Blocks.POTTED_AZURE_BLUET, Blocks.POTTED_OXEYE_DAISY};

    @Override
    public void apply(GenContext ctx) {
        CellView view = CellView.of(ctx);
        Put put = new Put(ctx.chunk);
        for (BuildingPlan p : Buildings.near(view, put.minX(), put.minZ())) furnish(p, put);
    }

    static void furnish(BuildingPlan p, Put put) {
        Palette pal = Palette.of(p.style(), p.gid());
        stairs(p, pal, put);
        for (BuildingPlan.Partition w : p.walls()) partition(p, pal, w, put);
        for (BuildingPlan.Light l : p.lights()) light(p, l, put);
        for (BuildingPlan.Prop pr : p.props()) if (!pr.kind().container()) prop(p, pal, pr, put);
    }

    static void stairs(BuildingPlan p, Palette pal, Put put) {
        BuildingPlan.Stairs s = p.stairs();
        if (s == null) return;
        int f = p.floors().size();
        if (s.ladder()) {
            BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Put.mc(s.dir()));
            int bx = s.x() - s.dir().dx, bz = s.z() - s.dir().dz;
            // a single floor under a tall roof: the ladder climbs three blocks into the rafters
            int topY = f > 1 ? p.floorY(f - 1) : Math.max(p.floorY(0) + 1, Math.min(p.floorY(0) + 3, p.cols().cover(s.x(), s.z()) - 2));
            for (int y = p.floorY(0) + 1; y <= topY; y++) {
                put.set(s.x(), y, s.z(), ladder);
                if (p.pillar(bx, bz)) put.set(bx, y, bz, pal.partition().defaultBlockState());
            }
            return;
        }
        BlockState step = Put.with(pal.chair().defaultBlockState(), StairBlock.FACING, Put.mc(s.dir()));
        for (int k = 0; k + 1 < f; k++) {
            for (int i = 0; i < p.storey(); i++) {
                int x = s.x() + s.dir().dx * i, z = s.z() + s.dir().dz * i;
                if (p.cut(k + 1, x, z)) put.set(x, p.floorY(k + 1), z, AIR);
                put.set(x, p.floorY(k) + 1 + i, z, step);
            }
        }
    }

    static void partition(BuildingPlan p, Palette pal, BuildingPlan.Partition w, Put put) {
        Columns c = p.cols();
        int k = w.floor(), yf = p.floorY(k);
        BlockState b = pal.partition().defaultBlockState();
        for (int u = w.from(); u <= w.to(); u++) {
            int x = w.alongX() ? u : w.line(), z = w.alongX() ? w.line() : u;
            if (!c.interior(x, z) || p.reserved(x, z)) continue;
            boolean doorway = k == 0 && p.door() != null && x == p.door().x() - p.door().out().dx
                    && z == p.door().z() - p.door().out().dz;
            int ceil = k + 1 < p.floors().size() && p.floorAt(k + 1, x, z) ? p.floorY(k + 1) : c.topFull(x, z);
            ceil = Math.min(ceil, c.cover(x, z)); // never carve the roof fill beside a lower part of the building
            for (int y = yf + 1; y < ceil; y++) {
                int up = doorway ? p.door().up() : 0;
                if (doorway && y <= yf + up) continue; // the room floor stays
                put.set(x, y, z, (u == w.gap() || doorway) && y <= yf + up + 2 ? AIR : b);
            }
        }
    }

    static void light(BuildingPlan p, BuildingPlan.Light l, Put put) {
        switch (l.kind()) {
            case GLOW -> put.set(l.x(), l.y(), l.z(), Blocks.GLOWSTONE.defaultBlockState());
            case STANDING -> put.set(l.x(), l.y(), l.z(), Blocks.LANTERN.defaultBlockState());
            case HANGING -> {
                put.set(l.x(), l.y(), l.z(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
                for (int y = l.y() + 1; y < p.cols().cover(l.x(), l.z()); y++) {
                    put.set(l.x(), y, l.z(), Blocks.CHAIN.defaultBlockState());
                }
            }
        }
    }

    static void prop(BuildingPlan p, Palette pal, BuildingPlan.Prop pr, Put put) {
        int x = pr.x(), y = pr.y(), z = pr.z();
        Direction in = Put.mc(pr.facing());
        switch (pr.kind()) {
            case BED -> {
                int fx = x + pr.facing().dx, fz = z + pr.facing().dz;
                BlockState bed = pal.bed().defaultBlockState().setValue(BedBlock.FACING, in.getOpposite());
                put.set(x, y, z, bed.setValue(BedBlock.PART, BedPart.HEAD));
                put.set(fx, y, fz, bed.setValue(BedBlock.PART, BedPart.FOOT));
            }
            case TABLE -> {
                put.set(x, y, z, pal.table().defaultBlockState());
                put.set(x, y + 1, z, Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
            }
            case BOOKSHELF -> {
                put.set(x, y, z, Blocks.BOOKSHELF.defaultBlockState());
                if (y + 2 < p.cols().topFull(x, z)) put.set(x, y + 1, z, Blocks.BOOKSHELF.defaultBlockState());
            }
            case CHAIR, PEW -> put.set(x, y, z, Put.with(pal.chair().defaultBlockState(), StairBlock.FACING, in.getOpposite()));
            case COUNTER -> put.set(x, y, z, Put.with(pal.counter().defaultBlockState(), SlabBlock.TYPE, SlabType.TOP));
            case FLOWER_POT -> put.set(x, y, z, FLOWERS[Hash.pick(Hash.pos(x, y, z, p.gid()), 1, FLOWERS.length)].defaultBlockState());
            case CANDLE -> put.set(x, y, z, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3)
                    .setValue(CandleBlock.LIT, true));
            case GRINDSTONE -> put.set(x, y, z, Blocks.GRINDSTONE.defaultBlockState()
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, in));
            case ANVIL -> put.set(x, y, z, Blocks.ANVIL.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, in.getClockWise()));
            default -> put.set(x, y, z, Put.with(block(pr.kind()).defaultBlockState(),
                    BlockStateProperties.HORIZONTAL_FACING, in));
        }
    }

    static Block block(PropKind k) {
        return switch (k) {
            case SMOKER -> Blocks.SMOKER;
            case FURNACE -> Blocks.FURNACE;
            case CRAFTING_TABLE -> Blocks.CRAFTING_TABLE;
            case CAULDRON -> Blocks.CAULDRON;
            case CARTOGRAPHY_TABLE -> Blocks.CARTOGRAPHY_TABLE;
            case LECTERN -> Blocks.LECTERN;
            case SMITHING_TABLE -> Blocks.SMITHING_TABLE;
            case HAY -> Blocks.HAY_BLOCK;
            case COMPOSTER -> Blocks.COMPOSTER;
            default -> Blocks.CRAFTING_TABLE;
        };
    }
}
