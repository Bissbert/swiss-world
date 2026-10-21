package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.SwissCoords;
import java.util.ArrayDeque;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One building rendered by the real stages (shell + furnish, chunk by chunk like the game) into a block array over its
 * bbox grown by {@link #PAD}, on top of solid terrain (everything up to the ground height of the column).
 */
final class BuildingRender {
    static final int PAD = Buildings.MARGIN + 2;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    final BuildingPlan p;
    final CellView view;
    final Columns c;
    final int x0, z0, y0, w, d, h;
    final BlockState[] blocks;
    /** True if a column in the padded box has no terrain (cell missing): the render is incomplete there. */
    boolean incomplete;

    BuildingRender(BuildingPlan p, CellView view) {
        this.p = p;
        this.view = view;
        this.c = p.cols();
        x0 = c.minX - PAD;
        z0 = c.minZ - PAD;
        w = c.maxX - c.minX + 1 + 2 * PAD;
        d = c.maxZ - c.minZ + 1 + 2 * PAD;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        int[] ground = new int[w * d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                double t = view.terrain(x0 + x, z0 + z);
                if (Double.isNaN(t)) {
                    incomplete = true;
                    ground[z * w + x] = c.baseY;
                } else {
                    ground[z * w + x] = (int) Math.floor(SwissCoords.toY(t));
                }
                lo = Math.min(lo, ground[z * w + x]);
                hi = Math.max(hi, ground[z * w + x]);
            }
        }
        for (int z = c.minZ; z <= c.maxZ; z++) {
            for (int x = c.minX; x <= c.maxX; x++) {
                if (c.state(x, z) == Columns.UNKNOWN) incomplete = true;
                if (c.body(x, z)) {
                    lo = Math.min(lo, c.lo(x, z));
                    hi = Math.max(hi, c.topFull(x, z) + 2);
                } else if (c.state(x, z) == Columns.EAVE) {
                    hi = Math.max(hi, c.eave2(x, z) / 2 + 2);
                }
            }
        }
        y0 = lo - 3;
        h = hi + 6 - y0;
        blocks = new BlockState[w * d * h];
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) blocks[idx(x, y, z)] = y0 + y <= ground[z * w + x] ? stone : AIR;
            }
        }
        for (int cz = Math.floorDiv(z0, 16); cz <= Math.floorDiv(z0 + d - 1, 16); cz++) {
            for (int cx = Math.floorDiv(x0, 16); cx <= Math.floorDiv(x0 + w - 1, 16); cx++) {
                Put put = new Put(cx * 16, cz * 16, y0 - 1000, y0 + h + 1000, (pos, s) -> set(pos.getX(), pos.getY(), pos.getZ(), s));
                if (p.style() != Style.CANOPY || true) {
                    BuildingStage.shell(p, put, view);
                    if (!p.style().empty()) FurnishStage.furnish(p, put);
                }
            }
        }
    }

    int idx(int x, int y, int z) {
        return (y * d + z) * w + x;
    }

    void set(int x, int y, int z, BlockState s) {
        if (in(x, y, z)) blocks[idx(x - x0, y - y0, z - z0)] = s;
    }

    boolean in(int x, int y, int z) {
        return x >= x0 && x < x0 + w && z >= z0 && z < z0 + d && y >= y0 && y < y0 + h;
    }

    BlockState at(int x, int y, int z) {
        return in(x, y, z) ? blocks[idx(x - x0, y - y0, z - z0)] : AIR;
    }

    boolean air(int x, int y, int z) {
        return at(x, y, z).isAir();
    }

    /** Air cells reachable from the box boundary through air (6-neighbour). Doors, glass and panes count as solid. */
    boolean[] outside() {
        boolean[] seen = new boolean[blocks.length];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1 || y == h - 1;
                    if (edge && blocks[idx(x, y, z)].isAir()) {
                        seen[idx(x, y, z)] = true;
                        q.add(new int[]{x, y, z});
                    }
                }
            }
        }
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] a = q.poll();
            for (int[] o : n) {
                int x = a[0] + o[0], y = a[1] + o[1], z = a[2] + o[2];
                if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= d) continue;
                int i = idx(x, y, z);
                if (!seen[i] && blocks[i].isAir()) {
                    seen[i] = true;
                    q.add(new int[]{x, y, z});
                }
            }
        }
        return seen;
    }

    /** True if no body column of the building has a side onto open ground (not its own body, not a neighbour). */
    static boolean enclosed(BuildingRender r) {
        Columns c = r.p.cols();
        for (int z = c.minZ; z <= c.maxZ; z++) {
            for (int x = c.minX; x <= c.maxX; x++) {
                if (!c.body(x, z)) continue;
                for (Dir d : Dir.values()) {
                    int nx = x + d.dx, nz = z + d.dz;
                    if (!c.body(nx, nz) && !r.view.foreign(nx, nz, r.p.gid())) return false;
                }
            }
        }
        return true;
    }

    /** Problems of this render: "leak ...", "nodoor", "door ..." (empty if fine). */
    static java.util.List<String> check(BuildingRender r) {
        java.util.List<String> res = new java.util.ArrayList<>();
        BuildingPlan p = r.p;
        Columns c = r.c;
        boolean[] out = r.outside();
        res.addAll(r.gaps());
        res.addAll(r.darkRooms());
        if (p.floors().size() > 1 && p.stairs() == null) res.add("nostairs floors=" + p.floors().size());
        res.addAll(r.coreAndCavities());
        int open = 0;
        String firstPane = "";
        for (int z = c.minZ; z <= c.maxZ; z++) {
            for (int x = c.minX; x <= c.maxX; x++) {
                for (int y = r.y0; y < r.y0 + r.h; y++) {
                    if (!r.in(x, y, z)) continue;
                    BlockState b = r.at(x, y, z);
                    if (!(b.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock)) continue;
                    boolean ew = b.getValue(net.minecraft.world.level.block.CrossCollisionBlock.EAST);
                    boolean ns = b.getValue(net.minecraft.world.level.block.CrossCollisionBlock.NORTH);
                    // a pane must be closed along its run: the cells it points at hold a block (wall, pane, door)
                    boolean bad = ew == ns;
                    for (Dir d : Dir.values()) {
                        if (!(d.alongX() ? ew : ns)) continue;
                        if (r.at(x + d.dx, y, z + d.dz).isAir()) bad = true;
                    }
                    if (bad && open++ == 0) firstPane = " first " + x + "," + y + "," + z;
                }
            }
        }
        if (open > 0) res.add("pane-open " + open + firstPane);
        // at a missing cell edge the neighbouring columns are unknown: leaks are judged by the multi-cell sweep
        if (!r.incomplete && p.style() != Style.BRIDGE && p.style() != Style.CANOPY) {
            int n = 0;
            String first = "";
            for (int z = c.minZ; z <= c.maxZ; z++) {
                for (int x = c.minX; x <= c.maxX; x++) {
                    if (!c.body(x, z) || p.style() == Style.TANK && !BuildingStage.round(c, x, z)) continue;
                    for (int y = c.lo(x, z) + 1; y < c.topFull(x, z); y++) {
                        if (r.in(x, y, z) && out[r.idx(x - r.x0, y - r.y0, z - r.z0)]) {
                            if (n++ == 0) first = " first " + x + "," + y + "," + z;
                        }
                    }
                }
            }
            if (n > 0) res.add("leak " + n + " cells" + first + holes(r, out));
        }
        if (p.rooms().isEmpty()) return res;
        BuildingPlan.Door dr = p.door();
        if (dr == null) {
            // a building whose every facade faces a neighbour gets no door (BuildingPlan#findDoor): hemmed in
            res.add(enclosed(r) ? "hemmed enclosed" : "nodoor");
            return res;
        }
        int y0 = p.floorY(0) + dr.up(), x = dr.x(), z = dr.z();
        int ix = x - dr.out().dx, iz = z - dr.out().dz, ox = x + dr.out().dx, oz = z + dr.out().dz;
        if (!(r.at(x, y0 + 1, z).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                || !(r.at(x, y0 + 2, z).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) {
            res.add("door missing " + r.at(x, y0 + 1, z) + " / " + r.at(x, y0 + 2, z));
        } else if (!r.air(ix, y0 + 1, iz) || !r.air(ix, y0 + 2, iz) || r.air(ix, y0, iz)) {
            res.add((r.c.interior(ix, iz) ? "door" : "thin") + " inside-blocked " + r.at(ix, y0 + 1, iz).getBlock().getDescriptionId() + "/" + r.at(ix, y0 + 2, iz).getBlock().getDescriptionId() + "/" + r.at(ix, y0, iz).getBlock().getDescriptionId());
        } else if (r.view.foreign(ox, oz, p.gid())) {
            res.add("hemmed outside-neighbour");
        } else if (!r.air(ox, y0 + 1, oz) || !r.air(ox, y0 + 2, oz) || r.air(ox, y0, oz)) {
            res.add("door outside-not-walkable");
        } else if (!out[r.idx(ox - r.x0, y0 + 1 - r.y0, oz - r.z0)]) {
            res.add("door outside-enclosed");
        } else if (!r.incomplete && !r.walkOut(ox, y0 + 1, oz, true)) {
            // hemmed in: other buildings (counted as solid) wall the landing in; else the door itself is at fault
            res.add((r.walkOut(ox, y0 + 1, oz, false) ? "hemmed" : "door") + " landing-unreachable seen=" + r.walkSeen + " [" + r.walkEnd + "]");
        }
        return res;
    }

    /**
     * Entrance quality: "pit" = within the first four cells outside the door one has a solid block two above the walking
     * level on both sides (a 1-wide trench the player cannot step out of); "sunken d" = the landing is d > 1 blocks below the highest terrain within three blocks
     * (the building itself and neighbouring buildings left out) and the way out is not at least two wide. A wide cut
     * (stairs down two or more wide between retaining walls) is a proper sunken entrance and is not reported.
     * Empty if fine or no door.
     */
    java.util.List<String> entrance() {
        java.util.List<String> res = new java.util.ArrayList<>();
        BuildingPlan.Door dr = p.door();
        if (dr == null || p.rooms().isEmpty()) return res;
        int y = p.floorY(0) + dr.up();
        int ox = dr.x() + dr.out().dx, oz = dr.z() + dr.out().dz;
        int worst = 0;
        for (int z = oz - 3; z <= oz + 3; z++) {
            for (int x = ox - 3; x <= ox + 3; x++) {
                if (c.state(x, z) == Columns.BODY || view.foreign(x, z, p.gid())) continue;
                double h = view.terrain(x, z);
                if (!Double.isNaN(h)) worst = Math.max(worst, (int) Math.floor(SwissCoords.toY(h)) - y);
            }
        }
        int[] ys = BuildingStage.trace(c, dr, y, view);
        int n = Math.min(4, ys == null ? 1 : ys.length);
        int minWidth = 3;
        boolean pit = false;
        Dir l = dr.out().cw(), rr = l.opposite();
        for (int i = 0; i < n; i++) {
            int x = dr.x() + dr.out().dx * (i + 1), z = dr.z() + dr.out().dz * (i + 1);
            int sy = ys == null ? y : ys[i];
            int width = 1;
            boolean blockedL = !air(x + l.dx, sy + 2, z + l.dz); // a one block rise at the side can be stepped onto
            boolean blockedR = !air(x + rr.dx, sy + 2, z + rr.dz);
            for (Dir s : new Dir[]{l, rr}) {
                int lx = x + s.dx, lz = z + s.dz;
                if (air(lx, sy + 1, lz) && air(lx, sy + 2, lz) && (!air(lx, sy, lz) || !air(lx, sy - 1, lz))) width++;
            }
            minWidth = Math.min(minWidth, width);
            pit |= blockedL && blockedR;
        }
        if (pit) res.add("pit");
        if (worst > 1 && minWidth < 2) res.add("sunken " + worst);
        return res;
    }

    /**
     * Visible roof gaps in the footprint: "gap-under" = a roof stair or slab of a body column with air directly
     * below it (the dark underside seen from the side of a stepped roof), "roof-hole" = a body column whose
     * topmost block is not roof material. Eave columns (outside the walls) overhang on purpose and do not count.
     */
    java.util.List<String> gaps() {
        java.util.List<String> res = new java.util.ArrayList<>();
        if (p.style().empty() || p.style() == Style.TANK) return res;
        Palette pal = Palette.of(p.style(), p.gid());
        int under = 0, holes = 0;
        String fu = "", fh = "";
        for (int z = c.minZ; z <= c.maxZ; z++) {
            for (int x = c.minX; x <= c.maxX; x++) {
                if (!c.body(x, z) || p.style() == Style.WALL) continue;
                int top = c.topFull(x, z);
                int y = Math.min(y0 + h - 1, top + 2);
                while (y >= y0 && at(x, y, z).isAir()) y--;
                net.minecraft.world.level.block.Block b = at(x, y, z).getBlock();
                boolean shaped = b == pal.roofStairs() || b == pal.roofSlab();
                if (!shaped && b != Blocks.GLOWSTONE && b != pal.roof() && b != pal.wall() && b != pal.base() && b != Blocks.STONE) {
                    holes++;
                    if (fh.isEmpty()) fh = x + "," + y + "," + z + " " + b.getDescriptionId();
                }
                if (shaped && y - 1 > c.lo(x, z) && air(x, y - 1, z)) {
                    under++;
                    if (fu.isEmpty()) fu = x + "," + y + "," + z;
                }
            }
        }
        if (under > 0) res.add("gap-under n=" + under + " first=" + fu);
        if (holes > 0) res.add("roof-hole n=" + holes + " first=" + fh);
        return res;
    }

    /** Block light levels (0..15) of the padded box: lanterns, glowstone and the like, spreading through non-solid cells. */
    byte[] light() {
        byte[] lv = new byte[blocks.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < blocks.length; i++) {
            int e = blocks[i].getLightEmission();
            if (e > 0) {
                lv[i] = (byte) e;
                q.add(i);
            }
        }
        while (!q.isEmpty()) {
            int i = q.poll();
            int x = i % w, z = (i / w) % d, y = i / (w * d);
            int l = lv[i] - 1;
            if (l <= 0) continue;
            int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] o : n) {
                int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                if (nx < 0 || nz < 0 || ny < 0 || nx >= w || nz >= d || ny >= h) continue;
                int j = idx(nx, ny, nz);
                BlockState b = blocks[j];
                if (lv[j] >= l || b.canOcclude() && b.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO)) continue;
                lv[j] = (byte) l;
                q.add(j);
            }
        }
        return lv;
    }

    /**
     * What the acceptance script checks: a building 8 or more blocks tall has a stairs or ladder block inside its
     * footprint between base+1 and top-3, and no closed pocket of air of 8 or more blocks lies under or inside it.
     */
    java.util.List<String> coreAndCavities() {
        java.util.List<String> res = new java.util.ArrayList<>();
        int base = Integer.MAX_VALUE, top = Integer.MIN_VALUE, ax = Integer.MAX_VALUE, bx = Integer.MIN_VALUE, az = Integer.MAX_VALUE, bz = Integer.MIN_VALUE;
        for (int z = c.minZ; z <= c.maxZ; z++) {
            for (int x = c.minX; x <= c.maxX; x++) {
                if (!c.body(x, z)) continue;
                base = Math.min(base, c.lo(x, z));
                top = Math.max(top, c.topFull(x, z));
                ax = Math.min(ax, x); bx = Math.max(bx, x); az = Math.min(az, z); bz = Math.max(bz, z);
            }
        }
        if (base == Integer.MAX_VALUE || p.style() == Style.TANK) return res; // a tank is a sealed cylinder, not a building to enter
        if (top - base >= 8 && bx - ax >= 4 && bz - az >= 4) { // 3-wide towers have no cell strictly inside to hold a core
            boolean has = false;
            for (int y = base + 1; y <= top - 3 && !has; y++) {
                for (int z = az; z <= bz && !has; z++) {
                    for (int x = ax; x <= bx && !has; x++) {
                        String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(at(x, y, z).getBlock()).getPath();
                        has = n.endsWith("_stairs") || n.equals("ladder");
                    }
                }
            }
            if (!has) res.add("nocore height=" + (top - base) + " floors=" + p.floors().size() + " wanted=" + p.wanted() + " style=" + p.style());
        }
        // closed air pockets (6-connected, no air path to the outside of the box or above the footprint) of >= 8 cells
        java.util.Set<Integer> air = new java.util.HashSet<>();
        for (int y = base; y < top - 4; y++) { // roof voids under the ridge are shell, not rooms
            for (int z = az; z <= bz; z++) {
                for (int x = ax; x <= bx; x++) if (c.body(x, z) && air(x, y, z)) air.add(idx(x - x0, y - y0, z - z0));
            }
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int start : air) {
            if (!seen.add(start)) continue;
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(start);
            int n = 0;
            boolean out = false, lit = false;
            byte[] lv = light();
            while (!q.isEmpty()) {
                int i = q.poll();
                n++;
                if (lv[i] > 0) lit = true;
                int x = i % w + x0, z = (i / w) % d + z0, y = i / (w * d) + y0;
                int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                for (int[] o : nb) {
                    int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                    if (!in(nx, ny, nz)) continue;
                    int j = idx(nx - x0, ny - y0, nz - z0);
                    if (air.contains(j)) {
                        if (seen.add(j)) q.add(j);
                    } else if (!c.body(nx, nz) && air(nx, ny, nz)) out = true;
                }
            }
            if (n >= 8 && !out && !lit) res.add("dark-pocket cells=" + n + " at " + (start % w + x0) + "," + (start / (w * d) + y0) + "," + ((start / w) % d + z0));
        }
        return res;
    }

    /** Cells a mob could spawn on in the rooms: air with air above, a solid top face below. */
    java.util.List<String> darkRooms() {
        java.util.List<String> res = new java.util.ArrayList<>();
        byte[] lv = light();
        java.util.Set<Long> boxes = new java.util.HashSet<>();
        for (BuildingPlan.Prop pr : p.props()) if (pr.kind().container()) boxes.add(((long) pr.x() << 32) ^ (pr.z() & 0xFFFFFFFFL) ^ ((long) pr.y() << 52));
        for (BuildingPlan.Room room : p.rooms()) {
            int y = p.floorY(room.floor()), dark = 0, cells = 0;
            String first = "";
            for (int z = room.z0(); z <= room.z1(); z++) {
                for (int x = room.x0(); x <= room.x1(); x++) {
                    if (boxes.contains(((long) x << 32) ^ (z & 0xFFFFFFFFL) ^ ((long) (y + 1) << 52)) || !c.interior(x, z) || !p.floorAt(room.floor(), x, z) || c.cover(x, z) - y < 3 || !in(x, y + 1, z) || !air(x, y + 1, z) || !air(x, y + 2, z) || at(x, y, z).isAir()) continue;
                    cells++;
                    // a nook sealed on all four sides by walls, furniture or chests cannot be entered: not a room cell
                    boolean sealed = true;
                    for (Dir dd : Dir.values()) {
                        sealed &= !air(x + dd.dx, y + 1, z + dd.dz) || boxes.contains(((long) (x + dd.dx) << 32) ^ ((z + dd.dz) & 0xFFFFFFFFL) ^ ((long) (y + 1) << 52));
                    }
                    if (sealed) continue;
                    if (lv[idx(x - x0, y + 1 - y0, z - z0)] == 0) {
                        final int xx = x, zz = z, yy = y;
                        dark++;
                        if (first.isEmpty()) first = x + "," + (y + 1) + "," + z + " cov=" + (c.cover(x, z) - y) + " res=" + p.reserved(x, z) + " props=" + p.props().stream().filter(q -> Math.abs(q.x() - xx) + Math.abs(q.z() - zz) <= 1 && q.y() == yy + 1).map(q -> q.kind() + "@" + q.x() + "," + q.z()).toList() + " walls=" + p.walls().stream().filter(w -> w.floor() == room.floor()).filter(w -> Math.abs((w.alongX() ? w.line() - zz : w.line() - xx)) <= 1).map(w -> (w.alongX() ? "Z" : "X") + w.line() + "[" + w.from() + ".." + w.to() + "g" + w.gap() + "]").toList() + " lv=" + lv[idx(x - x0, y + 1 - y0, z - z0)] + " nb=" + at(x - 1, y + 1, z).getBlock().getDescriptionId().replace("block.minecraft.", "") + "," + at(x + 1, y + 1, z).getBlock().getDescriptionId().replace("block.minecraft.", "") + "," + at(x, y + 1, z - 1).getBlock().getDescriptionId().replace("block.minecraft.", "") + "," + at(x, y + 1, z + 1).getBlock().getDescriptionId().replace("block.minecraft.", "");
                    }
                }
            }
            if (dark > 0) res.add("dark-room " + room.type() + " f" + room.floor() + " " + dark + "/" + cells + " first=" + first);
        }
        return res;
    }

    /** Walking (up/down one block, falls up to 3) from the landing to the padded box edge. */
    int walkSeen;
    String walkEnd = "";

    boolean walkOut(int sx, int sy, int sz, boolean neighboursBlock) {
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sy, sz});
        seen.add(key(sx, sy, sz));
        int[][] n = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] a = q.poll();
            if (a[0] <= x0 + 1 || a[2] <= z0 + 1 || a[0] >= x0 + w - 2 || a[2] >= z0 + d - 2) return true;
            for (int[] o : n) {
                int x = a[0] + o[0], z = a[2] + o[1];
                if (neighboursBlock && view.foreign(x, z, p.gid())) continue; // a neighbouring building stands there
                for (int dy = 1; dy >= -3; dy--) {
                    int y = a[1] + dy;
                    if (dy == 1 && !air(a[0], a[1] + 2, a[2])) continue;
                    if (air(x, y, z) && air(x, y + 1, z) && !air(x, y - 1, z) && !at(x, y - 1, z).getFluidState().isSource()
                            && seen.add(key(x, y, z))) {
                        q.add(new int[]{x, y, z});
                        break;
                    }
                }
            }
        }
        walkSeen = seen.size();
        walkEnd = "start " + sx + "," + sy + "," + sz;
        return false;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }

    /** Leaked interior cells touching leaked air that is not interior: where the envelope is open (first 4). */
    static String holes(BuildingRender r, boolean[] out) {
        Columns c = r.c;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int z = c.minZ; z <= c.maxZ && n < 4; z++) {
            for (int x = c.minX; x <= c.maxX && n < 4; x++) {
                if (!c.body(x, z)) continue;
                for (int y = c.lo(x, z) + 1; y < c.topFull(x, z) && n < 4; y++) {
                    if (!r.in(x, y, z) || !out[r.idx(x - r.x0, y - r.y0, z - r.z0)]) continue;
                    for (int[] o : nb) {
                        int a = x + o[0], b = y + o[1], d = z + o[2];
                        boolean inner = c.body(a, d) && b > c.lo(a, d) && b < c.topFull(a, d);
                        if (!inner && r.in(a, b, d) && out[r.idx(a - r.x0, b - r.y0, d - r.z0)]) {
                            sb.append(String.format(" [hole %d,%d,%d(top %d lo %d) -> %d,%d,%d colstate=%d lo=%s top=%s above=%s]", x, y, z,
                                    c.topFull(x, z), c.lo(x, z), a, b, d,
                                    (int) c.state(a, d), c.body(a, d) ? c.lo(a, d) : "-", c.body(a, d) ? c.topFull(a, d) : "-",
                                    r.at(a, b, d).getBlock().getDescriptionId()));
                            n++;
                            break;
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Side view through the door, inside to outside (columns), y0+5 down to y0-6 (rows). */
    String dump() {
        BuildingPlan.Door dr = p.door();
        if (dr == null) return "no door";
        int y0 = p.floorY(0);
        StringBuilder sb = new StringBuilder("door " + dr + " y0=" + y0 + "\n");
        for (int y = y0 + 5; y >= y0 - 6; y--) {
            sb.append(String.format("%5d ", y));
            for (int o = -4; o <= 11; o++) {
                BlockState b = at(dr.x() + dr.out().dx * o, y, dr.z() + dr.out().dz * o);
                String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();
                sb.append(b.isAir() ? '.' : n.contains("door") ? 'D' : n.contains("stairs") ? 'S' : n.contains("slab") ? '_' : n.contains("pane") || n.contains("glass") ? 'G' : '#');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** XY slice at z for x in [xa, xb], y from top down. */
    String slice(int z, int xa, int xb, int ya, int yb) {
        StringBuilder sb = new StringBuilder("slice z=" + z + " x " + xa + ".." + xb + " (air shows block light hex)\n");
        byte[] lv = light();
        for (int y = yb; y >= ya; y--) {
            sb.append(String.format("%6d ", y));
            for (int x = xa; x <= xb; x++) {
                BlockState b = at(x, y, z);
                String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();
                sb.append(b.getLightEmission() > 0 ? 'L' : b.isAir() ? (in(x, y, z) ? Character.forDigit(lv[idx(x - x0, y - y0, z - z0)], 16) : '.') : n.contains("door") ? 'D' : n.contains("stairs") ? 'S' : n.contains("slab") ? '_' : n.contains("pane") || n.contains("glass") ? 'G' : '#');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** XZ plan at world y, x and z ranges: air shows light hex, solids their first letter. */
    String level(int y, int xa, int xb, int za, int zb) {
        StringBuilder sb = new StringBuilder("level y=" + y + "\n");
        byte[] lv = light();
        for (int z = za; z <= zb; z++) {
            sb.append(String.format("%5d ", z));
            for (int x = xa; x <= xb; x++) {
                BlockState b = at(x, y, z);
                String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();
                sb.append(b.isAir() ? (in(x, y, z) ? Character.forDigit(lv[idx(x - x0, y - y0, z - z0)], 16) : '.') : Character.toUpperCase(n.charAt(0)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Top-down: height of the highest block above y0 of the plan (hex digit) and its kind (S stairs, _ slab, # block). */
    String top() {
        StringBuilder sb = new StringBuilder("top view (rel to floor 0; S stairs, _ slab, # block)\n");
        for (int z = c.minZ - 1; z <= c.maxZ + 1; z++) {
            for (int x = c.minX - 1; x <= c.maxX + 1; x++) {
                int y = this.y0 + h - 1;
                while (y >= this.y0 && at(x, y, z).isAir()) y--;
                BlockState b = at(x, y, z);
                String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();
                char k = n.contains("stairs") ? 'S' : n.contains("slab") ? '_' : '#';
                sb.append(String.format("%c%c ", k, Character.forDigit(Math.floorMod(y - p.floorY(0), 36), 36)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
