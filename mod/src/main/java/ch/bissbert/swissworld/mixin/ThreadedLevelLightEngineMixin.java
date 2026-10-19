package ch.bissbert.swissworld.mixin;

import ch.bissbert.swissworld.world.SwissLight;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A new chunk's light storage skips buried rock in Switzerland; see {@link SwissLight}. Owner: mod-core. */
@Mixin(ThreadedLevelLightEngine.class)
abstract class ThreadedLevelLightEngineMixin {
    /** The PRE_UPDATE task of {@code initializeLight}: one {@code hasOnlyAir} per section. */
    @WrapOperation(method = "lambda$initializeLight$19", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private boolean swissworld$lightEmpty(LevelChunkSection section, Operation<Boolean> original, ChunkAccess chunk, ChunkPos pos) {
        if (!SwissLight.applies(((LevelLightEngineAccessor) this).swissworld$level())) return original.call(section);
        LevelChunkSection[] sections = chunk.getSections();
        int i = SwissLight.indexOf(sections, section);
        return i < 0 ? original.call(section) : SwissLight.lightEmpty(sections, i);
    }
}
