package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.SwdCell;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Finds the buildings whose bbox (grown by a margin for door steps) reaches a chunk. */
public final class Buildings {
    public static final int MARGIN = 8;

    private Buildings() {}

    public static List<BuildingPlan> near(CellView view, int x0, int z0) {
        int ax = x0 - MARGIN, az = z0 - MARGIN, bx = x0 + 15 + MARGIN, bz = z0 + 15 + MARGIN;
        Set<SwdCell> cells = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int x : new int[]{x0, x0 + 15}) {
            for (int z : new int[]{z0, z0 + 15}) {
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
                if (seen.add(b.gid())) plans.add(BuildingPlan.of(view, cell, b));
            }
        }
        return plans;
    }
}
