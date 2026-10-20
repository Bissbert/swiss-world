package ch.bissbert.swissworld.cart;

/**
 * State a minecart carries for {@link FastCarts}; implemented on AbstractMinecart by a mixin. Not saved: a reloaded
 * cart starts from its current motion. Owner: mod-cart.
 */
public interface FastCart {
    /** Target ground speed, blocks per tick. */
    double swissworld$target();

    void swissworld$setTarget(double target);

    /** Last horizontal heading (unit vector x, z), kept while the cart waits at a standstill; 0, 0 if none. */
    double swissworld$headingX();

    double swissworld$headingZ();

    void swissworld$setHeading(double x, double z);
}
