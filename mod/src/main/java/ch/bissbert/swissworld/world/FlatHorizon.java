package ch.bissbert.swissworld.world;

import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Tells clients that Switzerland is "flat", so they draw a normal sky. Owner: mod-core.
 *
 * <p>A vanilla client draws the lower sky hemisphere black below its horizon height (y 63 unless the level is flat,
 * then the level's min build height) and darkens fog by {@code (y - minBuildHeight) * clearColorScale}, which is
 * 1/32 unless flat. Our terrain lies at y -2000..+1000, so the whole horizon was black. Both values come only from
 * the {@code isFlat} flag in {@link CommonPlayerSpawnInfo} (join, respawn, dimension change), so only that packet
 * field changes here; {@code ServerLevel.isFlat()} itself stays vanilla (server-side its only reader is
 * {@code ServerPlayer.createCommonSpawnInfo}). {@code ServerPlayerMixin} applies this.
 */
public final class FlatHorizon {
    private FlatHorizon() {}

    /** {@code info} with isFlat set if it is for {@code flatDimension}, else {@code info} itself. */
    public static CommonPlayerSpawnInfo forClient(CommonPlayerSpawnInfo info, ResourceKey<Level> flatDimension) {
        if (info.isFlat() || !flatDimension.equals(info.dimension())) return info;
        return new CommonPlayerSpawnInfo(info.dimensionType(), info.dimension(), info.seed(), info.gameType(),
                info.previousGameType(), info.isDebug(), true, info.lastDeathLocation(), info.portalCooldown());
    }
}
