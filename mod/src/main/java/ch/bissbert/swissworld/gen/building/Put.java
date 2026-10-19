package ch.bissbert.swissworld.gen.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Block writer for one proto chunk. Positions outside the chunk are dropped (ProtoChunk masks x/z to the chunk, so an
 * unclipped write would land in the wrong column). States with a block entity get one: proto chunks don't create them.
 */
public final class Put {
    private final ChunkAccess chunk;
    private final int x0, z0, minY, maxY;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public Put(ChunkAccess chunk) {
        this.chunk = chunk;
        this.x0 = chunk.getPos().getMinBlockX();
        this.z0 = chunk.getPos().getMinBlockZ();
        this.minY = chunk.getMinBuildHeight();
        this.maxY = chunk.getMaxBuildHeight();
    }

    public int minX() {
        return x0;
    }

    public int minZ() {
        return z0;
    }

    public boolean in(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    public void set(int x, int y, int z, BlockState s) {
        entity(x, y, z, s);
    }

    /** Sets the block and returns its new block entity, or null. */
    public BlockEntity entity(int x, int y, int z, BlockState s) {
        if (!in(x, z) || y <= minY || y >= maxY) return null;
        pos.set(x, y, z);
        BlockState old = chunk.setBlockState(pos, s, false);
        if (old != null && old.hasBlockEntity()) chunk.removeBlockEntity(pos);
        if (s.hasBlockEntity() && s.getBlock() instanceof EntityBlock eb) {
            BlockEntity be = eb.newBlockEntity(pos.immutable(), s);
            if (be != null) chunk.setBlockEntity(be);
            return be;
        }
        return null;
    }

    public static Direction mc(Dir d) {
        return switch (d) {
            case N -> Direction.NORTH;
            case E -> Direction.EAST;
            case S -> Direction.SOUTH;
            case W -> Direction.WEST;
        };
    }

    /** {@code s.setValue(p, v)} if the state has the property (palette blocks vary). */
    public static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, T v) {
        return s.hasProperty(p) ? s.setValue(p, v) : s;
    }
}
