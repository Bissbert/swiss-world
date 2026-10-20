package ch.bissbert.swissworld.gen.ground;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** The blocks a ground stage reads and writes: the chunk in the game, a plain map in tests and previews. */
public interface BlockAccess {
    BlockState get(int x, int y, int z);

    void set(int x, int y, int z, BlockState state);

    static BlockAccess of(ChunkAccess chunk) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return new BlockAccess() {
            @Override
            public BlockState get(int x, int y, int z) {
                return chunk.getBlockState(pos.set(x, y, z));
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                chunk.setBlockState(pos.set(x, y, z), state, false);
            }
        };
    }
}
