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
import net.minecraft.world.level.ChunkPos;
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
    private static final int TIMEOUT_TICKS = 120 * 20, PROGRESS_TICKS = 15 * 20;
    private static final String ATTRIBUTION = "Data ©swisstopo";

    /** Players waiting for their target chunk; main thread only. */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private record Pending(ChunkPos chunk, double x, double y, double z, float yRot, float xRot, int startTick) {}

    private static final PlaceLookup PLACES = new PlaceLookup(SwissRuntime::sidecar, System::currentTimeMillis);

    /** Tab completion for place names; the future completes off-thread, the server never waits for it. */
    private static final SuggestionProvider<CommandSourceStack> PLACE_SUGGESTIONS = (ctx, b) -> {
        String typed = b.getRemaining();
        return PLACES.suggest(typed).thenApply(list -> {
            for (Place p : list) {
                b.suggest(p.name(), Component.literal(PlaceLookup.label(p)));
                if (p.matched() != null && !p.matched().equals(p.name())) b.suggest(p.matched(), Component.literal(PlaceLookup.label(p)));
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
                ? new Pending(new ChunkPos(BlockPos.containing(last.x(), 0, last.z())), last.x(), last.y(), last.z(), last.yRot(), last.xRot(), p.server.getTickCount())
                : new Pending(new ChunkPos(0, 0), 0.5, Double.NaN, 0.5, p.getYRot(), p.getXRot(), p.server.getTickCount());
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
        return travel(p, new Pending(new ChunkPos(BlockPos.containing(x, 0, z)), x, Double.NaN, z, p.getYRot(), p.getXRot(), p.server.getTickCount()));
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
        PLACES.find(query, 4).whenComplete((list, err) -> server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return;
            if (err != null) {
                p.sendSystemMessage(Component.literal(PlaceLookup.failure(err)).withStyle(ChatFormatting.RED));
                return;
            }
            Place best = list.stream().filter(pl -> insideLv95(pl.e(), pl.n())).findFirst().orElse(null);
            if (best == null) {
                p.sendSystemMessage(Component.literal(PlaceLookup.notFound(query)).withStyle(ChatFormatting.RED));
                return;
            }
            p.sendSystemMessage(Component.literal(PlaceLookup.going(best)).withStyle(ChatFormatting.GRAY));
            MutableComponent also = alternatives(list, best);
            if (also != null) p.sendSystemMessage(also);
            travelLv95(p, best.e(), best.n());
        }));
        return 1;
    }

    /** "also: [Zofingen (station)] [...]", each one a click on /swiss goto E N; null when there are none. */
    private static MutableComponent alternatives(List<Place> list, Place best) {
        MutableComponent also = Component.literal("also: ").withStyle(ChatFormatting.GRAY);
        int shown = 0;
        for (Place pl : list) {
            if (pl == best || !insideLv95(pl.e(), pl.n()) || shown == 3) continue;
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
        swiss.getChunkSource().addRegionTicket(ENTER_TICKET, t.chunk(), 1, t.chunk());
        PENDING.put(p.getUUID(), t);
        SwissCoords c = SwissRuntime.coords();
        if (SwissConfig.PREFETCH.get()) {
            SwissRuntime.sidecar().prefetch(c.e0() + t.x(), c.n0() - t.z(), 1000, 0, 0).exceptionally(e -> 0);
        }
        p.sendSystemMessage(Component.literal("Preparing Switzerland… (" + ATTRIBUTION + ")").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /** Server tick: teleport players whose target chunk is ready. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        ServerLevel swiss = server.getLevel(SwissRegistries.SWITZERLAND);
        int now = server.getTickCount();
        for (Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> en = it.next();
            Pending t = en.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            LevelChunk chunk = swiss == null ? null : swiss.getChunkSource().getChunkNow(t.chunk().x, t.chunk().z);
            boolean timeout = now - t.startTick() > TIMEOUT_TICKS;
            if (p == null || chunk != null || timeout || swiss == null) {
                it.remove();
                if (swiss != null) swiss.getChunkSource().removeRegionTicket(ENTER_TICKET, t.chunk(), 1, t.chunk());
            }
            if (p == null || swiss == null) continue;
            if (chunk != null) {
                double y = Double.isNaN(t.y())
                        ? chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(t.x()), (int) Math.floor(t.z())) + 1
                        : t.y();
                boolean arriving = !inSwitzerland(p);
                if (arriving) {
                    p.setData(SwissRegistries.RETURN_POS, new ReturnPos(p.level().dimension(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()));
                }
                p.teleportTo(swiss, t.x(), y, t.z(), t.yRot(), t.xRot());
                p.sendSystemMessage(Component.literal(arriving ? "Welcome to Switzerland. " : "").append(whereText(p)));
            } else if (timeout) {
                p.sendSystemMessage(Component.literal("The terrain there is not ready yet"
                        + (SwissRuntime.cells().sidecarDown() ? " (the data sidecar is not reachable)" : "")
                        + ". Try again in a while.").withStyle(ChatFormatting.RED));
            } else if ((now - t.startTick()) % PROGRESS_TICKS == 0 && now != t.startTick()) {
                CellCache cells = SwissRuntime.cells();
                p.sendSystemMessage(Component.literal("Still building terrain… " + cells.pendingCount() + " cells pending"
                        + (cells.sidecarDown() ? ", sidecar not reachable" : "")).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /** Server stopping: drop pending entries (their tickets go with the level). */
    public static void clear() {
        PENDING.clear();
    }

    private static int back(ServerPlayer p) {
        if (PENDING.remove(p.getUUID()) instanceof Pending t) {
            ServerLevel swiss = p.server.getLevel(SwissRegistries.SWITZERLAND);
            if (swiss != null) swiss.getChunkSource().removeRegionTicket(ENTER_TICKET, t.chunk(), 1, t.chunk());
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
        PLACES.find(name, 1).whenComplete((list, err) -> server.execute(() -> {
            if (err != null) {
                s.sendFailure(Component.literal(PlaceLookup.failure(err)));
                return;
            }
            Place best = list.stream().filter(pl -> insideLv95(pl.e(), pl.n())).findFirst().orElse(null);
            if (best == null) {
                s.sendFailure(Component.literal(PlaceLookup.notFound(name)));
                return;
            }
            long e = Math.round(best.e()), n = Math.round(best.n());
            if (!SwissRuntime.changeOrigin(e, n)) {
                s.sendFailure(Component.literal("The origin got locked meanwhile: chunks were already generated."));
                return;
            }
            s.sendSuccess(() -> Component.literal("Origin set to " + PlaceLookup.label(best) + ", E " + e + " N " + n), true);
        }));
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
