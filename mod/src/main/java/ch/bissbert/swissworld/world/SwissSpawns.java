package ch.bissbert.swissworld.world;

import ch.bissbert.swissworld.SwissRegistries;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.FluidTags;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

/**
 * Water-animal spawn rule for {@code swissworld:switzerland}. Vanilla's
 * {@code WaterAnimal.checkSurfaceWaterAnimalSpawnRules} compares y with {@code level.getSeaLevel()}. For a ServerLevel
 * that is the hard-coded 63 (the chunk generator's value is not consulted), so every lake at y about -1436 is rejected.
 * We OR in a rule that only asks for water whose surface is at most {@link #MAX_DEPTH} blocks above. Other dimensions
 * are untouched (the predicate returns false there, so the vanilla rule decides).
 */
public final class SwissSpawns {
    /** Same band as vanilla (sea level - 13 .. sea level), measured from the local water surface. */
    public static final int MAX_DEPTH = 13;

    private SwissSpawns() {}

    public static void register(RegisterSpawnPlacementsEvent e) {
        e.register(EntityType.COD, SwissSpawns::check, RegisterSpawnPlacementsEvent.Operation.OR);
        e.register(EntityType.SALMON, SwissSpawns::check, RegisterSpawnPlacementsEvent.Operation.OR);
        e.register(EntityType.SQUID, SwissSpawns::check, RegisterSpawnPlacementsEvent.Operation.OR);
    }

    private static boolean check(EntityType<?> type, ServerLevelAccessor level, MobSpawnType spawn, BlockPos pos,
            net.minecraft.util.RandomSource random) {
        if (level.getLevel().dimension() != SwissRegistries.SWITZERLAND) return false;
        return inWater(
                dy -> level.getBlockState(pos.above(dy)).is(Blocks.WATER),
                level.getFluidState(pos.below()).is(FluidTags.WATER));
    }

    /**
     * @param waterAbove water at {@code pos + dy} (dy >= 1)
     * @param waterBelow the fluid under the spawn position is water
     * @return true when there is water below and above, and the water surface (first non-water block above) is at
     *         most {@link #MAX_DEPTH} blocks over the position
     */
    public static boolean inWater(IntPredicate waterAbove, boolean waterBelow) {
        if (!waterBelow || !waterAbove.test(1)) return false;
        for (int dy = 2; dy <= MAX_DEPTH; dy++) {
            if (!waterAbove.test(dy)) return true;
        }
        return false;
    }
}
