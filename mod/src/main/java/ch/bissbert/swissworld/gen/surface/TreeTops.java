package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.cell.SwdCell;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code TTOP} tree table of one cell (swd-format §6a): tree tops sorted by (dz, dx), with a lookup by area.
 * Also parses the {@code CNPY} canopy layer (§3). Pure: no Minecraft classes.
 */
public final class TreeTops {
    public static final int UNKNOWN = 0, CONIFER = 1, BROADLEAF = 2, LARCH = 3;
    private static final int ENTRY = 8;

    /** One tree: top column (dx, dz) in the cell, height in metres above TERR, crown radius in dm (0 = unknown). */
    public record Top(int dx, int dz, int heightM, int crownDm, int kind) {}

    private final Top[] tops;
    /** First index with {@code dz >= row}, for rows 0..SIZE. */
    private final int[] rowStart = new int[SwdCell.SIZE + 1];

    public TreeTops(List<Top> tops) {
        this.tops = tops.toArray(Top[]::new);
        Arrays.sort(this.tops, (a, b) -> a.dz() != b.dz() ? Integer.compare(a.dz(), b.dz()) : Integer.compare(a.dx(), b.dx()));
        int i = 0;
        for (int row = 0; row <= SwdCell.SIZE; row++) {
            while (i < this.tops.length && this.tops[i].dz() < row) i++;
            rowStart[row] = i;
        }
    }

    /** Parses a {@code TTOP} blob: {@code u32 count}, then {@code count} 8-byte entries, little-endian. */
    public static TreeTops parse(byte[] blob) {
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(b.getInt());
        if (blob.length != 4 + count * ENTRY) throw new IllegalArgumentException("TTOP: " + count + " trees in " + blob.length + " bytes");
        List<Top> out = new ArrayList<>((int) count);
        for (long k = 0; k < count; k++) {
            int dx = Short.toUnsignedInt(b.getShort()), dz = Short.toUnsignedInt(b.getShort());
            int h = Byte.toUnsignedInt(b.get()), crown = Byte.toUnsignedInt(b.get()), kind = Byte.toUnsignedInt(b.get());
            b.get(); // reserved
            if (dx >= SwdCell.SIZE || dz >= SwdCell.SIZE) throw new IllegalArgumentException("TTOP: tree at " + dx + "," + dz);
            out.add(new Top(dx, dz, h, crown, kind));
        }
        return new TreeTops(out);
    }

    /** Checks a {@code CNPY} blob: canopy height in whole metres above TERR, {@code u8[1000·1000]}, index dz·1000 + dx. */
    public static byte[] parseCanopy(byte[] blob) {
        if (blob.length != SwdCell.SIZE * SwdCell.SIZE) throw new IllegalArgumentException("CNPY: " + blob.length + " bytes");
        return blob;
    }

    public int size() {
        return tops.length;
    }

    /** Trees with dx0 <= dx <= dx1 and dz0 <= dz <= dz1 (clamped to the cell), in (dz, dx) order. */
    public List<Top> within(int dx0, int dz0, int dx1, int dz1) {
        List<Top> out = new ArrayList<>();
        int from = rowStart[Math.max(0, Math.min(SwdCell.SIZE, dz0))], to = rowStart[Math.max(0, Math.min(SwdCell.SIZE, dz1 + 1))];
        for (int i = from; i < to; i++) {
            if (tops[i].dx() >= dx0 && tops[i].dx() <= dx1) out.add(tops[i]);
        }
        return out;
    }
}
