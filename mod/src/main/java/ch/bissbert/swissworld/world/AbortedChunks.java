package ch.bissbert.swissworld.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager;

/**
 * Chunks whose cells were still pending at shutdown. Owner: mod-core.
 *
 * <p>Vanilla waits for every generating chunk before the final save, and a failed generation step is a fatal crash,
 * so on server stop the pending noise steps complete with an <em>empty</em> chunk (see
 * {@code SwissRuntime.stopping}). That chunk must never be saved as generated: on save its tag is rewritten back to
 * status {@code minecraft:biomes} with no blocks, light or heightmaps, so the noise step runs again on the next start.
 * Later steps may already have run on the empty chunk during shutdown, so this keys on the position, not the status.
 */
public final class AbortedChunks {
    public static final String RESTORE_STATUS = "minecraft:biomes";

    private AbortedChunks() {}

    public static void downgrade(CompoundTag chunk) {
        chunk.putString("Status", RESTORE_STATUS);
        for (String k : new String[] {"Heightmaps", "isLightOn", "CarvingMasks", "PostProcessing", "blending_data",
                "below_zero_retrogen", LevelChunkAuxiliaryLightManager.LIGHT_NBT_KEY}) {
            chunk.remove(k);
        }
        chunk.put("block_entities", new ListTag());
        ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag s = sections.getCompound(i);
            s.remove("block_states");
            s.remove("BlockLight");
            s.remove("SkyLight");
        }
    }
}
