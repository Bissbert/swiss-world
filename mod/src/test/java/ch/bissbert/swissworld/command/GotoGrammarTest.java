package ch.bissbert.swissworld.command;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

/** The goto grammar of SwissCommands: numbers go to "a b", anything else to the greedy place name. */
class GotoGrammarTest {
    private static int run(String cmd) throws CommandSyntaxException {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        d.register(literal("goto")
                .then(argument("a", DoubleArgumentType.doubleArg()).then(argument("b", DoubleArgumentType.doubleArg()).executes(c -> 1)))
                .then(argument("place", StringArgumentType.greedyString()).executes(c -> 2)));
        return d.execute(cmd, new Object());
    }

    @Test
    void numbersStayCoordinates() throws Exception {
        assertEquals(1, run("goto 2638356 1237642"));
        assertEquals(1, run("goto 46.68 7.87"));
        assertEquals(1, run("goto 638356 237642"));
    }

    @Test
    void namesGoToThePlaceSlot() throws Exception {
        assertEquals(2, run("goto Zofingen"));
        assertEquals(2, run("goto St. Moritz"));
        assertEquals(2, run("goto Biel/Bienne"));
        assertEquals(2, run("goto Genève"));
        assertEquals(2, run("goto 46.68, 7.87")); // comma form lands here; goToPlace hands it to goTo
    }
}
