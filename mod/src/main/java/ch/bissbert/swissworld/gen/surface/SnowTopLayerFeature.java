package ch.bissbert.swissworld.gen.surface;

import ch.bissbert.swissworld.SwissWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Vanilla {@code freeze_top_layer} without the freezing: snow layers on cold ground, but water never turns to ice.
 * A frozen river source would drain the falling and flowing water it feeds (see {@link WaterPlan}). Biomes use it as
 * {@code swissworld:snow_top_layer}.
 */
public final class SnowTopLayerFeature extends Feature<NoneFeatureConfiguration> {
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, SwissWorld.MOD_ID);

    static {
        FEATURES.register("snow_top_layer", SnowTopLayerFeature::new);
    }

    public SnowTopLayerFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        BlockPos origin = ctx.origin();
        BlockPos.MutableBlockPos top = new BlockPos.MutableBlockPos(), below = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                int x = origin.getX() + i, z = origin.getZ() + j;
                top.set(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
                below.setWithOffset(top, 0, -1, 0);
                Biome biome = level.getBiome(top).value();
                if (!biome.shouldSnow(level, top)) continue;
                level.setBlock(top, Blocks.SNOW.defaultBlockState(), 2);
                BlockState under = level.getBlockState(below);
                if (under.hasProperty(SnowyDirtBlock.SNOWY)) level.setBlock(below, under.setValue(SnowyDirtBlock.SNOWY, true), 2);
            }
        }
        return true;
    }
}
