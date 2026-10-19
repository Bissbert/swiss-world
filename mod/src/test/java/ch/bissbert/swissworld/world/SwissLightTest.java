package ch.bissbert.swissworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SwissLightTest {
    private static CrudeIncrementalIntIdentityHashBiMap<Holder<Biome>> biomes;
    private static Holder<Biome> plains;

    @BeforeAll
    static void boot() {
        Bootstrap.bootStrap();
        plains = Holder.direct(null);
        biomes = CrudeIncrementalIntIdentityHashBiMap.create(1);
        biomes.add(plains);
    }

    private static LevelChunkSection section(BlockState fill) {
        return new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, fill, PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomes, plains, PalettedContainer.Strategy.SECTION_BIOMES));
    }

    private static LevelChunkSection stone() {
        return section(Blocks.STONE.defaultBlockState());
    }

    @Test
    void opaqueStates() {
        assertTrue(SwissLight.opaque(Blocks.STONE.defaultBlockState()));
        assertTrue(SwissLight.opaque(Blocks.DIRT.defaultBlockState()));
        assertFalse(SwissLight.opaque(Blocks.AIR.defaultBlockState()));
        assertFalse(SwissLight.opaque(Blocks.WATER.defaultBlockState()));
        assertFalse(SwissLight.opaque(Blocks.GLASS.defaultBlockState()));
        assertFalse(SwissLight.opaque(Blocks.ICE.defaultBlockState()));
        assertFalse(SwissLight.opaque(Blocks.OAK_LEAVES.defaultBlockState()));
        assertFalse(SwissLight.opaque(Blocks.STONE_SLAB.defaultBlockState()));
        assertFalse(SwissLight.opaque(Blocks.GLOWSTONE.defaultBlockState()), "emits light");
        assertFalse(SwissLight.opaque(Blocks.MAGMA_BLOCK.defaultBlockState()), "emits light");
    }

    @Test
    void buriedRockNeedsAnOpaqueLid() {
        // bottom to top: stone, stone, stone (top rock section), air
        LevelChunkSection[] col = {stone(), stone(), stone(), section(Blocks.AIR.defaultBlockState())};
        assertTrue(SwissLight.lightEmpty(col, 0));
        assertTrue(SwissLight.lightEmpty(col, 1));
        assertFalse(SwissLight.lightEmpty(col, 2), "the top rock section is the lid and stays stored");
        assertTrue(SwissLight.lightEmpty(col, 3), "pure air is empty as in vanilla");
        // nothing above the top of the world: an all-rock column keeps its top section
        LevelChunkSection[] rock = {stone(), stone()};
        assertTrue(SwissLight.lightEmpty(rock, 0));
        assertFalse(SwissLight.lightEmpty(rock, 1));
    }

    @Test
    void anyOpeningKeepsTheSectionAndItsFloorStored() {
        LevelChunkSection[] col = {stone(), stone(), stone(), stone()};
        assertEquals(3, SwissLight.statusBits(col, 2));
        col[2].setBlockState(4, 4, 4, Blocks.AIR.defaultBlockState());
        assertFalse(SwissLight.lightEmpty(col, 2), "a cave pocket");
        assertFalse(SwissLight.lightEmpty(col, 1), "its lid is no longer opaque");
        assertTrue(SwissLight.lightEmpty(col, 0));
        assertEquals(0, SwissLight.statusBits(col, 2));
        col[2].setBlockState(4, 4, 4, Blocks.STONE.defaultBlockState());
        // the palette still lists air, so the section stays stored: conservative, never wrong
        assertEquals(0, SwissLight.statusBits(col, 2));
    }

    @Test
    void lightSourcesAndMixedOpaqueStates() {
        LevelChunkSection[] col = {stone(), stone(), stone()};
        col[1].setBlockState(0, 0, 0, Blocks.DIRT.defaultBlockState());
        assertTrue(SwissLight.lightEmpty(col, 0), "stone and dirt are both opaque");
        assertTrue(SwissLight.lightEmpty(col, 1));
        col[1].setBlockState(1, 0, 0, Blocks.GLOWSTONE.defaultBlockState());
        assertFalse(SwissLight.lightEmpty(col, 1), "a light source needs block light storage");
        assertFalse(SwissLight.lightEmpty(col, 0));
    }

    @Test
    void indexIsByIdentity() {
        LevelChunkSection a = stone(), b = stone();
        assertEquals(1, SwissLight.indexOf(new LevelChunkSection[] {a, b}, b));
        assertEquals(-1, SwissLight.indexOf(new LevelChunkSection[] {a}, b));
    }
}
