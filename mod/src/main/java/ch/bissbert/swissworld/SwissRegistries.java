package ch.bissbert.swissworld;

import ch.bissbert.swissworld.gen.SwissBiomeSource;
import ch.bissbert.swissworld.gen.SwissChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class SwissRegistries {
    public static final ResourceKey<Level> SWITZERLAND =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(SwissWorld.MOD_ID, "switzerland"));

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, SwissWorld.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, SwissWorld.MOD_ID);

    static {
        CHUNK_GENERATORS.register("swiss", () -> SwissChunkGenerator.CODEC);
        BIOME_SOURCES.register("swiss", () -> SwissBiomeSource.CODEC);
    }

    private SwissRegistries() {}

    static void register(IEventBus modBus) {
        CHUNK_GENERATORS.register(modBus);
        BIOME_SOURCES.register(modBus);
    }
}
