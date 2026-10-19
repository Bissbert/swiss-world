package ch.bissbert.swissworld.command;

import ch.bissbert.swissworld.SwissConfig;
import ch.bissbert.swissworld.SwissRegistries;
import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.SwissWorld;
import ch.bissbert.swissworld.cell.CellCache;
import ch.bissbert.swissworld.cell.SwdCell;
import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.cell.Wgs84;
import ch.bissbert.swissworld.net.SidecarClient.Place;
import ch.bissbert.swissworld.world.ReturnPos;
import ch.bissbert.swissworld.world.SwissOrigin;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * {@code /swiss} and {@code /swiss admin ...}. Owner: mod-core.
 *
 * <p>Entering never blocks the server tick: the target chunk gets a ticket, then {@link #tick} polls it with the
 * non-blocking {@code getChunkNow} and teleports once it is generated. Until then the player stays where they are.
 */
public final class SwissCommands {
    /** Marker file in the world folder; the next server start deletes the dimension (see SwissEvents). */
    public static final String RESET_MARKER = "swissworld-reset";

    private static final TicketType<ChunkPos> ENTER_TICKET =
            TicketType.create("swissworld_enter", Comparator.comparingLong(ChunkPos::toLong));
    private static final int STATUS_TICKS = 5 * 20;
    private static final String ATTRIBUTION = "Data ©swisstopo";

    /** Players waiting for their target chunk; main thread only. */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    /** A wait for the target chunk: started in dimension {@code from}; {@code y} NaN lands on the ground. */
    private static final class Pending {
        final ChunkPos chunk;
        final double x, y, z;
        final float yRot, xRot;
        final ResourceKey<Level> from;
        final int startTick;
        int lastProgressTick;
        long progress = Long.MIN_VALUE;

        Pending(ServerPlayer p, double x, double y, double z, float yRot, float xRot) {
            this.chunk = new ChunkPos(BlockPos.containing(x, 0, z));
            this.x = x;
            this.y = y;
            this.z = z;
            this.yRot = yRot;
            this.xRot = xRot;
            this.from = p.level().dimension();
            this.startTick = this.lastProgressTick = p.server.getTickCount();
        }
    }

    /** Sidecar activity seen by the last /status poll (main thread only): downloads, requests, store size. */
    private static long sidecarActivity;
    private static boolean sidecarBusy, statusInFlight;

    private static final PlaceLookup PLACES = new PlaceLookup(SwissRuntime::sidecar, System::currentTimeMillis);

    /** Tab completion for place names; the future completes off-thread, the server never waits for it. */
    private static final SuggestionProvider<CommandSourceStack> PLACE_SUGGESTIONS = (ctx, b) -> {
        String typed = b.getRemaining();
        return PLACES.suggest(typed).thenApply(list -> {
            String k = PlaceLookup.key(typed);
            for (Place p : PlaceLookup.forCompletion(list, typed)) {
                // "Berthoud" completes to the alias it matched, not to "Burgdorf"
                boolean alias = p.matched() != null && !p.matched().equals(p.name()) && PlaceLookup.key(p.matched()).startsWith(k);
                if (!alias || PlaceLookup.key(p.name()).startsWith(k)) b.suggest(p.name(), Component.literal(PlaceLookup.label(p)));
                if (alias) b.suggest(p.matched(), Component.literal(PlaceLookup.label(p)));
            }
            return b.build();
        });
    };

    private SwissCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("swiss")
                .executes(c -> toggle(c.getSource().getPlayerOrException()))
                .then(Commands.literal("back").executes(c -> back(c.getSource().getPlayerOrException())))
                .then(Commands.literal("where").executes(c -> where(c.getSource().getPlayerOrException())))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("goto")
                        .then(Commands.argument("a", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("b", DoubleArgumentType.doubleArg())
                                        .executes(c -> goTo(c.getSource().getPlayerOrException(),
                                                DoubleArgumentType.getDouble(c, "a"), DoubleArgumentType.getDouble(c, "b")))))
                        // registered after "a": on a tie Brigadier keeps the first, so two numbers stay coordinates
                        .then(Commands.argument("place", StringArgumentType.greedyString()).suggests(PLACE_SUGGESTIONS)
                                .executes(c -> goToPlace(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "place")))))
                .then(Commands.literal("admin").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("status").executes(c -> status(c.getSource())))
                        .then(Commands.literal("origin")
                                .executes(c -> showOrigin(c.getSource()))
                                .then(Commands.argument("e", LongArgumentType.longArg(2_480_000, 2_840_000))
                                        .then(Commands.argument("n", LongArgumentType.longArg(1_070_000, 1_300_000))
                                                .executes(SwissCommands::setOrigin)))
                                .then(Commands.literal("place")
                                        .then(Commands.argument("name", StringArgumentType.greedyString()).suggests(PLACE_SUGGESTIONS)
                                                .executes(c -> setOriginPlace(c.getSource(), StringArgumentType.getString(c, "name"))))))
                        .then(Commands.literal("prefetch")
                                .executes(c -> prefetch(c.getSource(), SwissConfig.STARTUP_RADIUS.get()))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 20_000))
                                        .executes(c -> prefetch(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
                        .then(Commands.literal("reset")
                                .executes(c -> {
                                    c.getSource().sendFailure(Component.literal(
                                            "This deletes the whole Switzerland dimension at the next start. Run /swiss admin reset confirm"));
                                    return 0;
                                })
                                .then(Commands.literal("confirm").executes(c -> reset(c.getSource()))))));
    }

    private static boolean inSwitzerland(ServerPlayer p) {
        return p.level().dimension() == SwissRegistries.SWITZERLAND;
    }

    private static int toggle(ServerPlayer p) {
        return inSwitzerland(p) ? back(p) : enter(p);
    }

    private static int enter(ServerPlayer p) {
        // back where you left Switzerland, else the origin
        ReturnPos last = p.getData(SwissRegistries.LAST_SWISS_POS);
        Pending t = last.present()
                ? new Pending(p, last.x(), last.y(), last.z(), last.yRot(), last.xRot())
                : new Pending(p, 0.5, Double.NaN, 0.5, p.getYRot(), p.getXRot());
        return travel(p, t);
    }

    /**
     * {@code /swiss goto a b}: real-world coordinates, read as LV95 (2 600 000 1 200 000), LV03 (600 000 200 000) or
     * WGS84 latitude longitude (46.68 7.87, the order map apps copy). Lands on the ground.
     */
    private static int goTo(ServerPlayer p, double a, double b) {
        double e, n;
        if (Math.abs(a) < 90 && Math.abs(b) < 180) {
            double[] en = Wgs84.toLv95(a, b);
            e = en[0];
            n = en[1];
        } else {
            e = a < 1_000_000 ? a + 2_000_000 : a;
            n = b < 1_000_000 ? b + 1_000_000 : b;
        }
        if (!insideLv95(e, n)) {
            p.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "LV95 E %.0f N %.0f is outside Switzerland. Use LV95 (E N), LV03 or WGS84 (lat lon).", e, n)).withStyle(ChatFormatting.RED));
            return 0;
        }
        SwissCoords c = SwissRuntime.coords();
        double x = Math.floor(e - c.e0()) + 0.5, z = Math.floor(c.n0() - n) + 0.5;
        p.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "Going to LV95 E %.0f N %.0f (x %.0f, z %.0f)", e, n, Math.floor(x), Math.floor(z)))
                .withStyle(ChatFormatting.GRAY));
        return travelLv95(p, e, n);
    }

    private static boolean insideLv95(double e, double n) {
        return e >= 2_480_000 && e <= 2_840_000 && n >= 1_070_000 && n <= 1_300_000;
    }

    private static int travelLv95(ServerPlayer p, double e, double n) {
        SwissCoords c = SwissRuntime.coords();
        double x = Math.floor(e - c.e0()) + 0.5, z = Math.floor(c.n0() - n) + 0.5;
        return travel(p, new Pending(p, x, Double.NaN, z, p.getYRot(), p.getXRot()));
    }

    /**
     * {@code /swiss goto <place>}: swissNAMES3D lookup through the sidecar, answered on the server thread once it
     * returns; then the same prefetch-and-wait {@link #travel} as coordinates. Up to three alternatives are listed,
     * clickable.
     */
    private static int goToPlace(ServerPlayer player, String query) {
        double[] ab = PlaceLookup.twoNumbers(query);
        if (ab != null) return goTo(player, ab[0], ab[1]);
        if (PENDING.containsKey(player.getUUID())) return travel(player, PENDING.get(player.getUUID()));
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        PLACES.find(query, 8).whenComplete((list, err) -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return;
            if (err != null) {
                p.sendSystemMessage(Component.literal(PlaceLookup.failure(err)).withStyle(ChatFormatting.RED));
                return;
            }
            List<Place> inside = list.stream().filter(pl -> insideLv95(pl.e(), pl.n())).toList();
            Place best = PlaceLookup.best(inside, query);
            if (best == null) {
                p.sendSystemMessage(Component.literal(PlaceLookup.notFound(query)).withStyle(ChatFormatting.RED));
                return;
            }
            p.sendSystemMessage(Component.literal(PlaceLookup.going(best)).withStyle(ChatFormatting.GRAY));
            MutableComponent also = alternatives(inside, best, query);
            if (also != null) p.sendSystemMessage(also);
            travelLv95(p, best.e(), best.n());
        }));
        return 1;
    }

    /** "also: [Zofingen (station)] [...]", each one a click on /swiss goto E N; null when there are none. */
    private static MutableComponent alternatives(List<Place> list, Place best, String query) {
        MutableComponent also = Component.literal("also: ").withStyle(ChatFormatting.GRAY);
        int shown = 0;
        for (Place pl : list) {
            if (pl == best || !PlaceLookup.offered(pl, query) || shown == 3) continue;
            String cmd = String.format(Locale.ROOT, "/swiss goto %.0f %.0f", pl.e(), pl.n());
            if (shown > 0) also.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            also.append(Component.literal(PlaceLookup.label(pl)).withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(cmd)))));
            shown++;
        }
        return shown == 0 ? null : also;
    }

    private static int travel(ServerPlayer p, Pending t) {
        ServerLevel swiss = p.server.getLevel(SwissRegistries.SWITZERLAND);
        if (swiss == null) {
            p.sendSystemMessage(Component.literal("The Switzerland dimension is not loaded").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (PENDING.containsKey(p.getUUID())) {
            p.sendSystemMessage(Component.literal("Still preparing your arrival in Switzerland… (/swiss back cancels)"));
            return 0;
        }
        swiss.getChunkSource().addRegionTicket(ENTER_TICKET, t.chunk, 1, t.chunk);
        PENDING.put(p.getUUID(), t);
        SwissCoords c = SwissRuntime.coords();
        if (SwissConfig.PREFETCH.get()) {
            SwissRuntime.sidecar().prefetch(c.e0() + t.x, c.n0() - t.z, 1000, 0, 0).exceptionally(e -> 0);
        }
        p.sendSystemMessage(Component.literal("Preparing Switzerland… (" + ATTRIBUTION + ")").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /**
     * Server tick: teleport players whose target chunk is ready. The wait has no fixed limit: it goes on while cells
     * arrive, chunks generate or the sidecar downloads/builds, and gives up after {@link Arrival#NO_PROGRESS_TICKS}
     * without any of that (or {@link Arrival#HARD_CAP_TICKS} in all), or when the player leaves the dimension they
     * started from. Progress shows on the action bar.
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        ServerLevel swiss = server.getLevel(SwissRegistries.SWITZERLAND);
        int now = server.getTickCount();
        pollSidecar(server, now);
        for (Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> en = it.next();
            Pending t = en.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            LevelChunk chunk = swiss == null ? null : swiss.getChunkSource().getChunkNow(t.chunk.x, t.chunk.z);
            int[] counts = swiss == null ? new int[4] : counts(swiss, t);
            long progress = sidecarActivity * 31 + SwissRuntime.chunksGenerated() * 7 + counts[0] * 1000L + counts[2];
            if (progress != t.progress) {
                t.progress = progress;
                t.lastProgressTick = now;
            }
            boolean moved = p != null && p.level().dimension() != t.from;
            boolean timeout = Arrival.givesUp(now, t.startTick, t.lastProgressTick, sidecarBusy);
            if (p == null || chunk != null || timeout || moved || swiss == null) {
                it.remove();
                if (swiss != null) swiss.getChunkSource().removeRegionTicket(ENTER_TICKET, t.chunk, 1, t.chunk);
            }
            if (p == null || swiss == null) continue;
            if (moved) {
                p.sendSystemMessage(Component.literal("Trip to Switzerland cancelled: you changed dimension.").withStyle(ChatFormatting.GRAY));
            } else if (chunk != null) {
                double x = t.x, z = t.z, y = t.y;
                if (Double.isNaN(y)) {
                    int[] at = landing(swiss, (int) Math.floor(x), (int) Math.floor(z));
                    x = at[0] + 0.5;
                    z = at[1] + 0.5;
                    y = at[2] + 1;
                }
                boolean arriving = !inSwitzerland(p);
                if (arriving) {
                    p.setData(SwissRegistries.RETURN_POS, new ReturnPos(p.level().dimension(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
                }
                p.teleportTo(swiss, x, y, z, t.yRot, t.xRot);
                p.displayClientMessage(Component.empty(), true);
                p.sendSystemMessage(Component.literal(arriving ? "Welcome to Switzerland. " : "").append(whereText(p)));
            } else if (timeout) {
                p.sendSystemMessage(Component.literal("The terrain there is not ready yet: no progress for "
                        + Arrival.NO_PROGRESS_TICKS / 20 + " s"
                        + (SwissRuntime.cells().sidecarDown() ? " (the data sidecar is not reachable)" : "")
                        + ". Try again in a while.").withStyle(ChatFormatting.RED));
            } else if ((now - t.startTick) % Arrival.BAR_TICKS == 0) {
                p.displayClientMessage(Component.literal(Arrival.bar(counts[0], counts[1], counts[2], counts[3],
                        SwissRuntime.cells().sidecarDown())).withStyle(ChatFormatting.GRAY), true);
            }
        }
    }

    /** {cells ready, cells needed, chunks generated, chunks needed} for the 3x3 chunks the arrival ticket loads. */
    private static int[] counts(ServerLevel swiss, Pending t) {
        CellCache cells = SwissRuntime.cells();
        SwissCoords c = SwissRuntime.coords();
        int chunks = 0, ready = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = t.chunk.x + dx, cz = t.chunk.z + dz;
                if (swiss.getChunkSource().getChunkNow(cx, cz) != null) chunks++;
                for (int corner = 0; corner < 4; corner++) {
                    int bx = cx * 16 + (corner & 1) * 15, bz = cz * 16 + (corner >> 1) * 15;
                    int e = SwissCoords.cellKm(c.east(bx)), n = SwissCoords.cellKm(c.north(bz));
                    if (seen.add(CellCache.key(e, n))
                            && (cells.getIfPresent(e, n) != null || cells.coverageState(e, n) == ch.bissbert.swissworld.cell.Coverage.OUTSIDE)) {
                        ready++;
                    }
                }
            }
        }
        return new int[] {ready, seen.size(), chunks, 9};
    }

    /** Asks the sidecar's /status now and then while someone waits; its counters move while it downloads and builds. */
    private static void pollSidecar(MinecraftServer server, int now) {
        if (statusInFlight || now % STATUS_TICKS != 0) return;
        statusInFlight = true;
        SwissRuntime.sidecar().status().whenComplete((st, err) -> server.execute(() -> {
            statusInFlight = false;
            if (err != null || st == null) {
                sidecarBusy = false;
                return;
            }
            try {
                JsonObject q = st.getAsJsonObject("queue"), up = st.getAsJsonObject("upstream"), store = st.getAsJsonObject("store");
                sidecarBusy = q.get("building").getAsInt() + q.get("now").getAsInt() > 0;
                sidecarActivity = up.get("requests").getAsLong() * 3 + up.get("downloads").getAsLong() * 5
                        + up.get("downloadBytes").getAsLong() + store.get("cells").getAsLong() * 11;
            } catch (RuntimeException e) {
                sidecarBusy = false;
            }
        }));
    }

    /**
     * Where to stand near column (x, z): {x, z, top block y}. The column itself when it is open ground, else the
     * nearest open column within {@link Arrival#LANDING_RADIUS} (street, yard, square); the roof only if there is none.
     */
    private static int[] landing(ServerLevel swiss, int x, int z) {
        int[] at = Arrival.nearestOpen(x, z, Arrival.LANDING_RADIUS, (cx, cz) -> openColumn(swiss, cx, cz));
        if (at == null) at = new int[] {x, z};
        LevelChunk ch = swiss.getChunkSource().getChunkNow(at[0] >> 4, at[1] >> 4);
        return new int[] {at[0], at[1], ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at[0], at[1])};
    }

    private static boolean openColumn(ServerLevel swiss, int x, int z) {
        LevelChunk ch = swiss.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (ch == null) return false;
        SwissCoords c = SwissRuntime.coords();
        long e = c.east(x), n = c.north(z);
        SwdCell cell = SwissRuntime.cells().getIfPresent(SwissCoords.cellKm(e), SwissCoords.cellKm(n));
        if (cell == null) return false;
        int dx = SwissCoords.dx(e), dz = SwissCoords.dz(n);
        SwdCell.Span[] spans = cell.spansAt(dx, dz);
        int top = ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        boolean fluid = !ch.getFluidState(new BlockPos(x, top, z)).isEmpty();
        return Arrival.openColumn(top, fluid, spans != null && spans.length > 0,
                SwissCoords.toY(cell.terrain(dx + 0.5, dz + 0.5)));
    }

    /** Server stopping: drop pending entries (their tickets go with the level). */
    public static void clear() {
        PENDING.clear();
    }

    private static int back(ServerPlayer p) {
        if (PENDING.remove(p.getUUID()) instanceof Pending t) {
            ServerLevel swiss = p.server.getLevel(SwissRegistries.SWITZERLAND);
            if (swiss != null) swiss.getChunkSource().removeRegionTicket(ENTER_TICKET, t.chunk, 1, t.chunk);
            p.displayClientMessage(Component.empty(), true);
            p.sendSystemMessage(Component.literal("Cancelled."));
            return 1;
        }
        if (!inSwitzerland(p)) {
            p.sendSystemMessage(Component.literal("You are not in Switzerland. /swiss takes you there."));
            return 0;
        }
        p.setData(SwissRegistries.LAST_SWISS_POS, new ReturnPos(SwissRegistries.SWITZERLAND, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
        ReturnPos r = p.getData(SwissRegistries.RETURN_POS);
        ServerLevel to = r.present() ? p.server.getLevel(r.dimension()) : null;
        if (to == null || to.dimension() == SwissRegistries.SWITZERLAND) {
            ServerLevel ow = p.server.overworld();
            BlockPos spawn = ow.getSharedSpawnPos();
            p.teleportTo(ow, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, p.getYRot(), p.getXRot());
        } else {
            p.teleportTo(to, r.x(), r.y(), r.z(), r.yRot(), r.xRot());
        }
        p.setData(SwissRegistries.RETURN_POS, ReturnPos.NONE);
        return 1;
    }

    private static int where(ServerPlayer p) {
        if (!inSwitzerland(p)) {
            p.sendSystemMessage(Component.literal("You are not in Switzerland."));
            return 0;
        }
        p.sendSystemMessage(whereText(p));
        return 1;
    }

    static MutableComponent whereText(ServerPlayer p) {
        SwissCoords c = SwissRuntime.coords();
        double e = c.e0() + p.getX(), n = c.n0() - p.getZ();
        double[] ll = Wgs84.fromLv95(e, n);
        double alt = SwissCoords.toMetres(p.getY());
        double ground = groundHeight(c, p.getX(), p.getZ());
        String url = String.format(Locale.ROOT, "https://map.geo.admin.ch/#/map?lang=en&center=%d,%d&z=10&crosshair=marker",
                Math.round(e), Math.round(n));
        String text = String.format(Locale.ROOT, "LV95 E %d N %d · WGS84 %.5f° N %.5f° E · %.0f m a.s.l.%s ",
                Math.round(e), Math.round(n), ll[0], ll[1], alt,
                Double.isNaN(ground) ? "" : String.format(Locale.ROOT, " (ground %.1f m)", ground));
        return Component.literal(text).append(Component.literal("[map]")
                .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));
    }

    /** Real terrain height under (x, z) from the loaded cell, NaN if not loaded or outside. */
    static double groundHeight(SwissCoords c, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int eKm = SwissCoords.cellKm(c.east(bx)), nKm = SwissCoords.cellKm(c.north(bz));
        SwdCell cell = SwissRuntime.cells().getIfPresent(eKm, nKm);
        if (cell == null || !cell.inside(SwissCoords.dx(c.east(bx)), SwissCoords.dz(c.north(bz)))) return Double.NaN;
        double fx = c.e0() + x - eKm * 1000.0, fz = 1000.0 - (c.n0() - z - nKm * 1000.0);
        return cell.terrain(fx, fz);
    }

    private static int info(CommandSourceStack s) {
        s.sendSystemMessage(Component.literal("Swiss World: Switzerland at 1:1. Terrain (swissALTI3D), land cover and water "
                + "(swissTLM3D), buildings (swissBUILDINGS3D) and borders (swissBOUNDARIES3D) are open data ©swisstopo. "));
        s.sendSystemMessage(Component.literal("Heights: y = metres − 2000 up to 3000 m, above that 0.6 blocks per metre. ")
                .append(Component.literal("[swisstopo]").withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://www.swisstopo.admin.ch")))));
        return 1;
    }

    private static int status(CommandSourceStack s) {
        CellCache cells = SwissRuntime.cells();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20, maxMb = rt.maxMemory() >> 20;
        s.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "cells %d/%d cached, %d pending · chunks %d generated (%.1f ms/chunk), %d waiting, %d aborted · heap %d/%d MB · coverage %s",
                cells.cachedCount(), cells.capacity(), cells.pendingCount(), SwissRuntime.chunksGenerated(), SwissRuntime.meanChunkMs(),
                SwissRuntime.PENDING_CHUNKS.get(), SwissRuntime.abortedCount(), usedMb, maxMb, cells.hasCoverage() ? "loaded" : "missing")));
        MinecraftServer server = s.getServer();
        SwissRuntime.sidecar().status().whenComplete((JsonObject o, Throwable err) -> server.execute(() ->
                s.sendSystemMessage(Component.literal(err == null ? "sidecar: " + o : "sidecar: not reachable (" + rootMessage(err) + ")"))));
        return 1;
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static int showOrigin(CommandSourceStack s) {
        SwissOrigin o = SwissRuntime.origin();
        SwissCoords c = SwissRuntime.coords();
        s.sendSystemMessage(Component.literal("Origin E " + c.e0() + " N " + c.n0()
                + (o == null ? " (dimension not loaded)" : o.locked() ? " (locked)" : " (not locked yet)")));
        return 1;
    }

    private static int setOrigin(CommandContext<CommandSourceStack> ctx) {
        long e = LongArgumentType.getLong(ctx, "e"), n = LongArgumentType.getLong(ctx, "n");
        if (SwissRuntime.origin() == null || !SwissRuntime.changeOrigin(e, n)) {
            ctx.getSource().sendFailure(Component.literal("The origin is locked: chunks were already generated. "
                    + "/swiss admin reset confirm deletes the dimension at the next start."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Origin set to E " + e + " N " + n), true);
        return 1;
    }

    /**
     * {@code /swiss admin origin place <name>}: the origin at a named place. Same rule as E N: only while the origin is
     * not locked (fresh world, nothing generated yet); checked again on the server thread after the lookup.
     */
    private static int setOriginPlace(CommandSourceStack s, String name) {
        SwissOrigin o = SwissRuntime.origin();
        if (o == null || o.locked()) {
            s.sendFailure(Component.literal("The origin is locked: chunks were already generated. "
                    + "/swiss admin reset confirm deletes the dimension at the next start."));
            return 0;
        }
        MinecraftServer server = s.getServer();
        java.util.concurrent.CompletableFuture<List<Place>> f = PLACES.find(name, 8);
        if (!(s.getEntity() instanceof ServerPlayer)) {
            // rcon and the console collect the reply only while the command runs: wait for the lookup here
            try {
                f.get(PlaceLookup.LOOKUP_TIMEOUT.toMillis() + 500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // reported by originPlaceAnswer from the completed (or failed) future below
            }
            if (f.isDone()) {
                List<Place> list = f.isCompletedExceptionally() ? null : f.join();
                Throwable err = list == null ? f.handle((v, e) -> e).join() : null;
                return originPlaceAnswer(s, name, list, err);
            }
        }
        f.whenComplete((list, err) -> server.execute(() -> originPlaceAnswer(s, name, list, err)));
        return 1;
    }

    private static int originPlaceAnswer(CommandSourceStack s, String name, List<Place> list, Throwable err) {
        String fail = null;
        Place best = null;
        if (err != null) {
            fail = PlaceLookup.failure(err);
        } else {
            best = PlaceLookup.best(list.stream().filter(pl -> insideLv95(pl.e(), pl.n())).toList(), name);
            if (best == null) fail = PlaceLookup.notFound(name);
        }
        long e = best == null ? 0 : Math.round(best.e()), n = best == null ? 0 : Math.round(best.n());
        if (fail == null && !SwissRuntime.changeOrigin(e, n)) fail = "The origin got locked meanwhile: chunks were already generated.";
        if (fail != null) {
            SwissWorld.LOGGER.info("origin place {} by {}: {}", name, s.getTextName(), fail);
            s.sendFailure(Component.literal(fail));
            return 0;
        }
        String ok = "Origin set to " + PlaceLookup.label(best) + ", E " + e + " N " + n;
        SwissWorld.LOGGER.info("origin place {} by {}: {}", name, s.getTextName(), ok);
        s.sendSuccess(() -> Component.literal(ok), true);
        return 1;
    }

    private static int prefetch(CommandSourceStack s, int radius) throws CommandSyntaxException {
        SwissCoords c = SwissRuntime.coords();
        double e = c.e0(), n = c.n0();
        if (s.getEntity() instanceof ServerPlayer p && inSwitzerland(p)) {
            e += p.getX();
            n -= p.getZ();
        }
        MinecraftServer server = s.getServer();
        SwissRuntime.sidecar().prefetch(e, n, radius, 0, 0).whenComplete((q, err) -> server.execute(() ->
                s.sendSystemMessage(Component.literal(err == null ? "Prefetch: " + q + " cells queued" : "Prefetch failed: " + rootMessage(err)))));
        return 1;
    }

    private static int reset(CommandSourceStack s) {
        Path marker = s.getServer().getWorldPath(LevelResource.ROOT).resolve(RESET_MARKER);
        try {
            Files.writeString(marker, "delete dimensions/swissworld/switzerland at next start\n");
        } catch (IOException e) {
            SwissWorld.LOGGER.error("Could not write {}", marker, e);
            s.sendFailure(Component.literal("Could not write the reset marker: " + e.getMessage()));
            return 0;
        }
        s.sendSuccess(() -> Component.literal("The Switzerland dimension will be deleted at the next server start "
                + "(origin unlocks). Restart the server to apply."), true);
        return 1;
    }
}
