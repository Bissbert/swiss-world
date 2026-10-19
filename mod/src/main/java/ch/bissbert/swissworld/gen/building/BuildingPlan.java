package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.Heights;
import ch.bissbert.swissworld.cell.SwdCell;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whole-building layout in world block coordinates: a pure function of the BTBL entry, its spans and the gid.
 * Stages recompute it per chunk and clip to the chunk, so no state crosses chunks.
 * Floor k lies at {@code floors[k]}; flights of stairs rise from floor k to k+1 along {@code stairs.dir}.
 */
public record BuildingPlan(long gid, Style style, Columns cols, int storey, List<Integer> floors, Door door,
                           Stairs stairs, List<Room> rooms, List<Partition> walls, List<Prop> props,
                           List<Light> lights) {

    /** Door column on the facade; {@code out} points outside. The door stands on floor 0. */
    public record Door(int x, int z, Dir out) {}

    /** First step of every flight at (x, z), rising along dir; walk is the side of the parallel landing row. */
    public record Stairs(int x, int z, Dir dir, Dir walk, boolean ladder) {}

    public record Room(int floor, int x0, int z0, int x1, int z1, RoomType type) {}

    /** Wall along x at z = line (alongX) or along z at x = line, cells from..to, with a 2-high gap at gap. */
    public record Partition(int floor, boolean alongX, int line, int from, int to, int gap) {}

    /** facing points into the room (away from the wall it stands against). */
    public record Prop(int x, int y, int z, PropKind kind, Dir facing, RoomType room) {}

    public enum LightKind { GLOW, HANGING, STANDING }

    public record Light(int x, int y, int z, LightKind kind) {}

    public static BuildingPlan of(CellView view, SwdCell home, SwdCell.Building b) {
        return new Planner(Columns.of(view, home, b), b).plan();
    }

    public int floorY(int k) {
        return floors.get(k);
    }

    /** True if floor k has a slab at (x, z): the body reaches below it and there is headroom above it. */
    public boolean floorAt(int k, int x, int z) {
        if (!cols.body(x, z)) return false;
        int y = floors.get(k);
        return k == 0 ? cols.lo(x, z) <= y : y >= cols.lo(x, z) && y + 3 <= cols.topFull(x, z);
    }

    /** Index i of (x, z) along the stair run (0 = first step, s = landing), or -1. */
    public int runIndex(int x, int z) {
        if (stairs == null || stairs.ladder) return -1;
        Dir d = stairs.dir;
        int i = d.alongX() ? (x - stairs.x) * d.dx : (z - stairs.z) * d.dz;
        boolean onRow = d.alongX() ? z == stairs.z : x == stairs.x;
        return onRow && i >= 0 && i <= storey ? i : -1;
    }

    /** Floor k (k >= 1) is open here for the stairwell (headroom over the flight, or the ladder shaft). */
    public boolean cut(int k, int x, int z) {
        if (k == 0 || stairs == null) return false;
        if (stairs.ladder) return x == stairs.x && z == stairs.z && k < floors.size();
        int i = runIndex(x, z);
        return i >= storey - 3 && i <= storey - 2 && i >= 0;
    }

    /** The cells kept free of walls and props: stair run, landing row and ladder. */
    public boolean reserved(int x, int z) {
        if (stairs == null) return false;
        if (stairs.ladder) return x == stairs.x && z == stairs.z;
        return runIndex(x, z) >= 0 || runIndex(x - stairs.walk.dx, z - stairs.walk.dz) >= 0;
    }

    /** Storey height from the BTBL height span and GASTW (floors above ground); 3 or 4 blocks. */
    static int storey(double height, int gastw) {
        if (gastw <= 0) return 4;
        return (int) Math.clamp(Math.round(height / (gastw + 0.7)), 3, 4);
    }

    private static final class Planner {
        final Columns c;
        final SwdCell.Building b;
        final long gid;
        final Style style;
        final int s;
        final List<Integer> floors = new ArrayList<>();
        final List<Room> rooms = new ArrayList<>();
        final List<Partition> walls = new ArrayList<>();
        final List<Prop> props = new ArrayList<>();
        final List<Light> lights = new ArrayList<>();
        final Set<Long> used = new HashSet<>();
        Door door;
        Stairs stairs;
        BuildingPlan view;

        Planner(Columns c, SwdCell.Building b) {
            this.c = c;
            this.b = b;
            this.gid = b.gid();
            int area = 0;
            for (int z = c.minZ; z <= c.maxZ; z++) for (int x = c.minX; x <= c.maxX; x++) if (c.body(x, z)) area++;
            this.style = Style.pick(b, c.baseH, area);
            double topY = c.y(Heights.toMetres(b.topH()));
            this.s = storey(topY - c.baseY, b.floors());
            int ceiling = (int) Math.floor(topY) - 1;
            for (int k = 0; k == 0 || (!style.hall() && c.baseY + k * s + 3 <= ceiling
                    && (b.floors() <= 0 || k <= b.floors())); k++) {
                floors.add(c.baseY + k * s);
            }
        }

        BuildingPlan plan() {
            door = findDoor();
            if (floors.size() > 1) stairs = findStairs();
            view = new BuildingPlan(gid, style, c, s, List.copyOf(floors), door, stairs, rooms, walls, props, lights);
            for (int k = 0; k < floors.size(); k++) layoutFloor(k);
            return new BuildingPlan(gid, style, c, s, List.copyOf(floors), door, stairs, List.copyOf(rooms),
                    List.copyOf(walls), List.copyOf(props), List.copyOf(lights));
        }

        Dir doorSide() {
            Dir d = Dir.ofEntrance(b.entranceSide());
            return d != null ? d : Dir.values()[Hash.pick(gid, 3, 4)];
        }

        /** Marches inward from the bbox edge on lines near the centre until a usable facade column is found. */
        Door findDoor() {
            int y0 = floors.get(0);
            Dir first = doorSide();
            for (Dir out : new Dir[]{first, first.cw().cw(), first.cw(), first.cw().cw().cw()}) {
                boolean alongX = !out.alongX();
                int lo = alongX ? c.minX : c.minZ, hi = alongX ? c.maxX : c.maxZ, mid = Math.floorDiv(lo + hi, 2);
                for (int o = 0; o <= hi - lo; o++) {
                    int u = mid + ((o & 1) == 0 ? o / 2 : -(o + 1) / 2);
                    if (u < lo || u > hi) continue;
                    int v = out == Dir.N ? c.minZ : out == Dir.S ? c.maxZ : out == Dir.W ? c.minX : c.maxX;
                    for (int step = 0; step <= (alongX ? c.maxZ - c.minZ : c.maxX - c.minX); step++, v -= out.dx + out.dz) {
                        int x = alongX ? u : v, z = alongX ? v : u;
                        if (!c.body(x, z)) continue;
                        if (c.facade(x, z, out) && c.lo(x, z) <= y0 && c.topFull(x, z) >= y0 + 3
                                && c.interior(x - out.dx, z - out.dz) && !corner(x, z)) {
                            return new Door(x, z, out);
                        }
                        break;
                    }
                }
            }
            return null;
        }

        boolean corner(int x, int z) {
            int n = 0;
            for (Dir d : Dir.values()) if (c.facade(x, z, d)) n++;
            return n >= 2;
        }

        boolean nearDoor(int x, int z) {
            if (door == null) return false;
            int ix = door.x - door.out.dx, iz = door.z - door.out.dz;
            return Math.abs(x - ix) + Math.abs(z - iz) <= 1;
        }

        /** Straight stacked flights along the long axis next to a wall, else a ladder against a facade. */
        Stairs findStairs() {
            int top = floors.get(floors.size() - 1);
            boolean longX = c.maxX - c.minX >= c.maxZ - c.minZ;
            Dir along = longX ? Dir.E : Dir.S, across = longX ? Dir.S : Dir.E;
            int aLo = longX ? c.minX : c.minZ, aHi = longX ? c.maxX : c.maxZ;
            int rLo = longX ? c.minZ : c.minX, rHi = longX ? c.maxZ : c.maxX;
            int[] rows = {rLo + 1, rHi - 1, rLo + 2, rHi - 2};
            int span = aHi - aLo + 1;
            for (int r : rows) {
                if (r <= rLo || r >= rHi) continue;
                Dir walk = r - rLo < rHi - r ? across : across.opposite();
                for (Dir dir : new Dir[]{along, along.opposite()}) {
                    int rot = Hash.pick(gid, 5, span);
                    for (int o = 0; o < span; o++) {
                        int a = aLo + (o + rot) % span;
                        int x = longX ? a : r, z = longX ? r : a;
                        if (coreFits(x, z, dir, walk, top)) return new Stairs(x, z, dir, walk, false);
                    }
                }
            }
            int n = (c.maxX - c.minX + 1) * (c.maxZ - c.minZ + 1), rot = Hash.pick(gid, 6, n);
            for (int o = 0; o < n; o++) {
                int i = (o + rot) % n, x = c.minX + i % (c.maxX - c.minX + 1), z = c.minZ + i / (c.maxX - c.minX + 1);
                if (!c.interior(x, z) || c.topFull(x, z) < top + 3 || nearDoor(x, z)) continue;
                for (Dir d : Dir.values()) {
                    if (!c.interior(x + d.dx, z + d.dz) && c.body(x + d.dx, z + d.dz)) {
                        return new Stairs(x, z, d.opposite(), d.opposite(), true);
                    }
                }
            }
            return null;
        }

        boolean coreFits(int x, int z, Dir dir, Dir walk, int top) {
            for (int i = 0; i <= s; i++) {
                int px = x + dir.dx * i, pz = z + dir.dz * i;
                for (int w = 0; w <= 1; w++) {
                    int qx = px + walk.dx * w, qz = pz + walk.dz * w;
                    if (!c.interior(qx, qz) || c.topFull(qx, qz) < top + 3 || nearDoor(qx, qz)) return false;
                }
            }
            return true;
        }

        boolean usable(int k, int x, int z) {
            return c.interior(x, z) && view.floorAt(k, x, z) && c.topFull(x, z) >= floors.get(k) + 3
                    && !view.reserved(x, z);
        }

        static long key(int k, int x, int z) {
            return ((long) k << 48) ^ ((long) (x & 0xFFFFFF) << 24) ^ (z & 0xFFFFFF);
        }

        void layoutFloor(int k) {
            List<int[]> rects = new ArrayList<>();
            if (style.hall()) rects.add(new int[]{c.minX + 1, c.minZ + 1, c.maxX - 1, c.maxZ - 1});
            else split(k, c.minX + 1, c.minZ + 1, c.maxX - 1, c.maxZ - 1, 0, rects);
            List<int[]> live = new ArrayList<>();
            for (int[] r : rects) if (anyUsable(k, r)) live.add(r);
            if (k == 0 && door != null) {
                int ix = door.x - door.out.dx, iz = door.z - door.out.dz;
                live.sort(Comparator.comparingInt(r -> Math.abs((r[0] + r[2]) / 2 - ix) + Math.abs((r[1] + r[3]) / 2 - iz)));
            }
            for (int i = 0; i < live.size(); i++) {
                int[] r = live.get(i);
                Room room = new Room(k, r[0], r[1], r[2], r[3], RoomType.of(style, k, i, live.size(), c.baseH));
                rooms.add(room);
                light(room);
                furnish(room, walls);
            }
        }

        boolean anyUsable(int k, int[] r) {
            for (int z = r[1]; z <= r[3]; z++) for (int x = r[0]; x <= r[2]; x++) if (usable(k, x, z)) return true;
            return false;
        }

        /** BSP: split the longer side with a partition at a hashed position, keeping rooms at least 3 wide. */
        void split(int k, int x0, int z0, int x1, int z1, int depth, List<int[]> out) {
            int lx = x1 - x0 + 1, lz = z1 - z0 + 1, min = style == Style.HOTEL ? 7 : 8;
            long h = Hash.mix(gid, 7919L * k + 131L * depth + 31L * x0 + z0);
            if (depth < 4 && Math.max(lx, lz) >= min) {
                boolean cutX = lx >= lz;
                int len = cutX ? lx : lz, p = (cutX ? x0 : z0) + 3 + Hash.pick(h, 1, len - 6);
                int from = cutX ? z0 : x0, to = cutX ? z1 : x1;
                int gap = gap(k, !cutX, p, from, to, h);
                if (gap != Integer.MIN_VALUE) {
                    walls.add(new Partition(k, !cutX, p, from, to, gap));
                    if (cutX) {
                        split(k, x0, z0, p - 1, z1, depth + 1, out);
                        split(k, p + 1, z0, x1, z1, depth + 1, out);
                    } else {
                        split(k, x0, z0, x1, p - 1, depth + 1, out);
                        split(k, x0, p + 1, x1, z1, depth + 1, out);
                    }
                    return;
                }
            }
            out.add(new int[]{x0, z0, x1, z1});
        }

        /** A doorway position on the wall where both sides are usable, or MIN_VALUE. */
        int gap(int k, boolean alongX, int line, int from, int to, long h) {
            int n = to - from + 1, rot = Hash.pick(h, 2, n);
            for (int o = 0; o < n; o++) {
                int u = from + (o + rot) % n;
                int x = alongX ? u : line, z = alongX ? line : u;
                int ax = alongX ? 0 : 1, az = alongX ? 1 : 0;
                if (usable(k, x, z) && usable(k, x - ax, z - az) && usable(k, x + ax, z + az)) return u;
            }
            return Integer.MIN_VALUE;
        }

        boolean nearGap(int k, int x, int z, List<Partition> ws) {
            for (Partition p : ws) {
                if (p.floor != k) continue;
                int gx = p.alongX ? p.gap : p.line, gz = p.alongX ? p.line : p.gap;
                if (Math.abs(x - gx) + Math.abs(z - gz) <= 1) return true;
            }
            return false;
        }

        boolean free(Room r, int x, int z, List<Partition> ws) {
            return x >= r.x0 && x <= r.x1 && z >= r.z0 && z <= r.z1 && usable(r.floor, x, z)
                    && !used.contains(key(r.floor, x, z)) && !(r.floor == 0 && nearDoor(x, z))
                    && !nearGap(r.floor, x, z, ws);
        }

        /** Lights at the room centre (and on a grid in big rooms): glowstone in the slab above, else a lantern. */
        void light(Room r) {
            int k = r.floor, lx = r.x1 - r.x0 + 1, lz = r.z1 - r.z0 + 1;
            int nx = Math.max(1, Math.round(lx / 8f)), nz = Math.max(1, Math.round(lz / 8f));
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < nz; j++) {
                    int cx = r.x0 + (2 * i + 1) * lx / (2 * nx), cz = r.z0 + (2 * j + 1) * lz / (2 * nz);
                    int[] p = nearestFree(r, cx, cz);
                    if (p == null) continue;
                    int x = p[0], z = p[1], y = floors.get(k);
                    if (k + 1 < floors.size() && view.floorAt(k + 1, x, z) && !view.cut(k + 1, x, z)) {
                        lights.add(new Light(x, floors.get(k + 1), z, LightKind.GLOW));
                    } else if (c.topFull(x, z) - 1 >= y + 3) {
                        lights.add(new Light(x, Math.min(c.topFull(x, z) - 1, y + 4), z, LightKind.HANGING));
                    } else {
                        lights.add(new Light(x, y + 1, z, LightKind.STANDING));
                        used.add(key(k, x, z));
                    }
                }
            }
        }

        int[] nearestFree(Room r, int cx, int cz) {
            int best = Integer.MAX_VALUE;
            int[] p = null;
            for (int z = r.z0; z <= r.z1; z++) {
                for (int x = r.x0; x <= r.x1; x++) {
                    int d = Math.abs(x - cx) + Math.abs(z - cz);
                    if (d < best && usable(r.floor, x, z) && !used.contains(key(r.floor, x, z))) {
                        best = d;
                        p = new int[]{x, z};
                    }
                }
            }
            return p;
        }

        /** Props along the room's edge ring, facing inward, container first; doorways stay clear. */
        void furnish(Room r, List<Partition> ws) {
            List<int[]> ring = new ArrayList<>();
            for (int x = r.x0; x <= r.x1; x++) ring.add(new int[]{x, r.z0, Dir.S.ordinal()});
            for (int z = r.z0 + 1; z <= r.z1; z++) ring.add(new int[]{r.x1, z, Dir.W.ordinal()});
            if (r.z1 > r.z0) for (int x = r.x1 - 1; x >= r.x0; x--) ring.add(new int[]{x, r.z1, Dir.N.ordinal()});
            if (r.x1 > r.x0) for (int z = r.z1 - 1; z > r.z0; z--) ring.add(new int[]{r.x0, z, Dir.E.ordinal()});
            int n = ring.size(), rot = Hash.pick(gid, 11L * r.floor + 7L * r.x0 + r.z0, n), next = 0;
            int y = floors.get(r.floor) + 1;
            boolean container = false;
            for (PropKind kind : r.type.props) {
                for (; next < n; next++) {
                    int[] q = ring.get((next + rot) % n);
                    int x = q[0], z = q[1];
                    Dir in = Dir.values()[q[2]];
                    if (!free(r, x, z, ws)) continue;
                    if (kind == PropKind.BED && !free(r, x + in.dx, z + in.dz, ws)) continue;
                    props.add(new Prop(x, y, z, kind, in, r.type));
                    used.add(key(r.floor, x, z));
                    if (kind == PropKind.BED) used.add(key(r.floor, x + in.dx, z + in.dz));
                    container |= kind.container();
                    next++;
                    break;
                }
            }
            if (!container) {
                int[] p = nearestFree(r, r.x0, r.z0);
                if (p != null && free(r, p[0], p[1], ws)) {
                    props.add(new Prop(p[0], y, p[1], r.type.props.get(0), Dir.S, r.type));
                    used.add(key(r.floor, p[0], p[1]));
                }
            }
        }
    }
}
