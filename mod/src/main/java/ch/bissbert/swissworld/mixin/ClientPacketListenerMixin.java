package ch.bissbert.swissworld.mixin;

import ch.bissbert.swissworld.world.SwissLight;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client's light engine clones its section map every frame, so it skips buried rock too; see {@link SwissLight}.
 * Owner: mod-core.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Shadow private ClientLevel level;

    @WrapOperation(method = "enableChunkLight", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z"))
    private boolean swissworld$lightEmpty(LevelChunkSection section, Operation<Boolean> original, LevelChunk chunk, int x, int z) {
        if (!SwissLight.applies(level)) return original.call(section);
        LevelChunkSection[] sections = chunk.getSections();
        int i = SwissLight.indexOf(sections, section);
        return i < 0 ? original.call(section) : SwissLight.lightEmpty(sections, i);
    }
}
