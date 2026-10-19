package ch.bissbert.swissworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StallGuardTest {
    private static StackTraceElement f(String cls, String method) {
        return new StackTraceElement(cls, method, null, 1);
    }

    private static final StackTraceElement GET_CHUNK = f("net.minecraft.server.level.ServerChunkCache", "getChunk");
    private static final StackTraceElement PARK = f("java.util.concurrent.locks.LockSupport", "park");

    @Test
    void teleportIntoPendingChunkIsAnEntityWait() {
        StackTraceElement[] stack = {PARK, f("net.minecraft.server.level.ServerChunkCache", "managedBlock"), GET_CHUNK,
                f("net.minecraft.world.level.Level", "getChunk"), f("net.minecraft.world.entity.Entity", "setPosRaw"),
                f("net.minecraft.world.entity.Entity", "absMoveTo"),
                f("net.minecraft.server.commands.TeleportCommand", "performTeleport")};
        assertEquals(StallGuard.Wait.ENTITY_CHUNK, StallGuard.classify(stack));
    }

    @Test
    void playerTickNearPendingChunkIsAnEntityWait() {
        // after a teleport into another dimension the player's own tick reads the fluid at its feet
        StackTraceElement[] stack = {PARK, GET_CHUNK, f("net.minecraft.world.level.Level", "getChunkAt"),
                f("net.minecraft.world.level.Level", "getFluidState"),
                f("net.minecraft.world.entity.Entity", "updateFluidHeightAndDoFluidPushing"),
                f("net.minecraft.server.level.ServerPlayer", "doTick"),
                f("net.minecraft.server.network.ServerGamePacketListenerImpl", "tick")};
        assertEquals(StallGuard.Wait.ENTITY_CHUNK, StallGuard.classify(stack));
    }

    @Test
    void forceloadIsAPlainChunkWait() {
        StackTraceElement[] stack = {PARK, GET_CHUNK, f("net.minecraft.server.level.ServerLevel", "setChunkForced"),
                f("net.minecraft.server.commands.ForceLoadCommand", "changeForceLoad")};
        assertEquals(StallGuard.Wait.CHUNK, StallGuard.classify(stack));
    }

    @Test
    void setPosRawAboveTheChunkWaitDoesNotCountWithoutIt() {
        // an entity move that is not waiting for a chunk at all
        StackTraceElement[] stack = {f("net.minecraft.world.entity.Entity", "setPosRaw"), f("x.Y", "tick")};
        assertEquals(StallGuard.Wait.NONE, StallGuard.classify(stack));
        assertEquals(StallGuard.Wait.NONE, StallGuard.classify(new StackTraceElement[0]));
    }

    @Test
    void limits() {
        assertEquals(0, StallGuard.limitMs(StallGuard.Wait.NONE, 20_000, 2_000));
        assertEquals(20_000, StallGuard.limitMs(StallGuard.Wait.CHUNK, 20_000, 2_000));
        assertEquals(2_000, StallGuard.limitMs(StallGuard.Wait.ENTITY_CHUNK, 20_000, 2_000));
        // playerStallAbortSeconds = 0: fall back to stallAbortSeconds
        assertEquals(20_000, StallGuard.limitMs(StallGuard.Wait.ENTITY_CHUNK, 20_000, 0));
        // both off
        assertEquals(0, StallGuard.limitMs(StallGuard.Wait.ENTITY_CHUNK, 0, 0));
    }
}
