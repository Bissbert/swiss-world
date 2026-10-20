package ch.bissbert.swissworld.gen.site;

import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** {@code /swiss admin stats [reset]} and {@code /swiss admin who <pos>}; wired into the admin chain of SwissCommands. */
public final class SiteCommands {
    private SiteCommands() {}

    public static int stats(CommandSourceStack s) {
        for (String line : GenStats.table()) s.sendSystemMessage(Component.literal(line));
        s.sendSystemMessage(Component.literal(String.format("site plans cached %d, cell misses %d, guard violations %d",
                SitePlans.cached(), SitePlans.misses(), SiteWriter.violations())));
        return 1;
    }

    public static int resetStats(CommandSourceStack s) {
        GenStats.reset();
        SiteWriter.reset();
        s.sendSystemMessage(Component.literal("gen stats reset"));
        return 1;
    }

    public static int who(CommandSourceStack s, BlockPos pos) {
        String block = s.getLevel().getBlockState(pos).toString();
        if (!WriteTrace.ENABLED) {
            s.sendFailure(Component.literal("trace is off: start the server with -Dswissworld.trace=true"));
            return 0;
        }
        List<String> w = WriteTrace.writers(pos);
        s.sendSystemMessage(Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block + ": "
                + (w == null ? "chunk not traced (generated before this start, or evicted)" : String.join(" > ", w))));
        return 1;
    }
}
