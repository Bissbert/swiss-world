package ch.bissbert.swissworld.cell;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Reader for the .swd v1 format. The Python twin is sidecar/swissdata/swd.py. */
public final class SwdReader {
    public static final int FORMAT_VERSION = 1;
    private static final int HEADER = 24;
    private static final int DIR_ENTRY = 32;

    private SwdReader() {}

    public static SwdCell read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    public static SwdCell decode(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < HEADER || !"SWD1".equals(new String(data, 0, 4, StandardCharsets.US_ASCII))) {
            throw new IOException("bad magic");
        }
        int version = b.getShort(4) & 0xFFFF;
        if (version != FORMAT_VERSION) throw new IOException("unsupported formatVersion " + version);
        int eKm = b.getInt(8), nKm = b.getInt(12), count = b.getInt(16);

        short[] terr = null, wlvl = null, oelv = null;
        byte[] surf = null, wknd = null, ocls = null, oflg = null, insd = null, cnpy = null;
        List<SwdCell.Tree> trees = null;
        List<SwdCell.Building> buildings = List.of();
        Map<Integer, SwdCell.Span[]> spans = Map.of();

        for (int i = 0; i < count; i++) {
            int d = HEADER + i * DIR_ENTRY;
            String tag = new String(data, d, 4, StandardCharsets.US_ASCII);
            int codec = b.getInt(d + 4);
            int off = Math.toIntExact(b.getLong(d + 8));
            int stored = Math.toIntExact(b.getLong(d + 16));
            int raw = Math.toIntExact(b.getLong(d + 24));
            byte[] blob = switch (codec) {
                case 0 -> java.util.Arrays.copyOfRange(data, off, off + stored);
                case 1 -> inflate(data, off, stored, raw);
                default -> throw new IOException("unknown codec " + codec);
            };
            if (blob.length != raw) throw new IOException(tag + ": length mismatch");
            switch (tag) {
                case "TERR" -> terr = u16(blob, SwdCell.TERR_N * SwdCell.TERR_N, tag);
                case "WLVL" -> wlvl = u16(blob, SwdCell.SIZE * SwdCell.SIZE, tag);
                case "OELV" -> oelv = u16(blob, SwdCell.SIZE * SwdCell.SIZE, tag);
                case "SURF" -> surf = u8(blob, tag);
                case "WKND" -> wknd = u8(blob, tag);
                case "OCLS" -> ocls = u8(blob, tag);
                case "OFLG" -> oflg = u8(blob, tag);
                case "INSD" -> insd = u8(blob, tag);
                case "CNPY" -> cnpy = u8(blob, tag);
                case "TTOP" -> trees = ttop(blob);
                case "BTBL" -> buildings = btbl(blob);
                case "BSPN" -> spans = bspn(blob);
                default -> { } // unknown tags are ignored by contract
            }
        }
        if (terr == null || surf == null) throw new IOException("TERR and SURF are required");
        return new SwdCell(eKm, nKm, terr, surf, wlvl, wknd, ocls, oelv, oflg, insd, buildings, spans, cnpy, trees);
    }

    private static byte[] inflate(byte[] data, int off, int len, int raw) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data, off, len);
            byte[] out = new byte[raw];
            int n = 0;
            while (n < raw && !inf.finished()) {
                int r = inf.inflate(out, n, raw - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += r;
            }
            if (n != raw) throw new IOException("short inflate");
            return out;
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
    }

    private static short[] u16(byte[] blob, int n, String tag) throws IOException {
        if (blob.length != n * 2) throw new IOException(tag + ": bad size");
        short[] a = new short[n];
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(a);
        return a;
    }

    private static byte[] u8(byte[] blob, String tag) throws IOException {
        if (blob.length != SwdCell.SIZE * SwdCell.SIZE) throw new IOException(tag + ": bad size");
        return blob;
    }

    private static List<SwdCell.Building> btbl(byte[] blob) {
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt();
        List<SwdCell.Building> res = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long gid = b.getLong(), egid = b.getLong();
            int objektart = b.getShort() & 0xFFFF, cat = b.getShort() & 0xFFFF, cls = b.getShort() & 0xFFFF, per = b.getShort() & 0xFFFF;
            int floors = b.get() & 0xFF, side = b.get() & 0xFF;
            int base = b.getShort() & 0xFFFF, top = b.getShort() & 0xFFFF;
            b.getShort(); // reserved
            res.add(new SwdCell.Building(gid, egid, objektart, cat, cls, per, floors, side, base, top,
                    b.getInt(), b.getInt(), b.getInt(), b.getInt()));
        }
        return res;
    }

    private static List<SwdCell.Tree> ttop(byte[] blob) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt();
        if (blob.length != 4 + 8L * n) throw new IOException("TTOP: bad size");
        List<SwdCell.Tree> res = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int dx = b.getShort() & 0xFFFF, dz = b.getShort() & 0xFFFF;
            res.add(new SwdCell.Tree(dx, dz, b.get() & 0xFF, b.get() & 0xFF, b.get() & 0xFF));
            b.get(); // reserved
        }
        return List.copyOf(res);
    }

    private static Map<Integer, SwdCell.Span[]> bspn(byte[] blob) {
        ByteBuffer b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt();
        Map<Integer, SwdCell.Span[]> res = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            int dx = b.getShort() & 0xFFFF, dz = b.getShort() & 0xFFFF, cnt = b.get() & 0xFF;
            SwdCell.Span[] col = new SwdCell.Span[cnt];
            for (int s = 0; s < cnt; s++) {
                col[s] = new SwdCell.Span(b.getShort() & 0xFFFF, b.getShort() & 0xFFFF, b.getShort() & 0xFFFF,
                        b.get() & 0xFF, b.get() & 0xFF);
            }
            res.put(SwdCell.key(dx, dz), col);
        }
        return res;
    }
}
