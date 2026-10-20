package ch.bissbert.swissworld.gen.ground;

/**
 * The packed 16-bit zone id of the taxonomy: {@code region(3) | belt(3) | zone(6) | lithology(3) | spare(1)}, high bits
 * first: region in bits 13-15, belt 10-12, zone 4-9, lithology 1-3, bit 0 spare.
 */
public final class ZoneId {
    private ZoneId() {}

    public static short pack(Region r, Belt b, Zone z, Lith l) {
        return (short) ((r.ordinal() & 7) << 13 | (b.ordinal() & 7) << 10 | (z.id() & 63) << 4 | (l.ordinal() & 7) << 1);
    }

    public static Region region(short id) {
        return Region.byId((id & 0xFFFF) >>> 13);
    }

    public static Belt belt(short id) {
        return Belt.byId((id >>> 10) & 7);
    }

    public static Zone zone(short id) {
        return Zone.byId((id >>> 4) & 63);
    }

    public static Lith lith(short id) {
        return Lith.byId((id >>> 1) & 7);
    }

    public static String toString(short id) {
        return region(id) + "/" + belt(id) + "/" + zone(id) + "/" + lith(id);
    }
}
