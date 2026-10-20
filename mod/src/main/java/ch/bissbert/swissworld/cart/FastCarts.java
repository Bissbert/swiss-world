package ch.bissbert.swissworld.cart;

import ch.bissbert.swissworld.SwissConfig;
import ch.bissbert.swissworld.SwissRegistries;
import ch.bissbert.swissworld.SwissWorld;
import ch.bissbert.swissworld.world.PlaceholderRepair;
import ch.bissbert.swissworld.world.TerrainHold;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import net.minecraft.world.phys.Vec3;

/**
 * Faster minecarts in Switzerland, where rail lines are tens of kilometres long. Owner: mod-cart.
 *
 * <p>Vanilla moves a cart once per tick: it snaps the cart onto the rail it is on and then moves it straight by its
 * velocity (at most 0.4 per axis on a normal rail). Faster than that, one move can carry the cart past a curve, onto a
 * slope's wrong height or off a switch, and the velocity is re-aimed by the number of blocks crossed. So a fast cart is
 * moved in {@linkplain CartSpeed#substeps substeps} of at most {@link CartSpeed#SUBSTEP}, each one vanilla's own
 * {@code moveAlongTrack} on the rail the cart is on at that moment: every rail shape is followed exactly as at vanilla
 * speed, including zigzag diagonals (runs of curve pieces), which keep their speed. Velocity is reset before each
 * substep; friction, slopes and powered rails are applied once per tick ({@link CartSpeed#effects}).
 *
 * <p>The track ahead is walked ({@link RailWalk}) up to the braking distance from the cap: the cart brakes to vanilla
 * speed before the end of the track, and to a stop before a chunk that is not ready (unloaded, a placeholder, not yet
 * on the rider's client; {@link TerrainHold#terrainReady}, which would otherwise take the rider off the cart).
 *
 * <p>Applies to carts with a player on board in Switzerland, or to every cart there but furnace carts with {@code
 * cartBoostUnridden}; never in water. The rider's forward and back keys are throttle and brake ({@code
 * cartRiderThrottle}); {@code cartCruiseControl} holds the speed without a key.
 */
public final class FastCarts {
    private FastCarts() {}

    /** Vanilla moves a ridden cart by 0.75 of its velocity per tick. */
    static final double RIDDEN = 0.75;

    public static boolean applies(AbstractMinecart cart) {
        if (cart.level().isClientSide || cart.level().dimension() != SwissRegistries.SWITZERLAND) return false;
        if (cart instanceof MinecartFurnace || cart.isInWater() || !cart.canUseRail()) return false;
        if (!SwissConfig.FAST_CARTS.get()) return false;
        return cart.getFirstPassenger() instanceof ServerPlayer || SwissConfig.CART_BOOST_UNRIDDEN.get();
    }

    /** Replaces one vanilla {@code moveAlongTrack(pos, state)}; {@code vanilla} runs the original. */
    public static void tick(AbstractMinecart cart, BlockPos pos, BlockState state,
            BiConsumer<BlockPos, BlockState> vanilla) {
        ServerLevel level = (ServerLevel) cart.level();
        FastCart fc = (FastCart) cart;
        ServerPlayer rider = cart.getFirstPassenger() instanceof ServerPlayer p ? p : null;
        double f = cart.isVehicle() ? RIDDEN : 1.0;
        double cap = SwissConfig.CART_MAX_SPEED.get();
        double accel = SwissConfig.CART_ACCELERATION.get();
        boolean cruise = rider != null && SwissConfig.CART_CRUISE.get();
        int input = rider == null || !SwissConfig.CART_THROTTLE.get() ? 0
                : rider.zza > 0.1f ? 1 : rider.zza < -0.1f ? -1 : 0;
        RailShape shape = ((BaseRailBlock) state.getBlock()).getRailDirection(state, level, pos, cart);

        Vec3 dm = cart.getDeltaMovement();
        double v = Math.min(dm.horizontalDistance() * f, Math.max(cap, CartSpeed.VANILLA));
        double hx, hz;
        if (v < 0.005) {
            boolean resume = input == 0 && cruise && fc.swissworld$target() > 0
                    && (fc.swissworld$headingX() != 0 || fc.swissworld$headingZ() != 0);
            if (input <= 0 && !resume) {
                fc.swissworld$setTarget(0);
                vanilla.accept(pos, state);
                return;
            }
            double lx = resume ? fc.swissworld$headingX() : rider.getLookAngle().x;
            double lz = resume ? fc.swissworld$headingZ() : rider.getLookAngle().z;
            int[] e = RailWalk.exitAlong(shape, lx, lz);
            double n = Math.hypot(e[0], e[1]);
            hx = e[0] / n;
            hz = e[1] / n;
            v = 0;
        } else {
            double h = dm.horizontalDistance();
            hx = dm.x / h;
            hz = dm.z / h;
        }

        RailWalk.Ahead ahead = RailWalk.scan(new View(level, cart, rider), pos.getX(), pos.getY(), pos.getZ(), shape,
                hx, hz, CartSpeed.lookahead(cap));
        double limit = CartSpeed.limit(ahead, cap);
        double target = CartSpeed.target(fc.swissworld$target(), v, input, cap, accel, cruise);
        double vNew = CartSpeed.approach(v, target, limit, accel);

        int n = CartSpeed.substeps(vNew);
        double step = vNew / n;
        double y0 = cart.getY();
        int on = 0, off = 0;
        long lastPowered = Long.MIN_VALUE;
        BlockPos p = pos;
        BlockState s = state;
        boolean derailed = false;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                p = cart.getCurrentRailPosition();
                s = level.getBlockState(p);
                if (!BaseRailBlock.isRail(s)) {
                    derailed = true;
                    break;
                }
            }
            if (s.getBlock() instanceof PoweredRailBlock pr && !pr.isActivatorRail() && p.asLong() != lastPowered) {
                lastPowered = p.asLong();
                if (s.getValue(PoweredRailBlock.POWERED)) on++;
                else off++;
            }
            cart.setDeltaMovement(hx * step / f, cart.getDeltaMovement().y, hz * step / f);
            vanilla.accept(p, s);
            Vec3 m = cart.getDeltaMovement();
            double h = m.horizontalDistance();
            if (h > 1e-6) {
                hx = m.x / h;
                hz = m.z / h;
            }
        }

        boolean motor = rider != null && (cruise || input != 0);
        double friction = cart.isVehicle() ? 0.997 : 0.96;
        double vAfter = CartSpeed.effects(vNew, on, off, y0 - cart.getY(), motor, friction, cap);
        if (vAfter > target || off > 0 || !motor) target = Math.min(cap, vAfter);
        fc.swissworld$setTarget(target);
        fc.swissworld$setHeading(hx, hz);
        cart.setDeltaMovement(hx * vAfter / f, cart.getDeltaMovement().y, hz * vAfter / f);

        if (derailed || !BaseRailBlock.isRail(level.getBlockState(cart.getCurrentRailPosition()))) {
            SwissWorld.LOGGER.info("[cart] off rail at {} {} {}, speed {} (track ahead: {} after {} blocks)",
                    String.format("%.1f", cart.getX()), String.format("%.1f", cart.getY()),
                    String.format("%.1f", cart.getZ()), String.format("%.2f", vNew), ahead.stop(),
                    String.format("%.1f", ahead.distance()));
        }
    }

    /** The world through loaded chunks only: a look-ahead must never load or generate a chunk. */
    private static final class View implements RailWalk.RailView {
        private final ServerLevel level;
        private final AbstractMinecart cart;
        private final ServerPlayer rider;
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        private long lastChunk = Long.MIN_VALUE;
        private boolean lastReady;

        View(ServerLevel level, AbstractMinecart cart, ServerPlayer rider) {
            this.level = level;
            this.cart = cart;
            this.rider = rider;
        }

        @Override
        public RailShape shape(int x, int y, int z) {
            LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (c == null) return null;
            BlockState s = c.getBlockState(at.set(x, y, z));
            if (!BaseRailBlock.isRail(s)) return null;
            return ((BaseRailBlock) s.getBlock()).getRailDirection(s, level, at, cart);
        }

        @Override
        public boolean ready(int x, int z) {
            ChunkPos cp = new ChunkPos(x >> 4, z >> 4);
            if (cp.toLong() == lastChunk) return lastReady;
            lastChunk = cp.toLong();
            lastReady = rider != null ? TerrainHold.terrainReady(level, rider, cp)
                    : level.getChunkSource().getChunkNow(cp.x, cp.z) != null && PlaceholderRepair.settled(cp);
            return lastReady;
        }
    }
}
