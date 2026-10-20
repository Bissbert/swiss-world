package ch.bissbert.swissworld.world;

import ch.bissbert.swissworld.SwissRegistries;
import ch.bissbert.swissworld.SwissRuntime;
import ch.bissbert.swissworld.SwissWorld;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;

/**
 * Nobody falls through terrain that is not there yet. Owner: mod-core.
 *
 * <p>A survival or adventure player in Switzerland whose chunk is not real terrain on the server (an empty
 * placeholder, see {@link StallGuard}, or still generating) or not yet on their client is held: the position is
 * pinned, gravity is switched off with a transient attribute modifier (synced, so the client stops falling, and the
 * server's floating kick stays off), fall distance is reset and an action bar says "Terrain still loading…". Once
 * the chunk is real and sent, the modifier is removed and the player is put on the ground if they are below it
 * (highest motion-blocking block that is not leaves), lifted further only if they would be inside blocks. A player
 * who is above the ground is left there and falls, without fall damage (an elytra flight stopped by the hold must not
 * end in a deadly fall) and without the floating kick, until they land, at most {@link #GRACE_TICKS}. Creative and
 * spectator players are left alone.
 *
 * <p>Damage from falling out of the world is cancelled for every player in Switzerland (and suffocation and fall
 * damage while held): below min y there is only
 * missing terrain, never a real void. Checks run every {@link #CHECK_TICKS} ticks per player: a few hash lookups,
 * no block scans (the ground search runs once, on release).
 */
public final class TerrainHold {
    static final int CHECK_TICKS = 4;
    static final int MESSAGE_TICKS = 20;
    /** Pinned players that drift further than this (e.g. a client that ignores the gravity modifier) are put back. */
    static final double MAX_DRIFT = 0.5;
    /** A held player further away than this from where they are held was teleported: hold them there instead. */
    static final double TELEPORTED = 8;
    /** On release, lift at most this many blocks out of blocks the player would be stuck in. */
    static final int MAX_LIFT = 48;
    /** After a release, fall damage stays off until the player lands, at most this long. */
    static final int GRACE_TICKS = 30 * 20;
    static final ResourceLocation MODIFIER_ID = ResourceLocation.fromNamespaceAndPath(SwissWorld.MOD_ID, "terrain_hold");
    private static final AttributeModifier NO_GRAVITY =
            new AttributeModifier(MODIFIER_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final Component MESSAGE = Component.literal("Terrain still loading…");

    private static final Map<UUID, Held> HELD = new HashMap<>();
    /** Released players still in the air (e.g. an elytra flight that was stopped): tick until which they take no fall damage. */
    private static final Map<UUID, Integer> FALLING = new HashMap<>();

    private record Held(ServerPlayer player, Vec3 pos) {}

    private TerrainHold() {}

    public static boolean isHeld(ServerPlayer player) {
        Held h = HELD.get(player.getUUID());
        return h != null && h.player == player;
    }

    public static void clear() {
        HELD.clear();
        FALLING.clear();
    }

    /**
     * Whether a released player has come down: in water, or on the ground at the top of their column ({@code ground}
     * is the first free y). Not just on ground: a held player's client may have reported on ground in mid-air.
     */
    static boolean landed(boolean onGround, boolean inWater, double y, int ground) {
        return inWater || onGround && y <= ground + 1;
    }

    /**
     * No fall damage for a released player until the grace is dropped, which happens after they landed (the landing
     * itself, where vanilla already sees them on the ground, still counts) or when it runs out.
     */
    private static boolean inGrace(ServerPlayer p) {
        Integer until = FALLING.get(p.getUUID());
        return until != null && p.getServer().getTickCount() < until;
    }

    /** Server tick end. */
    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (now % CHECK_TICKS != 0) return;
        for (Iterator<Map.Entry<UUID, Held>> it = HELD.entrySet().iterator(); it.hasNext(); ) {
            Held h = it.next().getValue();
            if (h.player.isRemoved() || h.player.hasDisconnected() || !applies(h.player)) {
                removeModifier(h.player); // died, logged out, changed dimension or game mode
                it.remove();
            }
        }
        for (Iterator<Map.Entry<UUID, Integer>> it = FALLING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || !applies(p) || HELD.containsKey(e.getKey()) || now >= e.getValue()
                    || landed(p.onGround(), p.isInWater(), p.getY(), p.serverLevel().getHeight(
                            Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ()))) {
                it.remove();
            } else {
                resetFloating(p); // vanilla counts any fall as floating: a long one would get them kicked
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!applies(p)) continue;
            ServerLevel level = p.serverLevel();
            Held h = HELD.get(p.getUUID());
            if (h != null && h.player != p) { // respawned: a new player object
                HELD.remove(p.getUUID());
                h = null;
            }
            boolean ready = terrainReady(level, p, p.chunkPosition());
            if (h == null) {
                if (!ready) hold(level, p);
                else if (p.getY() < level.getMinBuildHeight()) putOnGround(level, p); // never happens normally
            } else if (ready) {
                release(level, p);
            } else {
                keep(level, p, h, now);
            }
        }
    }

    /** Players this applies to: survival and adventure players in Switzerland. */
    private static boolean applies(ServerPlayer p) {
        return p.level().dimension() == SwissRegistries.SWITZERLAND && p.isAlive()
                && !p.isCreative() && !p.isSpectator();
    }

    /**
     * Whether the player's chunk is real terrain the client already has: loaded on the server, not a placeholder
     * (or one being filled in), and sent.
     */
    public static boolean terrainReady(ServerLevel level, ServerPlayer p, ChunkPos pos) {
        if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) return false;
        if (!PlaceholderRepair.settled(pos)) return false;
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
        if (holder == null || holder.getChunkToSend() == null) return false;
        return p.getChunkTrackingView().contains(pos) && !p.connection.chunkSender.isPending(pos.toLong());
    }

    private static void hold(ServerLevel level, ServerPlayer p) {
        p.stopRiding();
        if (p.isFallFlying()) p.stopFallFlying();
        Vec3 pos = new Vec3(p.getX(), Math.max(p.getY(), level.getMinBuildHeight() + 1), p.getZ());
        AttributeInstance gravity = p.getAttribute(Attributes.GRAVITY);
        if (gravity != null) gravity.addOrUpdateTransientModifier(NO_GRAVITY);
        HELD.put(p.getUUID(), new Held(p, pos));
        pin(p, pos);
        p.displayClientMessage(MESSAGE, true);
        SwissWorld.LOGGER.info("Holding {} at {} {} {}: terrain of chunk {} is not there yet ({})", p.getName().getString(),
                Math.round(pos.x), Math.round(pos.y), Math.round(pos.z), p.chunkPosition(),
                SwissRuntime.wasAborted(p.chunkPosition()) ? "placeholder" : "loading");
    }

    private static void keep(ServerLevel level, ServerPlayer p, Held h, int now) {
        double d2 = p.position().distanceToSqr(h.pos);
        if (d2 > TELEPORTED * TELEPORTED) { // /tp, /swiss goto: hold them where they are now
            hold(level, p);
            return;
        }
        p.stopRiding();
        p.resetFallDistance();
        resetFloating(p);
        if (d2 > MAX_DRIFT * MAX_DRIFT) pin(p, h.pos);
        else p.setDeltaMovement(Vec3.ZERO);
        if (now % MESSAGE_TICKS == 0) p.displayClientMessage(MESSAGE, true);
    }

    /** Absolute position, so the client's velocity is zeroed too; rotation unchanged. */
    private static void pin(ServerPlayer p, Vec3 pos) {
        p.resetFallDistance();
        p.setDeltaMovement(Vec3.ZERO);
        p.connection.teleport(pos.x, pos.y, pos.z, 0, 0, Set.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT));
    }

    private static void release(ServerLevel level, ServerPlayer p) {
        HELD.remove(p.getUUID());
        removeModifier(p);
        putOnGround(level, p);
        // left in the air (above the ground, or their flight was stopped): no fall damage until they land
        FALLING.put(p.getUUID(), level.getServer().getTickCount() + GRACE_TICKS);
        p.displayClientMessage(Component.empty(), true);
    }

    /** Onto the ground if below it, then out of any blocks; otherwise where they are. */
    private static void putOnGround(ServerLevel level, ServerPlayer p) {
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getBlockX(), p.getBlockZ());
        double y = groundedY(p.getY(), ground, level.getMinBuildHeight());
        AABB box = p.getBoundingBox();
        int lift = 0;
        while (lift < MAX_LIFT && !level.noCollision(p, box.move(0, y - p.getY(), 0))) {
            y = Math.floor(y) + 1;
            lift++;
        }
        p.resetFallDistance();
        if (y != p.getY()) {
            pin(p, new Vec3(p.getX(), y, p.getZ()));
            SwissWorld.LOGGER.info("Put {} on the ground at y {}", p.getName().getString(), Math.round(y));
        } else {
            p.setDeltaMovement(Vec3.ZERO);
        }
    }

    /**
     * Where a released player goes: up to {@code ground} (the first free y above the highest solid block) if below it,
     * otherwise unchanged; never below min y + 1 (a column without terrain).
     */
    static double groundedY(double y, int ground, int minY) {
        return Math.max(Math.max(y, ground), minY + 1);
    }

    private static void removeModifier(ServerPlayer p) {
        AttributeInstance gravity = p.getAttribute(Attributes.GRAVITY);
        if (gravity != null) gravity.removeModifier(MODIFIER_ID);
        resetFloating(p);
    }

    /**
     * Vanilla counts every tick a held player hovers as floating (only the kick is off, through zero gravity);
     * without this reset, the first airborne tick after the release would kick them.
     */
    private static void resetFloating(ServerPlayer p) {
        if (p.connection != null) p.connection.aboveGroundTickCount = 0;
    }

    /** Logout: the modifier is transient (not saved), but the player object may stay around a bit. */
    public static void loggedOut(ServerPlayer p) {
        Held h = HELD.remove(p.getUUID());
        if (h != null) removeModifier(h.player);
        FALLING.remove(p.getUUID());
    }

    /**
     * Below min y in Switzerland there is only missing terrain: no damage from it, ever. A held player also takes no
     * fall damage and does not suffocate when the terrain is filled in around them (until the release lifts them out).
     */
    public static void invulnerabilityCheck(EntityInvulnerabilityCheckEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.level().dimension() != SwissRegistries.SWITZERLAND) return;
        var source = e.getSource();
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)
                || isHeld(p) && (source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.FALL))
                || source.is(DamageTypes.FALL) && inGrace(p)) {
            e.setInvulnerable(true);
        }
    }
}
