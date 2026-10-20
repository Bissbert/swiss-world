package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.gen.site.SiteWriter;
import ch.bissbert.swissworld.SwissWorld;
import ch.bissbert.swissworld.gen.GenContext;
import ch.bissbert.swissworld.gen.GenStage;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.fml.ModList;

/**
 * Loot containers from the plan's container props: Lootr chests/barrels when Lootr is loaded, else vanilla ones,
 * each with {@code swissworld:chests/<room>} and a seed from position and gid. Owner: mod-content.
 */
public final class LootStage implements GenStage {
    private static volatile Block[] containers;

    @Override
    public void apply(GenContext ctx) {
        CellView view = CellView.of(ctx);
        Put put = new Put(ctx.chunk, SiteWriter.of(ctx, SiteWriter.Stage.LOOT));
        for (BuildingPlan p : Buildings.near(view, put.minX(), put.minZ())) {
            for (BuildingPlan.Prop pr : p.props()) if (pr.kind().container()) place(p, pr, put);
        }
    }

    static void place(BuildingPlan p, BuildingPlan.Prop pr, Put put) {
        if (!put.in(pr.x(), pr.z())) return;
        Block[] blocks = containers();
        boolean chest = pr.kind() == PropKind.CHEST;
        BlockState s = (chest ? blocks[0] : blocks[1]).defaultBlockState();
        s = chest ? Put.with(s, BlockStateProperties.HORIZONTAL_FACING, Put.mc(pr.facing()))
                : Put.with(s, BlockStateProperties.FACING, Direction.UP);
        BlockEntity be = put.entity(pr.x(), pr.y(), pr.z(), s);
        if (be instanceof RandomizableContainer rc) {
            rc.setLootTable(table(pr.room()), Hash.pos(pr.x(), pr.y(), pr.z(), p.gid()));
        }
    }

    public static ResourceKey<LootTable> table(RoomType room) {
        return ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath(SwissWorld.MOD_ID, room.lootPath()));
    }

    /** {chest, barrel}: Lootr's per-player containers if the mod is present (untested in dev), else vanilla. */
    static Block[] containers() {
        Block[] c = containers;
        if (c == null) {
            boolean lootr = ModList.get() != null && ModList.get().isLoaded("lootr");
            c = new Block[]{lootr ? lootr("lootr_chest", Blocks.CHEST) : Blocks.CHEST,
                    lootr ? lootr("lootr_barrel", Blocks.BARREL) : Blocks.BARREL};
            containers = c;
        }
        return c;
    }

    private static Block lootr(String id, Block fallback) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("lootr", id)).orElse(fallback);
    }
}
