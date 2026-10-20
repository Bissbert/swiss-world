package ch.bissbert.swissworld.mixin;

import ch.bissbert.swissworld.cart.FastCart;
import ch.bissbert.swissworld.cart.FastCarts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fast carts in Switzerland, moved in substeps of vanilla's own {@code moveAlongTrack}; see {@link FastCarts}.
 * NeoForge's hooks cannot do it: a vanilla rail's {@code getRailMaxSpeed} is a fixed 0.4 with no event, a cart's cap
 * is at most {@code getMaxCartSpeedOnRail()} 1.2, and either way the cart would still move once per tick. Owner:
 * mod-cart.
 */
@Mixin(AbstractMinecart.class)
abstract class AbstractMinecartMixin implements FastCart {
    @Unique private boolean swissworld$stepping;
    @Unique private double swissworld$target;
    @Unique private double swissworld$hx;
    @Unique private double swissworld$hz;

    @Shadow
    protected abstract void moveAlongTrack(BlockPos pos, BlockState state);

    @Inject(method = "moveAlongTrack", at = @At("HEAD"), cancellable = true)
    private void swissworld$fastCart(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (swissworld$stepping) return;
        AbstractMinecart self = (AbstractMinecart) (Object) this;
        if (!FastCarts.applies(self)) return;
        swissworld$stepping = true;
        try {
            FastCarts.tick(self, pos, state, this::moveAlongTrack);
        } finally {
            swissworld$stepping = false;
        }
        ci.cancel();
    }

    @Override
    public double swissworld$target() {
        return swissworld$target;
    }

    @Override
    public void swissworld$setTarget(double target) {
        swissworld$target = target;
    }

    @Override
    public double swissworld$headingX() {
        return swissworld$hx;
    }

    @Override
    public double swissworld$headingZ() {
        return swissworld$hz;
    }

    @Override
    public void swissworld$setHeading(double x, double z) {
        swissworld$hx = x;
        swissworld$hz = z;
    }
}
