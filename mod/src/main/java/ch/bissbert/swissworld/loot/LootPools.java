package ch.bissbert.swissworld.loot;

import ch.bissbert.swissworld.SwissWorld;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import org.slf4j.Logger;

/**
 * Room-type item pools mixed from every loaded {@code *:chests/*} table (sampled, weighted by how well the table's
 * path matches the room) plus item tags, minus the denylist ({@code #swissworld:loot_denylist} and code defaults).
 * Built lazily per set of reloadable registries; {@link #invalidate} drops them on tag updates.
 */
public final class LootPools {
    private static final Logger LOG = LogUtils.getLogger();
    public static final TagKey<Item> DENYLIST = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(SwissWorld.MOD_ID, "loot_denylist"));
    static final int ROLLS = 40;
    /** Share of the pool weight for sampled tables; the rest goes to tag items. */
    static final double TABLE_SHARE = 0.75, OFF_ROOM = 0.05;
    private static final ThreadLocal<Boolean> BUILDING = ThreadLocal.withInitial(() -> false);
    private static ReloadableServerRegistries.Holder builtFor;
    private static Map<String, Pool> pools = Map.of();

    /** Room id, keywords matched against table paths, and item tags mixed in. */
    record Room(String id, List<String> keywords, List<String> tags) {
        static Room of(String id, String keywords, String tags) {
            return new Room(id, List.of(keywords.split(",")), List.of(tags.split(",")));
        }
    }

    static final List<Room> ROOMS = List.of(
            Room.of("residential_kitchen", "butcher,house,food,supply,igloo,kitchen,pantry", "c:foods,c:crops,c:seeds"),
            Room.of("residential_living", "house,library,mansion,bonus,living", "minecraft:candles,c:dyes,minecraft:bookshelf_books"),
            Room.of("residential_bedroom", "house,mansion,igloo,bedroom", "minecraft:wool,minecraft:candles,minecraft:beds"),
            Room.of("shop", "armorer,weaponsmith,toolsmith,cartographer,fletcher,tannery,shepherd,shop,market,trade",
                    "c:foods,c:dyes,c:ingots"),
            Room.of("office", "cartographer,library,map,office", "minecraft:bookshelf_books,c:dyes"),
            Room.of("workshop", "toolsmith,mason,armorer,weaponsmith,smith,mineshaft,workshop,forge",
                    "c:tools,c:ingots,c:nuggets,c:raw_materials,c:dusts"),
            Room.of("hotel", "house,mansion,igloo,supply,hotel", "c:foods,minecraft:wool,minecraft:candles"),
            Room.of("church", "temple,library,ancient,church,chapel", "minecraft:candles,minecraft:bookshelf_books"),
            Room.of("barn", "shepherd,tannery,butcher,farm,barn,supply,outpost", "c:crops,c:seeds,minecraft:wool"),
            Room.of("alpine_hut", "igloo,supply,house,outpost,hut,cabin", "c:foods,c:tools"),
            Room.of("storage", "supply,mineshaft,dungeon,mansion,outpost,storage,warehouse",
                    "c:ingots,c:raw_materials,c:crops"));

    /** Items no room pool may contain, besides the tag. */
    static final Set<String> DENIED = Set.of("command_block", "chain_command_block", "repeating_command_block",
            "command_block_minecart", "structure_block", "structure_void", "jigsaw", "debug_stick", "barrier", "light",
            "knowledge_book", "bedrock", "spawner", "trial_spawner", "vault", "end_portal_frame", "reinforced_deepslate",
            "petrified_oak_slab", "player_head", "air");

    /** Weighted item samples; a draw copies the sample with a random count up to its observed maximum. */
    public record Pool(List<ItemStack> samples, int[] max, double[] cumulative) {
        public ItemStack draw(RandomSource r) {
            double u = r.nextDouble() * cumulative[cumulative.length - 1];
            int lo = 0, hi = cumulative.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cumulative[mid] <= u) lo = mid + 1;
                else hi = mid;
            }
            return samples.get(lo).copyWithCount(1 + r.nextInt(max[lo]));
        }

        public int size() {
            return samples.size();
        }
    }

    private LootPools() {
    }

    public static synchronized void invalidate() {
        builtFor = null;
        pools = Map.of();
    }

    /** The pool for a room id, or null while building (nested rolls) or if unknown/empty. */
    public static synchronized Pool get(MinecraftServer server, String room) {
        if (server == null || BUILDING.get()) return null;
        ReloadableServerRegistries.Holder regs = server.reloadableRegistries();
        if (regs != builtFor) {
            BUILDING.set(true);
            try {
                pools = build(server, regs);
            } finally {
                BUILDING.set(false);
            }
            builtFor = regs;
        }
        return pools.get(room);
    }

    static boolean denied(Item item) {
        if (item == Items.AIR) return true;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        String p = id.getPath();
        return DENIED.contains(p) || p.endsWith("_spawn_egg") || p.startsWith("structure_")
                || item.builtInRegistryHolder().is(DENYLIST);
    }

    static Map<String, Pool> build(MinecraftServer server, ReloadableServerRegistries.Holder regs) {
        // per table: item key -> (frequency per roll, sample stack, max count)
        Map<ResourceLocation, Map<ResourceLocation, Sample>> tables = new LinkedHashMap<>();
        LootParams params = new LootParams.Builder(server.overworld()).create(LootContextParamSets.EMPTY);
        RandomSource rnd = RandomSource.create(0x5155L);
        for (ResourceLocation id : regs.getKeys(Registries.LOOT_TABLE)) {
            if (!id.getPath().startsWith("chests/") || id.getNamespace().equals(SwissWorld.MOD_ID)) continue;
            LootTable t = regs.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
            Map<ResourceLocation, Sample> seen = new HashMap<>();
            try {
                for (int i = 0; i < ROLLS; i++) {
                    for (ItemStack s : t.getRandomItems(params, rnd.nextLong())) {
                        if (s.isEmpty() || denied(s.getItem())) continue;
                        seen.computeIfAbsent(BuiltInRegistries.ITEM.getKey(s.getItem()), k -> new Sample(s.copy()))
                                .add(s.getCount());
                    }
                }
            } catch (RuntimeException e) {
                LOG.debug("Skipping loot table {} for auto-mix: {}", id, e.toString());
                continue;
            }
            if (!seen.isEmpty()) tables.put(id, seen);
        }
        Map<String, Pool> out = new HashMap<>();
        for (Room room : ROOMS) out.put(room.id(), pool(room, tables));
        LOG.info("Auto-mixed loot pools from {} chest tables: {}", tables.size(),
                out.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size()).sorted().toList());
        return out;
    }

    static Pool pool(Room room, Map<ResourceLocation, Map<ResourceLocation, Sample>> tables) {
        // item -> {table weight, tag weight, max count}
        Map<ResourceLocation, double[]> w = new LinkedHashMap<>();
        Map<ResourceLocation, ItemStack> stack = new HashMap<>();
        for (var t : tables.entrySet()) {
            double aff = room.keywords().stream().anyMatch(t.getKey().getPath()::contains) ? 1 : OFF_ROOM;
            for (var s : t.getValue().entrySet()) {
                double[] e = w.computeIfAbsent(s.getKey(), k -> new double[3]);
                e[0] += aff * s.getValue().count / ROLLS;
                e[2] = Math.max(e[2], s.getValue().max);
                stack.putIfAbsent(s.getKey(), s.getValue().stack);
            }
        }
        for (String tag : room.tags()) {
            var set = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag)));
            if (set.isEmpty()) continue;
            List<Holder<Item>> items = set.get().stream().filter(h -> !denied(h.value())).toList();
            for (Holder<Item> h : items) {
                ResourceLocation k = BuiltInRegistries.ITEM.getKey(h.value());
                double[] e = w.computeIfAbsent(k, x -> new double[3]);
                e[1] += 1.0 / items.size();
                e[2] = Math.max(e[2], Math.max(1, Math.min(8, h.value().getDefaultMaxStackSize()) / 2));
                stack.putIfAbsent(k, new ItemStack(h));
            }
        }
        double tableSum = w.values().stream().mapToDouble(e -> e[0]).sum(), tagSum = w.values().stream().mapToDouble(e -> e[1]).sum();
        double share = tagSum == 0 ? 1 : tableSum == 0 ? 0 : TABLE_SHARE;
        List<ItemStack> samples = new ArrayList<>();
        List<Integer> max = new ArrayList<>();
        List<Double> cum = new ArrayList<>();
        double acc = 0;
        for (var e : w.entrySet()) {
            double[] v = e.getValue();
            double weight = (tableSum > 0 ? v[0] / tableSum * share : 0) + (tagSum > 0 ? v[1] / tagSum * (1 - share) : 0);
            if (weight <= 0) continue;
            samples.add(stack.get(e.getKey()));
            max.add(Math.max(1, (int) v[2]));
            cum.add(acc += weight);
        }
        return samples.isEmpty() ? null : new Pool(List.copyOf(samples), max.stream().mapToInt(i -> i).toArray(),
                cum.stream().mapToDouble(d -> d).toArray());
    }

    static final class Sample {
        final ItemStack stack;
        int count, max;

        Sample(ItemStack stack) {
            this.stack = stack.copyWithCount(1);
        }

        void add(int n) {
            count++;
            max = Math.max(max, n);
        }
    }
}
