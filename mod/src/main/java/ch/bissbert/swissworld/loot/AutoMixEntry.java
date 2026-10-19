package ch.bissbert.swissworld.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryType;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.NumberProviders;

/**
 * Loot entry {@code swissworld:auto_mix}: {@code count} stacks drawn from the auto-mixed pool of {@code room}
 * (see {@link LootPools}). Shipped tables consist of just this entry, so a datapack table of the same id replaces it.
 */
public class AutoMixEntry extends LootPoolSingletonContainer {
    record Settings(String room, NumberProvider count) {
        static final MapCodec<Settings> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("room").forGetter(Settings::room),
                NumberProviders.CODEC.optionalFieldOf("count", ConstantValue.exactly(5)).forGetter(Settings::count)
        ).apply(i, Settings::new));
    }

    public static final MapCodec<AutoMixEntry> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Settings.CODEC.forGetter(e -> e.settings)).and(singletonFields(i)).apply(i, AutoMixEntry::new));
    public static final LootPoolEntryType TYPE = new LootPoolEntryType(CODEC);

    private final Settings settings;

    private AutoMixEntry(Settings settings, int weight, int quality, List<LootItemCondition> conditions,
                         List<LootItemFunction> functions) {
        super(weight, quality, conditions, functions);
        this.settings = settings;
    }

    @Override
    public LootPoolEntryType getType() {
        return TYPE;
    }

    @Override
    public void createItemStack(Consumer<ItemStack> out, LootContext ctx) {
        LootPools.Pool pool = LootPools.get(ctx.getLevel().getServer(), settings.room());
        if (pool == null) return;
        int n = settings.count().getInt(ctx);
        for (int i = 0; i < n; i++) out.accept(pool.draw(ctx.getRandom()));
    }
}
