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
    /** Plans by gid; a building spans many chunks and every stage asks again. */
    private static final Map<Long, BuildingPlan> PLANS = new LinkedHashMap<>(CACHED, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, BuildingPlan> eldest) {
            return size() > CACHED;
        }
    };

    private Buildings() {}

    public static List<BuildingPlan> near(CellView view, int x0, int z0) {
        int ax = x0 - MARGIN, az = z0 - MARGIN, bx = x0 + 15 + MARGIN, bz = z0 + 15 + MARGIN;
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
    static BuildingPlan plan(CellView view, SwdCell cell, SwdCell.Building b) {
        synchronized (PLANS) {
            BuildingPlan p = PLANS.get(b.gid());
            if (p != null) return p;
        }
        BuildingPlan p = BuildingPlan.of(view, cell, b);
        synchronized (PLANS) {
            PLANS.put(b.gid(), p);
        }
        return p;
    }
}
