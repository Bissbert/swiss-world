package ch.bissbert.swissworld.loot;

import ch.bissbert.swissworld.SwissWorld;
import ch.bissbert.swissworld.gen.building.LootStage;
import ch.bissbert.swissworld.gen.building.RoomType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** {@code /swiss admin loot preview <room> <n>}: rolls {@code swissworld:chests/<room>} n times. Owner: mod-content. */
@EventBusSubscriber(modid = SwissWorld.MOD_ID)
public final class LootCommand {
    private static final DynamicCommandExceptionType UNKNOWN =
            new DynamicCommandExceptionType(r -> Component.literal("Unknown room type: " + r));

    private LootCommand() {
    }

    @SubscribeEvent
    static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("swiss").then(Commands.literal("admin")
                .then(Commands.literal("loot").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("preview")
                                .then(Commands.argument("room", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                Arrays.stream(RoomType.values()).map(r -> r.id), b))
                                        .executes(c -> preview(c, 1))
                                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 20))
                                                .executes(c -> preview(c, IntegerArgumentType.getInteger(c, "n")))))))));
    }

    static int preview(CommandContext<CommandSourceStack> c, int n) throws CommandSyntaxException {
        String id = StringArgumentType.getString(c, "room");
        RoomType room = RoomType.byId(id);
        if (room == null) throw UNKNOWN.create(id);
        CommandSourceStack src = c.getSource();
        LootTable table = src.getServer().reloadableRegistries().getLootTable(LootStage.table(room));
        LootParams params = new LootParams.Builder(src.getLevel())
                .withParameter(LootContextParams.ORIGIN, src.getPosition()).create(LootContextParamSets.CHEST);
        Map<String, Integer> total = new TreeMap<>();
        int stacks = 0;
        for (int i = 0; i < n; i++) {
            for (ItemStack s : table.getRandomItems(params)) {
                total.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
                stacks++;
            }
        }
        String items = total.entrySet().stream().map(x -> x.getValue() + "x " + x.getKey()).collect(Collectors.joining(", "));
        int count = stacks;
        src.sendSuccess(() -> Component.literal("swissworld:" + room.lootPath() + " x" + n + ": " + count + " stacks, "
                + total.size() + " items: " + items), false);
        return total.size();
    }
}
