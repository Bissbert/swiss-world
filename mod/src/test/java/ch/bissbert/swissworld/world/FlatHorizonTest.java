package ch.bissbert.swissworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class FlatHorizonTest {
    // same key as SWISS (that class needs the NeoForge bootstrap)
    private static final ResourceKey<Level> SWISS =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("swissworld", "switzerland"));

    private static CommonPlayerSpawnInfo info(ResourceKey<Level> dim) {
        return new CommonPlayerSpawnInfo(null, dim, 1234L, GameType.SPECTATOR, GameType.CREATIVE, false, false,
                Optional.empty(), 7);
    }

    @Test
    void switzerlandIsSentAsFlatWithEverythingElseKept() {
        CommonPlayerSpawnInfo in = info(SWISS), out = FlatHorizon.forClient(in, SWISS);
        assertTrue(out.isFlat());
        assertEquals(new CommonPlayerSpawnInfo(null, SWISS, 1234L, GameType.SPECTATOR,
                GameType.CREATIVE, false, true, Optional.empty(), 7), out);
    }

    @Test
    void otherDimensionsAreUnchanged() {
        for (ResourceKey<Level> dim : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            CommonPlayerSpawnInfo in = info(dim);
            assertSame(in, FlatHorizon.forClient(in, SWISS));
            assertFalse(FlatHorizon.forClient(in, SWISS).isFlat());
        }
    }
}
