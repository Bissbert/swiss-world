package ch.bissbert.swissworld.cell;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** coverage.bin: per-km cell state, 0 outside (ocean), 1 border, 2 inside. */
public record Coverage(int eMinKm, int nMinKm, int width, int height, byte[] grid) {
    public static final int OUTSIDE = 0, BORDER = 1, INSIDE = 2;

    public static Coverage read(Path file) throws IOException {
        byte[] d = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (!"SWCV".equals(new String(d, 0, 4, StandardCharsets.US_ASCII)) || (b.getShort(4) & 0xFFFF) != 1) {
            throw new IOException("bad coverage file");
        }
        int e = b.getInt(8), n = b.getInt(12), w = b.getInt(16), h = b.getInt(20);
        byte[] g = java.util.Arrays.copyOfRange(d, 24, 24 + w * h);
        return new Coverage(e, n, w, h, g);
    }

    public int state(int eKm, int nKm) {
        int x = eKm - eMinKm, y = nKm - nMinKm;
        if (x < 0 || y < 0 || x >= width || y >= height) return OUTSIDE;
        return grid[y * width + x];
    }
}
