package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import java.util.Arrays;
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
 * Special types: a round hollow tank, an all-glass greenhouse, a solid wall, a covered bridge open at both ends,
 * a canopy (open building, free-standing roof): only the roof, on posts at its corners.
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
        boolean glass = p.style() == Style.GREENHOUSE, canopy = p.style() == Style.CANOPY;
        int xa = Math.max(c.minX, put.minX()), xb = Math.min(c.maxX, put.minX() + 15);
        int za = Math.max(c.minZ, put.minZ()), zb = Math.min(c.maxZ, put.minZ() + 15);
        for (int z = za; z <= zb; z++) {
            for (int x = xa; x <= xb; x++) {
                byte st = c.state(x, z);
                if (st == Columns.EAVE && !glass) eave(c.eave2(x, z), x, z, pal, put);
                else if (st == Columns.BODY && !canopy) column(p, c, pal, x, z, put, view);
                else if (st == Columns.BODY) put.set(x, c.topFull(x, z), z, roof(p, pal, x, z));
                if (canopy && st != Columns.OUT && c.corner(x, z)) post(c, pal, x, z, put, view);
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
        if ((c.top2(x, z) & 1) != 0 && p.style() != Style.GREENHOUSE && c.uphill(x, z) == null) {
            put.set(x, top + 1, z, Put.with(pal.roofSlab().defaultBlockState(), SlabBlock.TYPE, SlabType.BOTTOM));
        }
    }

    /** A post from the terrain up to the underside of the roof (at most 16 blocks). */
    static void post(Columns c, Palette pal, int x, int z, Put put, CellView view) {
        double h = view.terrain(x, z);
        if (Double.isNaN(h)) return;
        int e2 = c.state(x, z) == Columns.BODY ? 2 * c.topFull(x, z) + 1 : c.eave2(x, z);
        int under = Math.floorDiv(e2, 2) - ((e2 & 1) != 0 ? 0 : 1), ground = (int) Math.floor(SwissCoords.toY(h));
        if (under - ground > 17) return;
        for (int y = ground + 1; y < under; y++) put.set(x, y, z, pal.base().defaultBlockState());
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
        if (door != null && door.x() == x && door.z() == z && (y == y0 + door.up() + 1 || y == y0 + door.up() + 2)) {
            BlockState d = pal.door().defaultBlockState().setValue(DoorBlock.FACING, Put.mc(door.out().opposite()));
            return d.setValue(DoorBlock.HALF, y == y0 + door.up() + 1 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
        }
        boolean chaletBase = p.style() == Style.CHALET && p.floors().size() > 1 && y < p.floorY(1);
        if (y <= y0 || chaletBase && y == y0 + 1) return pal.base().defaultBlockState();
        if (p.style() == Style.BRIDGE && y == p.cols().lo(x, z)) return pal.floor().defaultBlockState();
        if (outs == 1 && straight(p.cols(), x, y, z, face) && window(p, x, y, z, face)) {
            BlockState w = pal.pane().defaultBlockState();
            boolean ew = !face.alongX();
            return w.setValue(CrossCollisionBlock.EAST, ew).setValue(CrossCollisionBlock.WEST, ew)
                    .setValue(CrossCollisionBlock.NORTH, !ew).setValue(CrossCollisionBlock.SOUTH, !ew);
        }
        return chaletBase ? pal.base().defaultBlockState() : pal.wall().defaultBlockState();
    }

    /**
     * True on a straight wall run: both lateral neighbours along the wall are body columns with a wall at this height
     * facing the same side. A pane elsewhere (a jog of a stepped facade, a corner, a gap) would leave its sides open.
     */
    static boolean straight(Columns c, int x, int y, int z, Dir face) {
        Dir a = face.cw(), b = a.opposite();
        for (Dir d : new Dir[]{a, b}) {
            int nx = x + d.dx, nz = z + d.dz;
            if (!c.body(nx, nz) || y < c.lo(nx, nz) || y > c.topFull(nx, nz)) return false;
            byte s = c.state(nx + face.dx, nz + face.dz);
            if (s == Columns.BODY || s == Columns.UNKNOWN) return false;
        }
        return true;
    }

    /** Regular window grid per storey: rows 2..storey-1 above each floor, columns by a per-style pattern. */
    static boolean window(BuildingPlan p, int x, int y, int z, Dir face) {
        BuildingPlan.Door door = p.door();
        if (door != null && Math.abs(door.x() - x) + Math.abs(door.z() - z) <= 1 && y <= p.floorY(0) + door.up() + 3) return false;
        // storeys repeat above the last planned floor, so a building with dropped floors keeps its window rows
        int rel = p.style().hall() ? y - p.floorY(0) : Math.floorMod(y - p.floorY(0), p.storey());
        int u = (face.alongX() ? z : x) + Hash.pick(p.gid(), 31, 12);
        return switch (p.style()) {
            case BARN, TANK, GREENHOUSE, WALL, CANOPY -> false;
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

    private static final int MAX_RUN = 24;

    /**
     * The walking levels (block y of the surface) of the path leading out of the door, cell by cell, ending on the
     * natural terrain; each cell differs from the last by at most one block. Null if it runs into the own building,
     * leaves the loaded cells at once, or does not reach the terrain within {@link #MAX_RUN} cells.
     */
    static int[] trace(Columns c, BuildingPlan.Door d, int y0, CellView view) {
        int[] ys = new int[MAX_RUN];
        int prev = y0;
        for (int i = 0; i < MAX_RUN; i++) {
            int x = d.x() + d.out().dx * (i + 1), z = d.z() + d.out().dz * (i + 1);
            if (c.state(x, z) == Columns.BODY) return null;
            double h = view.terrain(x, z);
            if (Double.isNaN(h)) return i == 0 ? null : Arrays.copyOf(ys, i);
            int g = (int) Math.floor(SwissCoords.toY(h));
            int y = i == 0 ? y0 : Math.max(prev - 1, Math.min(prev + 1, g));
            ys[i] = y;
            if (y == g && calm(c, d, i + 1, g, view)) return Arrays.copyOf(ys, i + 1);
            prev = y;
        }
        return null;
    }

    /** The terrain after cell {@code i} of the path stays walkable (steps of at most one block) for a few cells. */
    private static boolean calm(Columns c, BuildingPlan.Door d, int i, int g, CellView view) {
        for (int k = 0; k < 6; k++, i++) {
            int x = d.x() + d.out().dx * (i + 1), z = d.z() + d.out().dz * (i + 1);
            double h = view.terrain(x, z);
            if (Double.isNaN(h)) return true;
            int n = (int) Math.floor(SwissCoords.toY(h));
            if (Math.abs(n - g) > 1) return false;
            g = n;
        }
        return true;
    }

    /** Landing in front of the door, then steps and a trench (higher ground) or fill (lower) down to the terrain. */
    static void steps(BuildingPlan p, Palette pal, Put put, CellView view) {
        BuildingPlan.Door d = p.door();
        int y0 = p.floorY(0) + d.up();
        int[] ys = trace(p.cols(), d, y0, view);
        if (ys == null) ys = fallbackPath(p, d, y0, view);
        for (int i = 0; i < ys.length; i++) {
            int x = d.x() + d.out().dx * (i + 1), z = d.z() + d.out().dz * (i + 1);
            double h = view.terrain(x, z);
            if (Double.isNaN(h)) break;
            int ground = (int) Math.floor(SwissCoords.toY(h)), y = ys[i];
            int prev = i == 0 ? y0 : ys[i - 1];
            for (int a = y + 1; a <= Math.max(ground > y ? ground : y, i == 0 || p.cols().state(x, z) == Columns.EAVE ? y + 3 : y); a++) put.set(x, a, z, AIR);
            if (y == ground && i > 0) continue;
            BlockState b;
            if (i == 0 || y == prev) b = pal.base().defaultBlockState();
            else b = Put.with(pal.steps().defaultBlockState(), StairBlock.FACING,
                    Put.mc(y < prev ? d.out().opposite() : d.out()));
            if (y != ground || i == 0) put.set(x, y, z, b);
            for (int a = ground + 1; a < y; a++) put.set(x, a, z, pal.foundation().defaultBlockState());
        }
        // low eave slabs over the natural ground where the path ends would block the way out
        for (int i = ys.length; i < ys.length + 5; i++) {
            int x = d.x() + d.out().dx * (i + 1), z = d.z() + d.out().dz * (i + 1);
            double h = view.terrain(x, z);
            if (Double.isNaN(h) || p.cols().state(x, z) != Columns.EAVE) continue;
            int g = (int) Math.floor(SwissCoords.toY(h));
            for (int a = g + 1; a <= g + 3; a++) put.set(x, a, z, AIR);
        }
    }

    /** No path reaches the terrain: still cut the first cells so the door opens onto a landing. */
    private static int[] fallbackPath(BuildingPlan p, BuildingPlan.Door d, int y0, CellView view) {
        int n = 0;
        while (n < 8) {
            int x = d.x() + d.out().dx * (n + 1), z = d.z() + d.out().dz * (n + 1);
            if (p.cols().state(x, z) == Columns.BODY || Double.isNaN(view.terrain(x, z))) break;
            n++;
        }
        int[] ys = new int[Math.max(n, 1)];
        int prev = y0;
        for (int i = 0; i < ys.length; i++) {
            double h = view.terrain(d.x() + d.out().dx * (i + 1), d.z() + d.out().dz * (i + 1));
            int g = Double.isNaN(h) ? y0 : (int) Math.floor(SwissCoords.toY(h));
            ys[i] = i == 0 ? y0 : Math.max(prev - 1, Math.min(prev + 1, g));
            prev = ys[i];
        }
        return ys;
    }
}
