package ch.bissbert.swissworld.gen.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Playtest 2026-10-20 00.12.16: stream banks showed bare stone and gravel blocks in the grass. */
class WaterStageTest {
    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void bankOnAMeadowIsGrass() {
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), WaterStage.natural(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), WaterStage.natural(Blocks.PODZOL.defaultBlockState()));
    }

    @Test
    void bankInARockGullyIsRock() {
        assertEquals(Blocks.STONE.defaultBlockState(), WaterStage.natural(Blocks.STONE.defaultBlockState()));
        assertEquals(Blocks.COARSE_DIRT.defaultBlockState(), WaterStage.natural(Blocks.COARSE_DIRT.defaultBlockState()));
    }

    @Test
    void bankOnLooseGroundIsGravelAndCornersNeverFall() {
        var bank = WaterStage.natural(Blocks.SAND.defaultBlockState());
        assertEquals(Blocks.GRAVEL.defaultBlockState(), bank);
        // a step corner stands over water: no falling block there
        assertFalse(WaterStage.rock(bank, true).getBlock() instanceof FallingBlock);
        assertFalse(WaterStage.rock(bank, false).getBlock() instanceof FallingBlock);
        assertEquals(Blocks.DIRT.defaultBlockState(), WaterStage.rock(Blocks.GRASS_BLOCK.defaultBlockState(), false));
    }

    @Test
    void bankBesideAGravelMarginTakesTheMeadowBeyond() {
        // a stream along z at x = 0..1 with a river-gravel margin (SURF 15) at x = 2..3, a meadow beyond and rock on
        // the far side (x < 0)
        ch.bissbert.swissworld.cell.SwdCell c = Cells.flat(600);
        for (int z = -10; z <= 10; z++) {
            for (int x = 0; x <= 1; x++) {
                Cells.surf(c, x, z, 8);
                Cells.water(c, x, z, 600.5, 2);
            }
            for (int x = 2; x <= 3; x++) Cells.surf(c, x, z, 15);
            for (int x = -6; x <= -1; x++) Cells.surf(c, x, z, 4);
        }
        ColumnGrid g = Cells.grid(c, 0, 0);
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), WaterStage.landBeside(g, 2, 0), "east bank");
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), WaterStage.landBeside(g, 3, 0), "outer bank");
        // the nearest land wins: the rock two columns west of the stream's east half, not the meadow three east
        assertEquals(Blocks.STONE.defaultBlockState(), WaterStage.landBeside(g, 1, 0), "step corner");
        assertEquals(Blocks.STONE.defaultBlockState(), WaterStage.landBeside(g, -1, 0), "west bank on rock");
    }
}
