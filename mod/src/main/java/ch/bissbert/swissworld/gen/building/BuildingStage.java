package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Building shells from BSPN/BTBL with vanilla palettes. Owner: mod-content.
 * Per body column: foundation down to the terrain, walls where a side faces outside, air inside with floor slabs,
 * the top block as roof (stairs facing uphill on slopes, a slab on half steps); eaves become thin slabs.
 * Special types: a round hollow tank, an all-glass greenhouse, a solid wall, a covered bridge open at both ends.
 */
public final class BuildingStage implements GenStage {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    @Override
    public void apply(GenContext ctx) {
        CellView view = CellView.of(ctx);
        Put put = new Put(ctx.chunk);
        for (BuildingPlan p : Buildings.near(view, put.minX(), put.minZ())) shell(p, put, view);
    }

    static void shell(BuildingPlan p, Put put, CellView view) {
        Columns c = p.cols();
        Palette pal = Palette.of(p.style(), p.gid());
        if (p.style() == Style.TANK) {
            tank(p, pal, put, view);
            return;
        }
        boolean glass = p.style() == Style.GREENHOUSE;
        int xa = Math.max(c.minX, put.minX()), xb = Math.min(c.maxX, put.minX() + 15);
        int za = Math.max(c.minZ, put.minZ()), zb = Math.min(c.maxZ, put.minZ() + 15);
        for (int z = za; z <= zb; z++) {
            for (int x = xa; x <= xb; x++) {
                byte st = c.state(x, z);
                if (st == Columns.EAVE && !glass) eave(c.eave2(x, z), x, z, pal, put);
                else if (st == Columns.BODY) column(p, c, pal, x, z, put, view);
            }
        }
        if (p.door() != null) steps(p, pal, put, view);
    }

    static void eave(int e2, int x, int z, Palette pal, Put put) {
        SlabType t = (e2 & 1) != 0 ? SlabType.BOTTOM : SlabType.TOP;
        put.set(x, Math.floorDiv(e2, 2) - ((e2 & 1) != 0 ? 0 : 1), z,
                Put.with(pal.roofSlab().defaultBlockState(), SlabBlock.TYPE, t));
    }

    static void column(BuildingPlan p, Columns c, Palette pal, int x, int z, Put put, CellView view) {
        int lo = c.lo(x, z), top = c.topFull(x, z);
        foundation(p, pal, x, z, lo, put, view);
        for (int y = lo; y <= top; y++) put.set(x, y, z, y == top ? roof(p, pal, x, z) : block(p, c, pal, x, y, z));
        if ((c.top2(x, z) & 1) != 0 && p.style() != Style.GREENHOUSE) {
            put.set(x, top + 1, z, Put.with(pal.roofSlab().defaultBlockState(), SlabBlock.TYPE, SlabType.BOTTOM));
        }
    }

    /** Down to the terrain under columns that start near floor 0; a bridge only where it meets the ground. */
    static void foundation(BuildingPlan p, Palette pal, int x, int z, int lo, Put put, CellView view) {
        double h = view.terrain(x, z);
        if (lo > p.floorY(0) + 1 || Double.isNaN(h)) return;
        int ground = (int) Math.floor(SwissCoords.toY(h));
        if (p.style() == Style.BRIDGE && lo - ground > 3) return;
        BlockState f = pal.foundation().defaultBlockState();
        for (int y = ground + 1; y < lo; y++) put.set(x, y, z, f);
    }

    /** Lagertank: the body clipped to the ellipse inscribed in its bbox, a windowless ring wall, flat top, hollow. */
    static void tank(BuildingPlan p, Palette pal, Put put, CellView view) {
        Columns c = p.cols();
        int xa = Math.max(c.minX, put.minX()), xb = Math.min(c.maxX, put.minX() + 15);
        int za = Math.max(c.minZ, put.minZ()), zb = Math.min(c.maxZ, put.minZ() + 15);
        for (int z = za; z <= zb; z++) {
            for (int x = xa; x <= xb; x++) {
                if (!round(c, x, z)) continue;
                boolean ring = false;
                for (Dir d : Dir.values()) ring |= !round(c, x + d.dx, z + d.dz);
                int lo = c.lo(x, z), top = c.topFull(x, z);
                foundation(p, pal, x, z, lo, put, view);
                for (int y = lo; y <= top; y++) {
                    put.set(x, y, z, y == top ? pal.roof().defaultBlockState() : ring ? pal.wall().defaultBlockState()
                            : y == lo ? pal.floor().defaultBlockState() : AIR);
                }
            }
        }
    }

    static boolean round(Columns c, int x, int z) {
        if (!c.body(x, z)) return false;
        double rx = (c.maxX - c.minX + 1) / 2.0, rz = (c.maxZ - c.minZ + 1) / 2.0;
        double u = (x + 0.5 - c.minX - rx) / rx, v = (z + 0.5 - c.minZ - rz) / rz;
        return u * u + v * v <= 1;
    }

    /** Below the top: wall (facade side), roof fill (side over a lower part of the same building), floor or air. */
    static BlockState block(BuildingPlan p, Columns c, Palette pal, int x, int y, int z) {
        if (p.style() == Style.WALL) return pal.wall().defaultBlockState();
        Dir face = null;
        boolean roofSide = false;
        int outs = 0;
        for (Dir d : Dir.values()) {
            int nx = x + d.dx, nz = z + d.dz;
            byte s = c.state(nx, nz);
            if (s == Columns.UNKNOWN) continue;
            if (s != Columns.BODY) {
                outs++;
                if (face == null) face = d;
            } else if (y > c.topFull(nx, nz)) roofSide = true;
            else if (y < c.lo(nx, nz) && face == null) face = d;
        }
        if (face != null && p.style() == Style.BRIDGE && face.alongX() == (c.maxX - c.minX >= c.maxZ - c.minZ)
                && outs == 1 && y > c.lo(x, z) && y <= c.lo(x, z) + 3) {
            return AIR; // open portal at the ends
        }
        if (face != null) return wall(p, pal, x, y, z, face, outs);
        if (roofSide) return pal.roof().defaultBlockState();
        int k = p.floors().indexOf(y);
        if (y == c.lo(x, z) || (k >= 0 && p.floorAt(k, x, z))) return pal.floor().defaultBlockState();
        return AIR;
    }

    static BlockState wall(BuildingPlan p, Palette pal, int x, int y, int z, Dir face, int outs) {
        int y0 = p.floorY(0);
        BuildingPlan.Door door = p.door();
        if (door != null && door.x() == x && door.z() == z && (y == y0 + 1 || y == y0 + 2)) {
            BlockState d = pal.door().defaultBlockState().setValue(DoorBlock.FACING, Put.mc(door.out().opposite()));
            return d.setValue(DoorBlock.HALF, y == y0 + 1 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
        }
        boolean chaletBase = p.style() == Style.CHALET && p.floors().size() > 1 && y < p.floorY(1);
        if (y <= y0 || chaletBase && y == y0 + 1) return pal.base().defaultBlockState();
        if (p.style() == Style.BRIDGE && y == p.cols().lo(x, z)) return pal.floor().defaultBlockState();
        if (outs == 1 && window(p, x, y, z, face)) {
            BlockState w = pal.pane().defaultBlockState();
            boolean ew = !face.alongX();
            return w.setValue(CrossCollisionBlock.EAST, ew).setValue(CrossCollisionBlock.WEST, ew)
                    .setValue(CrossCollisionBlock.NORTH, !ew).setValue(CrossCollisionBlock.SOUTH, !ew);
        }
        return chaletBase ? pal.base().defaultBlockState() : pal.wall().defaultBlockState();
    }

    /** Regular window grid per storey: rows 2..storey-1 above each floor, columns by a per-style pattern. */
    static boolean window(BuildingPlan p, int x, int y, int z, Dir face) {
        BuildingPlan.Door door = p.door();
        if (door != null && Math.abs(door.x() - x) + Math.abs(door.z() - z) <= 1 && y <= p.floorY(0) + 3) return false;
        // storeys repeat above the last planned floor, so a building with dropped floors keeps its window rows
        int rel = p.style().hall() ? y - p.floorY(0) : Math.floorMod(y - p.floorY(0), p.storey());
        int u = (face.alongX() ? z : x) + Hash.pick(p.gid(), 31, 12);
        return switch (p.style()) {
            case BARN, TANK, GREENHOUSE, WALL -> false;
            case BRIDGE -> rel == 2 && Math.floorMod(u, 3) != 0;
            case CHURCH -> rel >= 2 && rel <= 5 && Math.floorMod(u, 4) == 1;
            case INDUSTRIAL -> rel == 3 || rel == 4;
            case CHALET -> rel >= 2 && rel < Math.max(3, p.storey()) && Math.floorMod(u, 4) != 0 && Math.floorMod(u, 4) != 3;
            case MODERN -> rel >= 2 && rel < Math.max(3, p.storey()) && Math.floorMod(u, 3) != 2;
            default -> rel >= 2 && rel < Math.max(3, p.storey()) && Math.floorMod(u, 3) == 1;
        };
    }

    /** The top block: stairs facing uphill where the roof slopes down to a lower neighbour, else a full block. */
    static BlockState roof(BuildingPlan p, Palette pal, int x, int z) {
        Dir up = p.style() == Style.GREENHOUSE ? null : p.cols().uphill(x, z);
        if (up == null) return pal.roof().defaultBlockState();
        return Put.with(pal.roofStairs().defaultBlockState(), StairBlock.FACING, Put.mc(up));
    }

    /** Landing in front of the door, stairs down to the terrain, and headroom cut into higher ground. */
    static void steps(BuildingPlan p, Palette pal, Put put, CellView view) {
        BuildingPlan.Door d = p.door();
        int y0 = p.floorY(0);
        BlockState stair = Put.with(pal.steps().defaultBlockState(), StairBlock.FACING, Put.mc(d.out().opposite()));
        for (int i = 0; i < 8; i++) {
            int x = d.x() + d.out().dx * (i + 1), z = d.z() + d.out().dz * (i + 1);
            if (p.cols().state(x, z) == Columns.BODY) return;
            double h = view.terrain(x, z);
            if (Double.isNaN(h)) return;
            int ground = (int) Math.floor(SwissCoords.toY(h)), y = y0 - i;
            for (int a = y + 1; a <= Math.min(ground, y + 3); a++) put.set(x, a, z, AIR);
            if (ground >= y) return;
            put.set(x, y, z, i == 0 ? pal.base().defaultBlockState() : stair);
            for (int a = ground + 1; a < y; a++) put.set(x, a, z, pal.foundation().defaultBlockState());
        }
    }
}
