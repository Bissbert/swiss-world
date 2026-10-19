package ch.bissbert.swissworld;

import ch.bissbert.swissworld.gen.SwissBiomeSource;
import ch.bissbert.swissworld.gen.SwissChunkGenerator;
import ch.bissbert.swissworld.world.ReturnPos;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class SwissRegistries {
    public static final ResourceKey<Level> SWITZERLAND =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(SwissWorld.MOD_ID, "switzerland"));

    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, SwissWorld.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, SwissWorld.MOD_ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, SwissWorld.MOD_ID);

    /** Where {@code /swiss} brings a player back to. */
    public static final Supplier<AttachmentType<ReturnPos>> RETURN_POS = ATTACHMENT_TYPES.register("return_pos",
            () -> AttachmentType.builder(() -> ReturnPos.NONE).serialize(ReturnPos.CODEC, ReturnPos::present).copyOnDeath().build());
    /** Where a player left Switzerland; {@code /swiss} brings them back there. */
    public static final Supplier<AttachmentType<ReturnPos>> LAST_SWISS_POS = ATTACHMENT_TYPES.register("last_swiss_pos",
            () -> AttachmentType.builder(() -> ReturnPos.NONE).serialize(ReturnPos.CODEC, ReturnPos::present).copyOnDeath().build());

    static {
        CHUNK_GENERATORS.register("swiss", () -> SwissChunkGenerator.CODEC);
        BIOME_SOURCES.register("swiss", () -> SwissBiomeSource.CODEC);
    }

    private SwissRegistries() {}

    static void register(IEventBus modBus) {
        CHUNK_GENERATORS.register(modBus);
        BIOME_SOURCES.register(modBus);
        ATTACHMENT_TYPES.register(modBus);
    }
}
