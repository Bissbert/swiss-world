package ch.bissbert.swissworld.mixin;

import ch.bissbert.swissworld.world.SwissLight;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A new sky light entry for a fully opaque section starts dark instead of repeating the layer above; see
 * {@link SwissLight#dark}. Saved light still wins. Owner: mod-core.
 */
@Mixin(SkyLightSectionStorage.class)
abstract class SkyLightSectionStorageMixin {
    @Inject(method = "createDataLayer", at = @At("HEAD"), cancellable = true)
    private void swissworld$dark(long sectionPos, CallbackInfoReturnable<DataLayer> cir) {
        LayerLightSectionStorageAccessor self = (LayerLightSectionStorageAccessor) this;
        if (!self.swissworld$queued().containsKey(sectionPos) && SwissLight.dark(self.swissworld$chunkSource(), sectionPos)) {
            cir.setReturnValue(new DataLayer());
        }
    }

}
