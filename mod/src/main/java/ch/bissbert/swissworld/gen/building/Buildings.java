package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.SwdCell;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds the buildings whose bbox (grown by a margin for door steps) reaches a chunk. */
public final class Buildings {
    public static final int MARGIN = 8;
    private static final int CACHED = 256;
    /** Plans by gid and the cells present around it; a building spans many chunks and every stage asks again. */
    private static final Map<Key, BuildingPlan> PLANS = new LinkedHashMap<>(CACHED, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, BuildingPlan> eldest) {
            return size() > CACHED;
        }
    };

    private Buildings() {}

    /** {@code cells}: bit i set if the cell under corner i of the margin-grown bbox exists. */
    record Key(long gid, int cells) {}

    public static List<BuildingPlan> near(CellView view, int x0, int z0) {
        return near(view, x0, z0, MARGIN);
    }

    /** The buildings whose bbox lies within {@code margin} of the 16 x 16 chunk at (x0, z0). */
    public static List<BuildingPlan> near(CellView view, int x0, int z0, int margin) {
        int ax = x0 - margin, az = z0 - margin, bx = x0 + 15 + margin, bz = z0 + 15 + margin;
        Set<SwdCell> cells = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int x : new int[]{ax, bx}) {
            for (int z : new int[]{az, bz}) {
                SwdCell c = view.cell(x, z);
                if (c != null && c.buildings() != null) cells.add(c);
            }
        }
        List<BuildingPlan> plans = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (SwdCell cell : cells) {
            for (SwdCell.Building b : cell.buildings()) {
                if (view.blockX(cell, b.maxX()) < ax || view.blockX(cell, b.minX()) > bx
                        || view.blockZ(cell, b.maxZ()) < az || view.blockZ(cell, b.minZ()) > bz) continue;
                if (seen.add(b.gid())) plans.add(plan(view, cell, b));
            }
        }
        return plans;
    }

    /** Cached plan; two threads may both compute it, which is harmless as planning is deterministic. */
    public static BuildingPlan plan(CellView view, SwdCell cell, SwdCell.Building b) {
        // a neighbour cell built later must not be shadowed by a plan made without it
        int ax = view.blockX(cell, b.minX()) - MARGIN, bx = view.blockX(cell, b.maxX()) + MARGIN;
        int az = view.blockZ(cell, b.minZ()) - MARGIN, bz = view.blockZ(cell, b.maxZ()) + MARGIN;
        int mask = (view.cell(ax, az) != null ? 1 : 0) | (view.cell(bx, az) != null ? 2 : 0)
                | (view.cell(ax, bz) != null ? 4 : 0) | (view.cell(bx, bz) != null ? 8 : 0);
        Key k = new Key(b.gid(), mask);
        synchronized (PLANS) {
            BuildingPlan p = PLANS.get(k);
            if (p != null) return p;
        }
        BuildingPlan p = BuildingPlan.of(view, cell, b);
        synchronized (PLANS) {
            PLANS.put(k, p);
        }
        return p;
    }
}
