package ch.bissbert.swissworld.world;

import ch.bissbert.swissworld.SwissRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Which sections of a Switzerland chunk get light storage. Owner: mod-core. Wired in by the light mixins.
 *
 * <p>Vanilla gives every section that is not pure air (and its 26 neighbours) a sky and a block light entry. In a
 * 4064-tall dimension a mountain column is solid rock from y -2032 up to the surface: ~170 sections per column, and
 * the light worker clones the whole section map (2^20 slots, 12 MB of humongous arrays per layer) on every batch.
 * Here a section also counts as light-empty when it is fully opaque and so is the section above it: no light can
 * enter it, so not storing it reads the same 0. The "lid" (the section above must be opaque too) keeps the topmost
 * rock section of every column stored, so the sky engine's walk up a column always meets a stored 0 before it could
 * reach "above the data" (15). Sections next to a stored one keep their storage through vanilla's neighbour count.
 */
public final class SwissLight {
    private SwissLight() {}

    /** True if this level (a ServerLevel or ClientLevel, possibly as a height accessor) is Switzerland. */
    public static boolean applies(LevelHeightAccessor level) {
        return level instanceof Level l && l.dimension() == SwissRegistries.SWITZERLAND;
    }

    /** Light-empty status of {@code sections[index]}: pure air, or opaque under an opaque lid. */
    public static boolean lightEmpty(LevelChunkSection[] sections, int index) {
        LevelChunkSection s = sections[index];
        return s.hasOnlyAir() || opaque(s) && index + 1 < sections.length && opaque(sections[index + 1]);
    }

    /** Index of {@code section} in {@code sections} by identity, or -1. */
    public static int indexOf(LevelChunkSection[] sections, LevelChunkSection section) {
        for (int i = 0; i < sections.length; i++) if (sections[i] == section) return i;
        return -1;
    }

    /** Every state the palette may hold blocks light completely and emits none (the global palette says "maybe"). */
    static boolean opaque(LevelChunkSection s) {
        return !s.hasOnlyAir() && !s.maybeHas(state -> !opaque(state));
    }

    static boolean opaque(BlockState state) {
        return state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 15
                && state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == 0;
    }

    /** Status bits of section {@code index} (bit 0) and the one below it (bit 1), whose lid it is. */
    public static int statusBits(LevelChunkSection[] sections, int index) {
        return (lightEmpty(sections, index) ? 1 : 0) | (index > 0 && lightEmpty(sections, index - 1) ? 2 : 0);
    }

    /** Tells the light engine which of {@code section} and the one below changed status ({@link #statusBits}). */
    public static void publish(LevelLightEngine engine, SectionPos section, int before, int after) {
        if (((before ^ after) & 1) != 0) engine.updateSectionStatus(section, (after & 1) != 0);
        if (((before ^ after) & 2) != 0) engine.updateSectionStatus(section.offset(0, -1, 0), (after & 2) != 0);
    }
}
