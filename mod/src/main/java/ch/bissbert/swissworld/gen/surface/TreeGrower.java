package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.gen.surface.TreePlan.Axis;
import ch.bissbert.swissworld.gen.surface.TreePlan.Part;
import ch.bissbert.swissworld.gen.surface.TreePlan.Site;
import ch.bissbert.swissworld.gen.surface.TreePlan.Species;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Grows one tree for its spot: the shape follows the species' archetype and the {@link Site} (free room per direction,
 * slope, light, altitude, wind), and is random only through the tree's own hash, so every chunk grows the same tree.
 * <ul>
 *   <li>Spruce, larch: excurrent. A leader to the top, whorls of branches every 2 to 3 blocks along a cone, each branch
 *       a line of logs carrying needle pads on its outer two thirds (spruce drooping, larch sparse and open).</li>
 *   <li>Oak/beech, birch, fruit, willow: space colonisation (Runions et al. 2007). Attractors fill the crown envelope
 *       (a hollow dome cut to the free room), limbs grow from the stem towards them, leaves are clusters around the
 *       limb tips only. Willows hang curtains of leaves from their tips.</li>
 *   <li>Mountain pine (krummholz): a few prostrate stems spreading downwind, dense needles on their outer parts.</li>
 * </ul>
 * Branches start at a crown base that rises where neighbours crowd the tree (self-pruning) and on the uphill side;
 * each direction reaches only as far as its room, so neighbouring crowns meet at their tips and leave light between
 * them. Blocks the site marks as solid (terrain, banks, buildings, the clearance over roads) are left out before the
 * leaf distances are computed, so as placed every leaf has exactly its vanilla distance (at most
 * {@value TreePlan#LEAF_REACH}) and never decays.
 */
final class TreeGrower {
    private static final double TAU = 2 * Math.PI;

    private TreeGrower() {}

    static List<Part> grow(Species s, int height, long hash, Site site) {
        int r = 1;
        for (float f : site.room()) r = Math.max(r, (int) Math.ceil(f));
        Box b = new Box(Math.min(TreePlan.REACH, r), Math.max(2, height), site, new SplittableRandom(hash));
        switch (s) {
            case SPRUCE -> b.conifer(false);
            case LARCH -> b.conifer(true);
            case OAK -> b.broadleaf(Form.OAK);
            case BIRCH -> b.broadleaf(Form.BIRCH);
            case FRUIT -> b.broadleaf(Form.FRUIT);
            case WILLOW -> b.broadleaf(Form.WILLOW);
            case PINE -> b.pine();
            case BUSH -> b.bush();
        }
        return b.parts();
    }

    /**
     * Space colonisation per broadleaf form: crown base on a solitary tree and in a closed stand (fractions of the
     * height), where the stem ends inside the crown, crown centre, attractors per block of crown, influence and kill
     * distance, upward tropism, leaf cluster radius and fill.
     */
    private enum Form {
        OAK(0.2, 0.5, 0.35, 0.5, 0.1, 8, 1.8, 0.25, 2.1, 0.8),
        BIRCH(0.25, 0.55, 0.75, 0.55, 0.1, 6, 1.6, 0.45, 1.9, 0.8),
        FRUIT(0.35, 0.45, 0, 0.5, 0.14, 6, 1.6, 0.35, 1.7, 0.85),
        WILLOW(0.2, 0.35, 0.25, 0.5, 0.1, 7, 1.8, 0.05, 1.5, 0.75);

        final double soloBase, denseBase, stem, centre, attractors, influence, kill, tropism, leafR, fill;

        Form(double soloBase, double denseBase, double stem, double centre, double attractors, double influence,
                double kill, double tropism, double leafR, double fill) {
            this.soloBase = soloBase;
            this.denseBase = denseBase;
            this.stem = stem;
            this.centre = centre;
            this.attractors = attractors;
            this.influence = influence;
            this.kill = kill;
            this.tropism = tropism;
            this.leafR = leafR;
            this.fill = fill;
        }
    }

    /** The tree under construction: (2r + 1)^2 x h cells around the trunk column, each empty, leaf or log. */
    private static final class Box {
        private static final byte EMPTY = 0, LEAF = 1, LOG_X = 2, LOG_Y = 3, LOG_Z = 4;
        private static final int[] N6 = {1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};
        final int r, h, side;
        final byte[] cell;
        final Site site;
        final SplittableRandom rnd;
        /** Free room of the 8 directions (east, south-east, south, ...: x east, z south). */
        final float[] room;
        /** Height above treeline-ish altitude, 0 below 1800 m, 1 at 2200 m; and wind from the west near it. */
        final double alpine, wind;
        /** Unit vector to the light (south and downslope), and how strongly the crown leans to it. */
        final double lightX, lightZ, light;
        final double slopeX, slopeZ, slope;

        Box(int r, int h, Site site, SplittableRandom rnd) {
            this.r = r;
            this.h = h;
            this.site = site;
            this.rnd = rnd;
            side = 2 * r + 1;
            cell = new byte[side * side * h];
            room = site.room();
            alpine = Math.max(0, Math.min(1, (site.metres() - 1800) / 400));
            wind = Math.max(0, (alpine - 0.4) / 0.6);
            slopeX = site.slopeX();
            slopeZ = site.slopeZ();
            slope = Math.hypot(slopeX, slopeZ);
            // light from the south, more on a south-facing slope; downslope (gradient negative) opens the crown too
            double lx = -slopeX * 0.8, lz = 0.5 - slopeZ * 0.8;
            double l = Math.hypot(lx, lz);
            lightX = l > 0 ? lx / l : 0;
            lightZ = l > 0 ? lz / l : 1;
            light = Math.min(1, l);
        }

        boolean inside(int dx, int dy, int dz) {
            return Math.abs(dx) <= r && Math.abs(dz) <= r && dy >= 0 && dy < h;
        }

        int k(int dx, int dy, int dz) {
            return (dy * side + dz + r) * side + dx + r;
        }

        void leaf(int dx, int dy, int dz) {
            if (inside(dx, dy, dz) && cell[k(dx, dy, dz)] == EMPTY) cell[k(dx, dy, dz)] = LEAF;
        }

        void log(int dx, int dy, int dz, Axis a) {
            if (inside(dx, dy, dz)) cell[k(dx, dy, dz)] = (byte) (LOG_X + a.ordinal());
        }

        boolean isLog(int dx, int dy, int dz) {
            return inside(dx, dy, dz) && cell[k(dx, dy, dz)] >= LOG_X;
        }

        /** Free room towards angle a (radians, 0 = east, pi/2 = south), between the two nearest of the 8 directions. */
        double room(double a) {
            double f = (a / TAU * 8) % 8;
            if (f < 0) f += 8;
            int i = (int) f;
            double t = f - i;
            return room[i] * (1 - t) + room[(i + 1) % 8] * t;
        }

        /** Largest room of any direction: the crown's natural radius here. */
        double maxRoom() {
            double m = 0;
            for (float f : room) m = Math.max(m, f);
            return m;
        }

        /** 1 where nothing crowds the tree towards a, 0 where its room is gone. */
        double freedom(double a) {
            return Math.min(1, room(a) / Math.max(1, site.free()));
        }

        /** Branch length factor towards a: fuller to the light, cut back on the windward (west) side near the treeline. */
        double vigour(double a) {
            double c = Math.cos(a), s = Math.sin(a);
            double v = 1 + 0.15 * light * (c * lightX + s * lightZ);
            return v * (1 - 0.75 * wind * Math.max(0, -c));
        }

        /** Lowest branch towards a: low on an open side, high in a closed stand, and above the slope uphill. */
        double base(double a, double solo, double dense) {
            double b = h * (solo + (dense - solo) * (1 - freedom(a))) * (1 - 0.4 * alpine);
            double uphill = slopeX * Math.cos(a) + slopeZ * Math.sin(a);
            return b + Math.max(0, uphill) * room(a);
        }

        /** Trunk column at height dy: leans one block downslope on steep ground, from a third of the height. */
        int[] stem(int dy) {
            if (slope < 0.6 || h < 10 || dy < h / 3) return new int[] {0, 0};
            return Math.abs(slopeX) >= Math.abs(slopeZ) ? new int[] {slopeX > 0 ? -1 : 1, 0} : new int[] {0, slopeZ > 0 ? -1 : 1};
        }

        /** Trunk logs from the ground to {@code top}: 2 x 2 at the foot of tall trees, the lean joined face to face. */
        void trunk(int top) {
            int thick = h >= 25 ? (int) Math.round(0.35 * h) : 0;
            int[] prev = stem(0);
            for (int dy = 0; dy <= top; dy++) {
                int[] p = stem(dy);
                if (p[0] != prev[0] || p[1] != prev[1]) log(prev[0], dy, prev[1], Axis.Y);
                log(p[0], dy, p[1], Axis.Y);
                if (dy < thick) {
                    log(p[0] + 1, dy, p[1], Axis.Y);
                    log(p[0], dy, p[1] + 1, Axis.Y);
                    log(p[0] + 1, dy, p[1] + 1, Axis.Y);
                }
                prev = p;
            }
        }

        /** Spruce or larch: a leader and whorls of drooping (spruce) or open, slightly upturned (larch) branches. */
        void conifer(boolean larch) {
            int top = h - 1;
            double solo = larch ? 0.15 : 0.05, dense = larch ? 0.55 : 0.5;
            trunk(top - 1);
            int[] tip = stem(top);
            leaf(tip[0], top, tip[1]);
            double lowest = top;
            for (int i = 0; i < 8; i++) lowest = Math.min(lowest, base(i * TAU / 8, solo, dense));
            int minBase = (int) Math.max(1, Math.min(top - 1, Math.round(lowest)));
            double reach = maxRoom() * (1 - 0.35 * alpine);
            double fill = (larch ? 0.5 : 0.85) * (1 - 0.3 * alpine);
            // spire: needles around the leader
            for (int dy = Math.max(minBase, top - 2); dy < top; dy++) {
                int[] p = stem(dy);
                for (int d = 0; d < 4; d++) leaf(p[0] + dir4x(d), dy, p[1] + dir4z(d));
            }
            int y = top - 2;
            while (y >= minBase) {
                int[] p = stem(y);
                double cone = reach * Math.pow((top - y) / (double) Math.max(1, top - minBase), 0.85);
                int n = larch ? 4 : 5 + rnd.nextInt(2);
                double phase = rnd.nextDouble() * TAU;
                for (int i = 0; i < n; i++) {
                    double a = phase + i * TAU / n + (rnd.nextDouble() - 0.5) * 0.5;
                    if (y < base(a, solo, dense)) continue;
                    double len = Math.min(cone * vigour(a), room(a) - 1) * (larch ? 0.9 : 1);
                    needleBranch(p[0], y, p[1], a, (int) Math.floor(len + rnd.nextDouble() * 0.5), larch, fill);
                }
                y -= y > top - 8 ? 2 : larch ? 3 : 2 + rnd.nextInt(2);
            }
        }

        static int dir4x(int d) {
            return d == 0 ? 1 : d == 1 ? -1 : 0;
        }

        static int dir4z(int d) {
            return d == 2 ? 1 : d == 3 ? -1 : 0;
        }

        /** One conifer branch of {@code len} blocks from the stem at (sx, y, sz) towards a, with its needles. */
        void needleBranch(int sx, int y, int sz, double a, int len, boolean larch, double fill) {
            if (len < 1) {
                for (int d = 0; d < 4; d++) if (rnd.nextDouble() < fill) leaf(sx + dir4x(d), y, sz + dir4z(d));
                return;
            }
            double c = Math.cos(a), s = Math.sin(a);
            Axis axis = Math.abs(c) >= Math.abs(s) ? Axis.X : Axis.Z;
            int px = sx, py = y, pz = sz;
            for (int i = 1; i <= len; i++) {
                int x = sx + (int) Math.round(i * c), z = sz + (int) Math.round(i * s);
                int yy = y;
                if (len >= 4 && i > 0.6 * len) yy = larch ? (i > 0.75 * len ? y + 1 : y) : y - 1;
                // face to face with the previous log: one step at a time
                if (x != px && z != pz) log(x, py, pz, axis);
                if (yy != py) log(x, py, z, axis);
                boolean last = i == len;
                if (!last) log(x, yy, z, axis);
                if (i >= Math.max(1, 0.3 * len)) {
                    if (larch) {
                        if (rnd.nextDouble() < fill) leaf(x, yy + 1, z);
                        for (int d = 0; d < 4; d++) if (rnd.nextDouble() < fill * 0.6) leaf(x + dir4x(d), yy, z + dir4z(d));
                    } else {
                        leaf(x, yy + 1, z);
                        for (int d = 0; d < 4; d++) {
                            if (rnd.nextDouble() < fill) leaf(x + dir4x(d), yy, z + dir4z(d));
                            if (rnd.nextDouble() < fill * 0.6) leaf(x + dir4x(d), yy + 1, z + dir4z(d));
                        }
                        if (last && len >= 3) leaf(x, yy - 1, z);
                    }
                }
                if (last) {
                    leaf(x, yy, z);
                    int fx = sx + (int) Math.round((len + 1) * c), fz = sz + (int) Math.round((len + 1) * s);
                    if (rnd.nextDouble() < fill) leaf(fx, yy, fz);
                }
                px = x;
                py = yy;
                pz = z;
            }
        }

        /** Broadleaf crown by space colonisation, see {@link Form}. */
        void broadleaf(Form f) {
            int top = h - 1;
            double lowest = top;
            for (int i = 0; i < 8; i++) lowest = Math.min(lowest, base(i * TAU / 8, f.soloBase, f.denseBase));
            int cb = (int) Math.max(1, Math.min(top - 1, Math.round(lowest)));
            int stemTop = Math.min(top - 1, cb + (int) Math.round((top - cb) * f.stem));
            trunk(stemTop);
            double cy = cb + (top - cb) * f.centre;
            double up = Math.max(1, top - cy - f.leafR + 0.5), down = Math.max(0.5, cy - cb);
            double shift = Math.min(1.5, 0.15 * maxRoom() * light);
            double cx = lightX * shift, cz = lightZ * shift;
            double narrow = 1 - 0.35 * alpine;
            double width = Math.max(1, maxRoom() * narrow - 0.4 * f.leafR);
            int n = (int) Math.max(12, Math.min(160, Math.round(f.attractors * Math.PI * width * width * (up + down))));
            double[] ax = new double[n], ay = new double[n], az = new double[n];
            int count = 0;
            for (int tries = 0; tries < n * 6 && count < n; tries++) {
                double ux = rnd.nextDouble() * 2 - 1, uy = rnd.nextDouble() * 2 - 1, uz = rnd.nextDouble() * 2 - 1;
                double d2 = ux * ux + uy * uy + uz * uz;
                // a hollow shell: no attractors deep inside the crown, so light reaches in and limbs run outwards
                if (d2 > 1 || d2 < 0.35 * 0.35) continue;
                double a = Math.atan2(uz, ux);
                double horiz = Math.max(0.5, room(a) * narrow * vigour(a) - 0.4 * f.leafR);
                double px = cx + ux * horiz, pz = cz + uz * horiz, py = cy + uy * (uy > 0 ? up : down);
                if (py < base(a, f.soloBase, f.denseBase) || Math.hypot(px, pz) > room(a) - 0.4 * f.leafR + 0.5) continue;
                ax[count] = px;
                ay[count] = py;
                az[count] = pz;
                count++;
            }
            Colonies c = new Colonies(n + 400);
            // limbs start from the stem inside the crown: oak and willow from a few points (few large limbs), birch all along
            int step = f == Form.BIRCH ? 1 : 2;
            for (int y = stemTop; y >= cb; y -= step) {
                int[] p = stem(y);
                c.add(p[0], y, p[1], -1, y == stemTop || f != Form.FRUIT || y >= stemTop - 2);
            }
            c.grow(ax, ay, az, count, f.influence, f.kill, f.tropism, this);
            for (int i = 0; i < c.size; i++) {
                if (c.parent[i] < 0) continue;
                int[] q = c.voxel(c.parent[i]), p = c.voxel(i);
                limb(q[0], q[1], q[2], p[0], p[1], p[2]);
            }
            double fill = f.fill * (1 - 0.3 * alpine) * (site.metres() > 0 && slope > 0.3 && slopeZ > 0 ? 0.85 : 1);
            for (int i = 0; i < c.size; i++) {
                if (c.parent[i] < 0 || c.children[i] > 0) continue;
                int[] p = c.voxel(i);
                cluster(p[0], p[1], p[2], f.leafR, fill * vigour(Math.atan2(p[2], p[0])));
                int up1 = c.parent[i];
                if (f == Form.OAK && up1 >= 0 && c.parent[up1] >= 0) {
                    int[] q = c.voxel(up1);
                    cluster(q[0], q[1], q[2], f.leafR - 0.6, fill * 0.7);
                }
                if (f == Form.WILLOW) {
                    int drop = 2 + rnd.nextInt(3);
                    for (int d = 1; d <= drop; d++) leaf(p[0], p[1] - d, p[2]);
                    for (int d = 1; d < drop; d++) {
                        int side = rnd.nextInt(4);
                        leaf(p[0] + dir4x(side), p[1] - d, p[2] + dir4z(side));
                    }
                }
            }
            // the top of the stem in its own cluster, so a birch or a crowded oak is never bare on top
            int[] t = stem(stemTop);
            cluster(t[0], stemTop + 1, t[1], Math.min(f.leafR, 1.5), fill);
        }

        /** Logs from voxel q to voxel p, one axis at a time, oriented along the limb. */
        void limb(int qx, int qy, int qz, int px, int py, int pz) {
            int dx = px - qx, dy = py - qy, dz = pz - qz;
            Axis a = Math.abs(dy) >= Math.max(Math.abs(dx), Math.abs(dz)) ? Axis.Y : Math.abs(dx) >= Math.abs(dz) ? Axis.X : Axis.Z;
            int x = qx, y = qy, z = qz;
            while (x != px || y != py || z != pz) {
                if (y != py) y += Integer.signum(py - y);
                else if (x != px) x += Integer.signum(px - x);
                else z += Integer.signum(pz - z);
                if (!isLog(x, y, z)) log(x, y, z, a);
            }
        }

        /** A leaf cluster of radius rad around (x, y, z), slightly flattened, each leaf kept with chance {@code fill}. */
        void cluster(int x, int y, int z, double rad, double fill) {
            int ri = (int) Math.ceil(rad);
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    for (int dx = -ri; dx <= ri; dx++) {
                        double d2 = dx * dx + dz * dz + dy * dy * 1.6;
                        if (d2 > rad * rad) continue;
                        if (d2 > 1.01 && rnd.nextDouble() >= fill) continue;
                        leaf(x + dx, y + dy, z + dz);
                    }
                }
            }
        }

        /** Mountain pine: a few prostrate stems from the foot, spreading downwind (east), with dense needles. */
        void pine() {
            int top = h - 1;
            log(0, 0, 0, Axis.Y);
            int n = 3 + rnd.nextInt(3);
            double phase = rnd.nextDouble() * TAU;
            for (int i = 0; i < n; i++) {
                double a = phase + i * TAU / n + (rnd.nextDouble() - 0.5) * 0.6;
                // flagged: stems bend away from the west wind
                double ang = Math.atan2(Math.sin(a), Math.cos(a));
                a = ang * (1 - 0.4 * wind);
                double len = Math.min(room(a) - 1, (1.5 + rnd.nextDouble() * 2) * vigour(a));
                int steps = (int) Math.max(1, Math.round(len));
                double c = Math.cos(a), s = Math.sin(a);
                int px = 0, py = 0, pz = 0;
                for (int j = 1; j <= steps; j++) {
                    int x = (int) Math.round(j * c), z = (int) Math.round(j * s);
                    int y = (int) Math.round(top * 0.7 * j / steps);
                    limb(px, py, pz, x, y, z);
                    if (j * 2 >= steps) cluster(x, Math.min(top, y + 1), z, 1.5, 0.9);
                    px = x;
                    py = y;
                    pz = z;
                }
            }
            cluster(0, Math.min(top, 1), 0, 1.5, 0.9);
        }

        /** A low cluster of persistent leaves, no log. */
        void bush() {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) < 2 || rnd.nextBoolean()) leaf(dx, 0, dz);
                }
            }
            leaf(0, 1, 0);
        }

        /**
         * Logs first, then leaves with their vanilla distance, found by a breadth-first walk from the logs through the
         * leaves. Before that every block outside the room of its direction, or in a cell the site marks as solid, is
         * dropped; leaves further than {@value TreePlan#LEAF_REACH} from a log are left out.
         */
        List<Part> parts() {
            for (int dy = 0; dy < h; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int k = k(dx, dy, dz);
                        if (cell[k] == EMPTY) continue;
                        boolean trunk = dx == 0 && dz == 0;
                        if (dy < site.floor(dx, dz) || !trunk && Math.hypot(dx, dz) > room(Math.atan2(dz, dx)) + 0.5) cell[k] = EMPTY;
                    }
                }
            }
            byte[] dist = new byte[cell.length];
            int[] queue = new int[cell.length];
            int head = 0, tail = 0;
            // logs cut off from the foot by the clipping go too: walk the logs from the ground up
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int k = k(dx, 0, dz);
                    if (cell[k] < LOG_X) continue;
                    dist[k] = 1;
                    queue[tail++] = k;
                }
            }
            while (head < tail) {
                int k = queue[head++];
                int dx = k % side - r, dz = k / side % side - r, dy = k / (side * side);
                for (int i = 0; i < N6.length; i += 3) {
                    int x = dx + N6[i], y = dy + N6[i + 1], z = dz + N6[i + 2];
                    if (!inside(x, y, z)) continue;
                    int q = k(x, y, z);
                    if (cell[q] < LOG_X || dist[q] != 0) continue;
                    dist[q] = 1;
                    queue[tail++] = q;
                }
            }
            for (int k = 0; k < cell.length; k++) {
                if (cell[k] >= LOG_X && dist[k] == 0) cell[k] = EMPTY;
                dist[k] = 0;
            }
            head = tail = 0;
            List<Part> out = new ArrayList<>();
            for (int dy = 0; dy < h; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int k = k(dx, dy, dz);
                        if (cell[k] < LOG_X) continue;
                        out.add(new Part(dx, dy, dz, true, 0, Axis.values()[cell[k] - LOG_X]));
                        queue[tail++] = k;
                    }
                }
            }
            boolean anyLog = tail > 0;
            while (head < tail) {
                int k = queue[head++], d = dist[k];
                if (d == TreePlan.LEAF_REACH) continue;
                int dx = k % side - r, dz = k / side % side - r, dy = k / (side * side);
                for (int i = 0; i < N6.length; i += 3) {
                    int x = dx + N6[i], y = dy + N6[i + 1], z = dz + N6[i + 2];
                    if (!inside(x, y, z)) continue;
                    int q = k(x, y, z);
                    if (cell[q] != LEAF || dist[q] != 0) continue;
                    dist[q] = (byte) (d + 1);
                    queue[tail++] = q;
                }
            }
            for (int dy = 0; dy < h; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int k = k(dx, dy, dz);
                        if (cell[k] != LEAF) continue;
                        if (!anyLog) out.add(new Part(dx, dy, dz, false, 0, null));
                        else if (dist[k] != 0) out.add(new Part(dx, dy, dz, false, dist[k], null));
                    }
                }
            }
            return out;
        }
    }

    /** Space colonisation nodes (block coordinates relative to the trunk foot) with their parent and child count. */
    private static final class Colonies {
        final double[] x, y, z;
        final int[] parent, children;
        final boolean[] active;
        int size;

        Colonies(int capacity) {
            x = new double[capacity];
            y = new double[capacity];
            z = new double[capacity];
            parent = new int[capacity];
            children = new int[capacity];
            active = new boolean[capacity];
        }

        void add(double px, double py, double pz, int from, boolean grows) {
            if (size == x.length) return;
            x[size] = px;
            y[size] = py;
            z[size] = pz;
            parent[size] = from;
            active[size] = grows;
            if (from >= 0) children[from]++;
            size++;
        }

        int[] voxel(int i) {
            return new int[] {(int) Math.round(x[i]), (int) Math.round(y[i]), (int) Math.round(z[i])};
        }

        /**
         * Grows nodes towards the attractors: each attractor pulls its nearest node within {@code influence}; a node
         * steps one block along the mean pull (plus {@code tropism} upwards); attractors within {@code kill} of a node
         * are used up. Nearest nodes are kept per attractor and updated only against new nodes.
         */
        void grow(double[] ax, double[] ay, double[] az, int n, double influence, double kill, double tropism, Box box) {
            int[] near = new int[n];
            double[] nearD = new double[n];
            boolean[] alive = new boolean[n];
            for (int a = 0; a < n; a++) {
                alive[a] = true;
                nearD[a] = Double.MAX_VALUE;
                near[a] = -1;
                closer(a, 0, ax, ay, az, near, nearD, alive, kill);
            }
            double[] sx = new double[x.length], sy = new double[x.length], sz = new double[x.length];
            int[] pulls = new int[x.length];
            double inf2 = influence * influence;
            for (int iter = 0; iter < 48; iter++) {
                int from = size;
                java.util.Arrays.fill(pulls, 0, size, 0);
                for (int a = 0; a < n; a++) {
                    if (!alive[a] || near[a] < 0 || nearD[a] > inf2) continue;
                    int i = near[a];
                    double d = Math.sqrt(nearD[a]);
                    if (pulls[i]++ == 0) sx[i] = sy[i] = sz[i] = 0;
                    sx[i] += (ax[a] - x[i]) / d;
                    sy[i] += (ay[a] - y[i]) / d;
                    sz[i] += (az[a] - z[i]) / d;
                }
                for (int i = 0; i < from; i++) {
                    if (pulls[i] == 0) continue;
                    double dx = sx[i] / pulls[i], dy = sy[i] / pulls[i] + tropism, dz = sz[i] / pulls[i];
                    double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (l < 1e-3) continue;
                    double px = x[i] + dx / l, py = y[i] + dy / l, pz = z[i] + dz / l;
                    int vx = (int) Math.round(px), vy = (int) Math.round(py), vz = (int) Math.round(pz);
                    if (!box.inside(vx, vy, vz) || occupied(vx, vy, vz, from)) continue;
                    add(px, py, pz, i, true);
                }
                if (size == from) break;
                for (int a = 0; a < n; a++) if (alive[a]) closer(a, from, ax, ay, az, near, nearD, alive, kill);
            }
        }

        private boolean occupied(int vx, int vy, int vz, int upTo) {
            for (int j = size - 1; j >= 0; j--) {
                if ((int) Math.round(x[j]) == vx && (int) Math.round(y[j]) == vy && (int) Math.round(z[j]) == vz) return true;
            }
            return false;
        }

        private void closer(int a, int from, double[] ax, double[] ay, double[] az, int[] near, double[] nearD, boolean[] alive, double kill) {
            for (int i = from; i < size; i++) {
                if (!active[i]) continue;
                double dx = ax[a] - x[i], dy = ay[a] - y[i], dz = az[a] - z[i];
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < kill * kill) {
                    alive[a] = false;
                    return;
                }
                if (d2 < nearD[a]) {
                    nearD[a] = d2;
                    near[a] = i;
                }
            }
        }
    }
}
