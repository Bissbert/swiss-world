package ch.bissbert.swissworld.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Where a player was before {@code /swiss}; a player attachment that survives death. Owner: mod-core. */
public record ReturnPos(ResourceKey<Level> dimension, double x, double y, double z, float yRot, float xRot) {
    public static final ReturnPos NONE = new ReturnPos(Level.OVERWORLD, 0, Double.NaN, 0, 0, 0);

    public static final Codec<ReturnPos> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ReturnPos::dimension),
            Codec.DOUBLE.fieldOf("x").forGetter(ReturnPos::x),
            Codec.DOUBLE.fieldOf("y").forGetter(ReturnPos::y),
            Codec.DOUBLE.fieldOf("z").forGetter(ReturnPos::z),
            Codec.FLOAT.fieldOf("yRot").forGetter(ReturnPos::yRot),
            Codec.FLOAT.fieldOf("xRot").forGetter(ReturnPos::xRot)
    ).apply(i, ReturnPos::new));

    public boolean present() {
        return !Double.isNaN(y);
    }
}
