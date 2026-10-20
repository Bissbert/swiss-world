package ch.bissbert.swissworld;

import net.neoforged.neoforge.common.ModConfigSpec;

/** {@code <world>/serverconfig/swissworld-server.toml}. Owner: mod-core. Read at server start. */
public final class SwissConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> SIDECAR_URL = B
            .comment("Base URL of the swissdata sidecar (docs/sidecar-api.md)")
            .define("sidecarUrl", "http://127.0.0.1:8080");
    public static final ModConfigSpec.ConfigValue<String> STORE = B
            .comment("Store directory with cells/ and coverage.bin. Empty: system property swissworld.store,",
                    "else env SWISSWORLD_STORE, else ./swissworld-store")
            .define("store", "");
    public static final ModConfigSpec.IntValue CELL_CACHE = B
            .comment("Decoded cells kept in memory. A cell with all layers is ~10 MB of heap")
            .defineInRange("cellCacheSize", 64, 9, 1024);
    public static final ModConfigSpec.LongValue ORIGIN_E = B
            .comment("Default origin (LV95) for a new dimension; block (0, 0) is this point.",
                    "Stored in the dimension's data on first use, then locked once a chunk generated")
            .defineInRange("originE", SwissRuntime.DEFAULT_ORIGIN.e0(), 2_480_000L, 2_840_000L);
    public static final ModConfigSpec.LongValue ORIGIN_N = B
            .defineInRange("originN", SwissRuntime.DEFAULT_ORIGIN.n0(), 1_070_000L, 1_300_000L);
    public static final ModConfigSpec.BooleanValue PREFETCH = B
            .comment("Ask the sidecar to prefetch around the origin at startup and ahead of moving players")
            .define("prefetch", true);
    public static final ModConfigSpec.IntValue STARTUP_RADIUS = B
            .comment("Startup prefetch radius around the origin, metres")
            .defineInRange("startupPrefetchRadiusM", 2000, 0, 20_000);
    public static final ModConfigSpec.IntValue LOOKAHEAD_S = B
            .comment("Player lookahead: prefetch corridor length = this many seconds at the player's speed")
            .defineInRange("lookaheadSeconds", 60, 0, 600);
    public static final ModConfigSpec.IntValue PREFETCH_INTERVAL_S = B
            .comment("Minimum seconds between two prefetch calls for the same player")
            .defineInRange("prefetchIntervalSeconds", 5, 1, 300);
    public static final ModConfigSpec.IntValue STALL_ABORT_S = B
            .comment("If the server thread waits this long for a chunk whose cells are still missing (e.g. forceload",
                    "while the sidecar is down), pending chunks are given up: they become empty placeholders that are",
                    "never saved and are regenerated in place once their cells arrive. Keep it below max-tick-time",
                    "(60 s). 0 disables")
            .defineInRange("stallAbortSeconds", 20, 0, 600);
    public static final ModConfigSpec.DoubleValue PLAYER_STALL_ABORT_S = B
            .comment("The same for a chunk a player or entity needs (/tp, ender pearl, elytra, login, a player's own",
                    "tick): give up after this many seconds. Players in a placeholder are held in place until its terrain is there. Shorter means",
                    "shorter freezes but more placeholders. 0: use stallAbortSeconds")
            .defineInRange("playerStallAbortSeconds", 2.0, 0.0, 600.0);

    public static final ModConfigSpec.IntValue IO_THREADS = B
            .comment("Threads that read and decode cell files and talk to the sidecar (never the server thread).",
                    "0: automatic, clamp(cores / 2, 2, 6)")
            .defineInRange("ioThreads", 0, 0, 32);
    public static final ModConfigSpec.IntValue PREFETCH_MAX_IN_FLIGHT = B
            .comment("Most 'prefetch' requests for the cells ahead of a player that may be unanswered at once, per player")
            .defineInRange("prefetchMaxInFlight", 2, 1, 16);

    public static final ModConfigSpec.BooleanValue PREFETCH_AHEAD = B
            .comment("Also request, at 'prefetch' priority, the ring of cells one kilometre ahead of a moving player,",
                    "and decode them as soon as they are in the store")
            .define("prefetchAhead", true);

    public static final ModConfigSpec.BooleanValue FAST_CARTS = B
            .comment("Faster minecarts in Switzerland: carts with a player on board run up to cartMaxSpeed, moved in",
                    "substeps along the rails so they never jump a curve; they brake before the end of the track and stop",
                    "before terrain that is not loaded yet. Elsewhere, and for carts without a player, vanilla")
            .define("fastCarts", true);
    public static final ModConfigSpec.DoubleValue CART_MAX_SPEED = B
            .comment("Cruising cap of a fast cart, blocks per tick (1.0 = 20 m/s; vanilla 0.4 = 8 m/s)")
            .defineInRange("cartMaxSpeed", 1.0, 0.4, 1.5);
    public static final ModConfigSpec.DoubleValue CART_ACCELERATION = B
            .comment("Speed gained per tick, blocks per tick, while the rider holds forward or after the cart slowed for",
                    "the track ahead (0.02: from 0 to 20 m/s in 2.5 s). Braking (back key) is twice this")
            .defineInRange("cartAcceleration", 0.02, 0.001, 0.2);
    public static final ModConfigSpec.BooleanValue CART_THROTTLE = B
            .comment("The rider's forward and back keys are throttle and brake of a fast cart (generated lines have no",
                    "powered rails, so without this only powered rails speed a cart up)")
            .define("cartRiderThrottle", true);
    public static final ModConfigSpec.BooleanValue CART_CRUISE = B
            .comment("A fast cart keeps its speed without a key (no friction, no slowing uphill); off: it coasts like vanilla")
            .define("cartCruiseControl", true);
    public static final ModConfigSpec.BooleanValue CART_BOOST_UNRIDDEN = B
            .comment("Also run carts without a player (empty, chest, hopper ...; never furnace carts) up to cartMaxSpeed",
                    "in Switzerland; they have no throttle, so only powered rails speed them up")
            .define("cartBoostUnridden", false);

    public static final ModConfigSpec SPEC = B.build();

    private SwissConfig() {}
}
