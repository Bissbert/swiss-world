package ch.bissbert.swissworld.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Saved light waiting to be applied and the chunks, for {@link SkyLightSectionStorageMixin}. Owner: mod-core. */
@Mixin(LayerLightSectionStorage.class)
public interface LayerLightSectionStorageAccessor {
    @Accessor("queuedSections")
    Long2ObjectMap<DataLayer> swissworld$queued();

    @Accessor("chunkSource")
    LightChunkGetter swissworld$chunkSource();
}
