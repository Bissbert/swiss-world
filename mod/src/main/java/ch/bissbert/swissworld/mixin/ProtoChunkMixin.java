package ch.bissbert.swissworld.mixin;

import ch.bissbert.swissworld.world.SwissLight;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** As {@link LevelChunkMixin}, for chunks still generating once their light is initialised. Owner: mod-core. */
@Mixin(ProtoChunk.class)
abstract class ProtoChunkMixin {
    @Shadow private volatile LevelLightEngine lightEngine;

    @Shadow public abstract ChunkStatus getPersistedStatus();

    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState swissworld$lightStatus(LevelChunkSection section, int x, int y, int z, BlockState state,
                                              Operation<BlockState> original, BlockPos pos) {
        LevelLightEngine engine = lightEngine;
        if (engine == null || !getPersistedStatus().isOrAfter(ChunkStatus.INITIALIZE_LIGHT)
                || !SwissLight.applies(((LevelLightEngineAccessor) engine).swissworld$level())) {
            return original.call(section, x, y, z, state);
        }
        LevelChunkSection[] sections = ((ProtoChunk) (Object) this).getSections();
        int i = ((ProtoChunk) (Object) this).getSectionIndex(pos.getY());
        int before = SwissLight.statusBits(sections, i);
        BlockState old = original.call(section, x, y, z, state);
        SwissLight.publish(engine, SectionPos.of(pos), before, SwissLight.statusBits(sections, i));
        return old;
    }
}
