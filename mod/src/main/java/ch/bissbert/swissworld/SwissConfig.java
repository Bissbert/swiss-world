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

    public static final ModConfigSpec SPEC = B.build();

    private SwissConfig() {}
}
