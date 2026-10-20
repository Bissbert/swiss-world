package ch.bissbert.swissworld.gen.ground;

import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwdReader;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.gen.stage.TerrainStage;
import ch.bissbert.swissworld.gen.surface.ColumnGrid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real cells (a private copy of the store, {@code SWISSWORLD_STORE}) and a light block world for the ground stages:
 * per column the blocks TerrainStage and WaterStage would leave near the top, stone below, air above.
 */
final class TestWorld implements BlockAccess {
    static final SwissCoords ORIGIN = new SwissCoords(2_632_900, 1_170_600);
    static final int LOW = 24, HIGH = 48;

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The store's cells directory, or null when no store is configured (the real-cell tests then skip). */
    static Path store() {
        String s = System.getProperty("swissworld.testStore", "");
        if (s.isEmpty()) return null;
        Path p = Path.of(s, "cells");
        return Files.isDirectory(p) ? p : null;
    }

    static ColumnGrid.Cells cells(Path dir) {
        Map<Long, SwdCell> cache = new HashMap<>();
        return (x, z) -> {
            int e = SwissCoords.cellKm(ORIGIN.east(x)), n = SwissCoords.cellKm(ORIGIN.north(z));
            long key = (long) e << 32 | n;
            return cache.computeIfAbsent(key, k -> {
                Path f = dir.resolve(e + "_" + n + ".swd");
                if (!Files.exists(f)) return null;
                try {
                    return SwdReader.read(f);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        };
    }

    private final ColumnGrid.Cells cells;
    private final Map<Long, Col> cols = new HashMap<>();
    private long lastKey = Long.MIN_VALUE;
    private Col last;

    private record Col(int top, BlockState[] s) {}

    TestWorld(ColumnGrid.Cells cells) {
        this.cells = cells;
    }

    private long key(int x, int z) {
        return (long) x << 32 | (z & 0xffffffffL);
    }

    private Col column(int x, int z) {
        long k = key(x, z);
        if (k == lastKey) return last;
        Col got = cols.get(k);
        if (got == null) got = make(x, z, k);
        lastKey = k;
        return last = got;
    }

    private Col make(int x, int z, long k) {
        BlockState[] c;
        SwdCell cell = cells.at(x, z);
        int dx = SwissCoords.dx(ORIGIN.east(x)), dz = SwissCoords.dz(ORIGIN.north(z));
        TerrainStage.Column col = TerrainStage.column(cell, dx, dz);
        int top = col.top();
        c = new BlockState[LOW + HIGH];
        for (int i = 0; i < c.length; i++) {
            BlockState s = col.at(top - LOW + i);
            c[i] = s == null ? Blocks.AIR.defaultBlockState() : s;
        }
        if (cell != null && cell.inside(dx, dz) && !Double.isNaN(cell.water(dx, dz))) {
            int wy = (int) Math.floor(SwissCoords.toY(cell.water(dx, dz)));
            for (int y = top + 1; y <= wy && y < top + HIGH - LOW; y++) c[y - top + LOW] = Blocks.WATER.defaultBlockState();
        }
        Col col2 = new Col(top, c);
        cols.put(k, col2);
        return col2;
    }

    int baseTop(int x, int z) {
        return column(x, z).top;
    }

    @Override
    public BlockState get(int x, int y, int z) {
        Col col = column(x, z);
        BlockState[] c = col.s;
        int i = y - col.top + LOW;
        if (i < 0) return Blocks.STONE.defaultBlockState();
        if (i >= c.length) return Blocks.AIR.defaultBlockState();
        return c[i];
    }

    @Override
    public void set(int x, int y, int z, BlockState state) {
        Col col = column(x, z);
        int i = y - col.top + LOW;
        if (i >= 0 && i < col.s.length) col.s[i] = state;
    }

    /** Highest non-air y of the column. */
    int top(int x, int z) {
        Col col = column(x, z);
        for (int i = col.s.length - 1; i >= 0; i--) if (!col.s[i].isAir()) return i + col.top - LOW;
        return col.top - LOW;
    }

    /** Runs the ground stages on the chunk with north-west corner (x0, z0); returns the zones. */
    ZoneMap chunk(int x0, int z0) {
        ZoneMap zm = ZoneMap.of(ORIGIN, cells, x0, z0);
        GroundTextureStage.apply(zm, this);
        SnowStage.apply(zm, this);
        return zm;
    }
}
