package ch.bissbert.swissworld.world;

import ch.bissbert.swissworld.cell.SwissCoords;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The dimension's real-world origin, stored in {@code dimensions/swissworld/switzerland/data/swissworld_origin.dat}.
 * Owner: mod-core. It locks when the first chunk starts generating; after that only a reset can change it.
 */
public final class SwissOrigin extends SavedData {
    public static final String NAME = "swissworld_origin";

    private long e0, n0;
    private boolean locked;

    public SwissOrigin(SwissCoords initial) {
        this.e0 = initial.e0();
        this.n0 = initial.n0();
        setDirty();
    }

    private SwissOrigin(long e0, long n0, boolean locked) {
        this.e0 = e0;
        this.n0 = n0;
        this.locked = locked;
    }

    public static SavedData.Factory<SwissOrigin> factory(SwissCoords initial) {
        return new SavedData.Factory<>(() -> new SwissOrigin(initial),
                (tag, registries) -> new SwissOrigin(tag.getLong("e0"), tag.getLong("n0"), tag.getBoolean("locked")));
    }

    public synchronized SwissCoords coords() {
        return new SwissCoords(e0, n0);
    }

    public synchronized boolean locked() {
        return locked;
    }

    /** Called from worldgen threads when a chunk starts generating. */
    public synchronized void lock() {
        if (!locked) {
            locked = true;
            setDirty();
        }
    }

    /** @return false if locked */
    public synchronized boolean set(long e, long n) {
        if (locked) return false;
        e0 = e;
        n0 = n;
        setDirty();
        return true;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("e0", e0);
        tag.putLong("n0", n0);
        tag.putBoolean("locked", locked);
        return tag;
    }
}
