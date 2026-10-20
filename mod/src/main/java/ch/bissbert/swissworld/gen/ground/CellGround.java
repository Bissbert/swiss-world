package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;

/**
 * {@link Ground} straight from the .swd cells (the chunk's {@link ch.bissbert.swissworld.gen.GenContext#cellAt}).
 * Remembers the last cell, since lookups come in runs of neighbouring columns. Optional layers are unknown until the
 * cells carry REGN/LITH/TOPO (sidecar-zones).
 */
public final class CellGround implements Ground {
    private final SwissCoords coords;
    private final ColumnGrid.Cells cells;
    private SwdCell last;
    private long lastE0 = Long.MIN_VALUE, lastN0 = Long.MIN_VALUE;

    public CellGround(SwissCoords coords, ColumnGrid.Cells cells) {
        this.coords = coords;
        this.cells = cells;
    }

    private SwdCell cell(int x, int z) {
        long e = coords.east(x), n = coords.north(z);
        long e0 = Math.floorDiv(e, 1000), n0 = Math.floorDiv(n, 1000);
        if (e0 != lastE0 || n0 != lastN0) {
            last = cells.at(x, z);
            lastE0 = e0;
            lastN0 = n0;
        }
        return last;
    }

    private int dx(int x) {
        return SwissCoords.dx(coords.east(x));
    }

    private int dz(int z) {
        return SwissCoords.dz(coords.north(z));
    }

    @Override
    public boolean present(int x, int z) {
        SwdCell c = cell(x, z);
        return c != null && c.inside(dx(x), dz(z));
    }

    @Override
    public double metres(int x, int z) {
        SwdCell c = cell(x, z);
        if (c == null) return Double.NaN;
        return c.terrain(dx(x) + 0.5, dz(z) + 0.5);
    }

    @Override
    public int surf(int x, int z) {
        SwdCell c = cell(x, z);
        return c == null ? 0 : c.surf(dx(x), dz(z));
    }

    @Override
    public boolean wet(int x, int z) {
        return !Double.isNaN(waterMetres(x, z));
    }

    @Override
    public int waterKind(int x, int z) {
        SwdCell c = cell(x, z);
        return c == null ? 0 : c.waterKind(dx(x), dz(z));
    }

    @Override
    public double waterMetres(int x, int z) {
        SwdCell c = cell(x, z);
        if (c == null) return Double.NaN;
        int dx = dx(x), dz = dz(z);
        double w = c.water(dx, dz);
        if (Double.isNaN(w)) return w;
        // as ColumnGrid: water only counts where it stands above the ground block
        return ColumnGrid.y(w) > ColumnGrid.y(c.terrain(dx + 0.5, dz + 0.5)) ? w : Double.NaN;
    }

    @Override
    public int overlay(int x, int z) {
        SwdCell c = cell(x, z);
        return c == null ? 0 : c.overlay(dx(x), dz(z));
    }

    @Override
    public double overlayMetres(int x, int z) {
        SwdCell c = cell(x, z);
        return c == null ? Double.NaN : c.overlayElevation(dx(x), dz(z));
    }

    @Override
    public int flags(int x, int z) {
        SwdCell c = cell(x, z);
        return c == null ? 0 : c.overlayFlags(dx(x), dz(z));
    }

    @Override
    public boolean building(int x, int z) {
        SwdCell c = cell(x, z);
        return c != null && c.spansAt(dx(x), dz(z)) != null;
    }

    @Override
    public int canopy(int x, int z) {
        SwdCell c = cell(x, z);
        return c == null || !c.hasCanopy() ? -1 : c.canopy(dx(x), dz(z));
    }

    @Override
    public long east(int x) {
        return coords.east(x);
    }

    @Override
    public long north(int z) {
        return coords.north(z);
    }
}
