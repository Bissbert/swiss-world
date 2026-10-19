package ch.bissbert.swissworld;

import ch.bissbert.swissworld.cell.SwissCoords;
import ch.bissbert.swissworld.command.SwissCommands;
import ch.bissbert.swissworld.net.PlayerPrefetch;
import ch.bissbert.swissworld.world.AbortedChunks;
import ch.bissbert.swissworld.world.StallGuard;
import ch.bissbert.swissworld.world.SwissOrigin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game-bus wiring: runtime lifecycle, origin, stall guard, shutdown downgrade, commands, ticks. Owner: mod-core. */
final class SwissEvents {
    private SwissEvents() {}

    static void register() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(SwissEvents::aboutToStart);
        bus.addListener(SwissEvents::levelLoad);
        bus.addListener(SwissEvents::chunkSave);
        bus.addListener((ServerStoppingEvent e) -> {
            SwissCommands.clear();
            SwissRuntime.stopping();
        });
        bus.addListener((ServerStoppedEvent e) -> {
            StallGuard.stop();
            SwissRuntime.stopped();
        });
        bus.addListener((RegisterCommandsEvent e) -> SwissCommands.register(e.getDispatcher()));
        bus.addListener((ServerTickEvent.Post e) -> {
            SwissCommands.tick(e.getServer());
            PlayerPrefetch.tick(e.getServer());
        });
    }

    /** Config is loaded, levels are not yet: apply a pending reset, then start the cell cache. */
    private static void aboutToStart(ServerAboutToStartEvent e) {
        MinecraftServer server = e.getServer();
        Path world = server.getWorldPath(LevelResource.ROOT);
        Path marker = world.resolve(SwissCommands.RESET_MARKER);
        if (Files.exists(marker)) {
            Path dim = DimensionType.getStorageFolder(SwissRegistries.SWITZERLAND, world);
            SwissWorld.LOGGER.warn("Reset requested: deleting {}", dim);
            try {
                deleteTree(dim);
                Files.delete(marker);
            } catch (IOException ex) {
                SwissWorld.LOGGER.error("Reset failed; delete {} by hand", dim, ex);
            }
        }
        SwissRuntime.start();
        StallGuard.start(server);
    }

    /** Before any chunk of the dimension can generate (forced chunks generate during prepareLevels). */
    private static void levelLoad(LevelEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != SwissRegistries.SWITZERLAND) return;
        SwissCoords initial = new SwissCoords(SwissConfig.ORIGIN_E.get(), SwissConfig.ORIGIN_N.get());
        SwissOrigin origin = level.getDataStorage().computeIfAbsent(SwissOrigin.factory(initial), SwissOrigin.NAME);
        SwissRuntime.setOrigin(origin);
        SwissCoords c = origin.coords();
        SwissWorld.LOGGER.info("Swiss World origin E {} N {} ({})", c.e0(), c.n0(), origin.locked() ? "locked" : "not locked yet");
        if (SwissConfig.PREFETCH.get()) {
            SwissRuntime.sidecar().prefetch(c.e0(), c.n0(), SwissConfig.STARTUP_RADIUS.get(), 0, 0)
                    .exceptionally(err -> 0);
        }
    }

    private static void chunkSave(ChunkDataEvent.Save e) {
        if (e.getLevel() instanceof ServerLevel level && level.dimension() == SwissRegistries.SWITZERLAND
                && SwissRuntime.wasAborted(e.getChunk().getPos())) {
            AbortedChunks.downgrade(e.getData());
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
