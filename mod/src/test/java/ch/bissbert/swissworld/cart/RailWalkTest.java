package ch.bissbert.swissworld.cart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.junit.jupiter.api.Test;

class RailWalkTest {
    /** A small rail world: rails by position, chunks (by column) that are not ready. */
    static final class World implements RailWalk.RailView {
        final Map<String, RailShape> rails = new HashMap<>();
        final Set<Integer> unreadyX = new HashSet<>();

        World rail(int x, int y, int z, RailShape s) {
            rails.put(x + "," + y + "," + z, s);
            return this;
        }

        @Override
        public RailShape shape(int x, int y, int z) {
            return rails.get(x + "," + y + "," + z);
        }

        @Override
        public boolean ready(int x, int z) {
            return !unreadyX.contains(x);
        }
    }

    static World straightEast(int from, int to) {
        World w = new World();
        for (int x = from; x <= to; x++) w.rail(x, 0, 0, RailShape.EAST_WEST);
        return w;
    }

    @Test
    void longStraightIsClear() {
        RailWalk.Ahead a = RailWalk.scan(straightEast(0, 100), 0, 0, 0, RailShape.EAST_WEST, 1, 0, 20);
        assertEquals(RailWalk.Stop.CLEAR, a.stop());
        assertEquals(20, a.distance(), 1e-9);
    }

    @Test
    void deadEndIsTheEndOfTheTrack() {
        RailWalk.Ahead a = RailWalk.scan(straightEast(0, 10), 0, 0, 0, RailShape.EAST_WEST, 1, 0, 30);
        assertEquals(RailWalk.Stop.END, a.stop());
        assertEquals(10, a.rails());
        assertEquals(10, a.distance(), 1e-9);
    }

    @Test
    void headingDecidesTheDirection() {
        // going west from x 5 on rails 0..10: 5 rails to the end
        RailWalk.Ahead a = RailWalk.scan(straightEast(0, 10), 5, 0, 0, RailShape.EAST_WEST, -1, 0, 30);
        assertEquals(RailWalk.Stop.END, a.stop());
        assertEquals(5, a.rails());
    }

    @Test
    void zigzagDiagonalIsFollowedAndShorterPerRail() {
        // north-east staircase: (0,0) runs N-S, then curves alternate: east, north, east, north ...
        World w = new World().rail(0, 0, 1, RailShape.NORTH_SOUTH);
        int x = 0, z = 0;
        for (int i = 0; i < 20; i++) {
            // enter (x, z) from the south; leave east; enter (x+1, z) from the west; leave north
            w.rail(x, 0, z, RailShape.SOUTH_EAST);
            w.rail(x + 1, 0, z, RailShape.NORTH_WEST);
            x++;
            z--;
        }
        RailWalk.Ahead a = RailWalk.scan(w, 0, 0, 1, RailShape.NORTH_SOUTH, 0, -1, 10);
        assertEquals(RailWalk.Stop.CLEAR, a.stop());
        // curve pieces count 1/sqrt(2) each
        assertEquals(15, a.rails());
    }

    @Test
    void slopeUpAndDownAreJoinedTrack() {
        World w = new World()
                .rail(0, 0, 0, RailShape.EAST_WEST)
                .rail(1, 0, 0, RailShape.ASCENDING_EAST)   // climbs to y 1 eastwards
                .rail(2, 1, 0, RailShape.EAST_WEST)
                .rail(3, 0, 0, RailShape.ASCENDING_WEST)   // going east: down to y 0
                .rail(4, 0, 0, RailShape.EAST_WEST);
        RailWalk.Ahead a = RailWalk.scan(w, 0, 0, 0, RailShape.EAST_WEST, 1, 0, 30);
        assertEquals(RailWalk.Stop.END, a.stop());
        assertEquals(4, a.rails());
    }

    @Test
    void railPointingElsewhereEndsTheTrack() {
        // a north-south rail across the line (a junction set the other way) does not take an eastbound cart
        World w = straightEast(0, 3).rail(4, 0, 0, RailShape.NORTH_SOUTH).rail(5, 0, 0, RailShape.EAST_WEST);
        RailWalk.Ahead a = RailWalk.scan(w, 0, 0, 0, RailShape.EAST_WEST, 1, 0, 30);
        assertEquals(RailWalk.Stop.END, a.stop());
        assertEquals(3, a.rails());
    }

    @Test
    void unreadyChunkStopsTheWalk() {
        World w = straightEast(0, 100);
        w.unreadyX.add(16);
        RailWalk.Ahead a = RailWalk.scan(w, 0, 0, 0, RailShape.EAST_WEST, 1, 0, 40);
        assertEquals(RailWalk.Stop.UNREADY, a.stop());
        assertEquals(15, a.distance(), 1e-9);
    }

    @Test
    void exitAlongPicksTheExitTheCartFaces() {
        assertArrayEquals(new int[] {1, 0}, RailWalk.exitAlong(RailShape.NORTH_EAST, 0.7, 0.7));
        assertArrayEquals(new int[] {0, -1}, RailWalk.exitAlong(RailShape.NORTH_EAST, -0.7, -0.7));
        assertArrayEquals(new int[] {0, 1}, RailWalk.exitAlong(RailShape.ASCENDING_SOUTH, 0.1, 0.9));
    }
}
