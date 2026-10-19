package ch.bissbert.swissworld.mixin;

import ch.bissbert.swissworld.world.SwissLight;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A block change that opens or seals buried rock updates its light storage (server and client); see
 * {@link SwissLight}. Runs before vanilla's own air/non-air update and {@code checkBlock}. Owner: mod-core.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Shadow @Final Level level;

    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState swissworld$lightStatus(LevelChunkSection section, int x, int y, int z, BlockState state,
                                              Operation<BlockState> original, BlockPos pos) {
        if (!SwissLight.applies(level)) return original.call(section, x, y, z, state);
        LevelChunkSection[] sections = ((LevelChunk) (Object) this).getSections();
        int i = ((LevelChunk) (Object) this).getSectionIndex(pos.getY());
        int before = SwissLight.statusBits(sections, i);
        BlockState old = original.call(section, x, y, z, state);
        SwissLight.publish(level.getChunkSource().getLightEngine(), SectionPos.of(pos), before, SwissLight.statusBits(sections, i));
        return old;
    }
}
