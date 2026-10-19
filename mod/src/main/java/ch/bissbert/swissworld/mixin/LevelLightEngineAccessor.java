package ch.bissbert.swissworld.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level a light engine belongs to, for {@link ch.bissbert.swissworld.world.SwissLight#applies}. Owner: mod-core. */
@Mixin(LevelLightEngine.class)
public interface LevelLightEngineAccessor {
    @Accessor("levelHeightAccessor")
    LevelHeightAccessor swissworld$level();
}
