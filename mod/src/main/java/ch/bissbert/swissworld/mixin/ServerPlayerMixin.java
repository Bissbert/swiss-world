package ch.bissbert.swissworld.mixin;

import ch.bissbert.swissworld.SwissRegistries;
import ch.bissbert.swissworld.world.FlatHorizon;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Switzerland is sent to clients as flat, for a normal sky below y 63; see {@link FlatHorizon}. Owner: mod-core. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Inject(method = "createCommonSpawnInfo", at = @At("RETURN"), cancellable = true)
    private void swissworld$flatHorizon(ServerLevel level, CallbackInfoReturnable<CommonPlayerSpawnInfo> cir) {
        cir.setReturnValue(FlatHorizon.forClient(cir.getReturnValue(), SwissRegistries.SWITZERLAND));
    }
}
