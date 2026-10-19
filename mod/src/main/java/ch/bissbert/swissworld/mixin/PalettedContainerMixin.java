package ch.bissbert.swissworld.mixin;

import ch.bissbert.swissworld.world.SectionPacking;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Single-value containers skip vanilla's unpack/remap on save; see {@link SectionPacking}. Owner: mod-core. */
@Mixin(PalettedContainer.class)
abstract class PalettedContainerMixin<T> {
    @Inject(method = "pack", at = @At("HEAD"), cancellable = true)
    private void swissworld$packSingleValue(IdMap<T> registry, PalettedContainer.Strategy strategy,
                                            CallbackInfoReturnable<PalettedContainerRO.PackedData<T>> cir) {
        if (!SectionPacking.ENABLED) return;
        @SuppressWarnings("unchecked")
        PalettedContainerRO.PackedData<T> packed = SectionPacking.packSingleValue((PalettedContainer<T>) (Object) this);
        if (packed != null) cir.setReturnValue(packed);
    }
}
