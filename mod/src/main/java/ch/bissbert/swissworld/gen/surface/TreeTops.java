package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.SwdCell;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The {@code TTOP} tree table of one cell (swd-format §6a), as {@link SwdCell#trees} reads it: tree tops sorted by
 * (dz, dx), with a lookup by area. Pure: no Minecraft classes. Wrapping does not copy, so it is cheap per chunk.
 */
public final class TreeTops {
    private static final Comparator<SwdCell.Tree> ORDER = Comparator.comparingInt(SwdCell.Tree::dz).thenComparingInt(SwdCell.Tree::dx);

    private final List<SwdCell.Tree> tops;

    /** {@code tops} must be sorted by (dz, dx), as {@link SwdCell#trees} is. */
    public TreeTops(List<SwdCell.Tree> tops) {
        this.tops = tops;
    }

    /** Any list of trees, sorted first. */
    public static TreeTops sorted(List<SwdCell.Tree> tops) {
        return new TreeTops(tops.stream().sorted(ORDER).toList());
    }

    public int size() {
        return tops.size();
    }

    /** Trees with dx0 <= dx <= dx1 and dz0 <= dz <= dz1 (clamped to the cell), in (dz, dx) order. */
    public List<SwdCell.Tree> within(int dx0, int dz0, int dx1, int dz1) {
        List<SwdCell.Tree> out = new ArrayList<>();
        for (int dz = Math.max(0, dz0); dz <= Math.min(SwdCell.SIZE - 1, dz1); dz++) {
            for (int i = first(dz * SwdCell.SIZE + Math.max(0, dx0)); i < tops.size(); i++) {
                SwdCell.Tree t = tops.get(i);
                if (t.dz() != dz || t.dx() > dx1) break;
                out.add(t);
            }
        }
        return out;
    }

    /** First index whose key {@code dz·1000 + dx} is at least {@code key}. */
    private int first(int key) {
        int lo = 0, hi = tops.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            SwdCell.Tree t = tops.get(mid);
            if (t.dz() * SwdCell.SIZE + t.dx() < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
