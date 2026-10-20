package ch.bissbert.swissworld.gen.site;

import ch.bissbert.swissworld.SwissWorld;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * The one way the stages after terrain put blocks into a chunk: knows the {@link SitePlan}'s claims and lets a stage
 * write only where the site gives it room. A write outside is dropped and counted ({@link #violations}); with
 * {@code -Dswissworld.trace=true} or {@code -Dswissworld.guard.log=true} the first ones are logged with stage, claim and
 * position. The stages check the claims themselves before writing; the guard is the backstop that makes "a lower claim
 * adapts to a higher one" hold whatever a stage forgets. Owner: mod-siteplan.
 *
 * <p>Rules, by the column's claim and the block's height y against the column's final ground G:
 * <ul>
 *   <li>Water: its own columns, banks, earthworks and natural ground; under the deck of a way or structure (y &lt;= G).
 *   <li>Overlay (ways, bridges, tunnels, earthworks): every column but buildings and landings; in those only under
 *       the ground (a tunnel bore: y &lt; G).
 *   <li>Buildings, furnishing, loot: buildings, landings, earthworks, banks, natural ground and water; over a way or
 *       structure only clear of its deck and head room (y &gt; G + {@link #HEADROOM}).
 *   <li>Vegetation (trees, ground cover): natural ground, earthworks, banks; over water or a way only high above it
 *       (an overhanging crown, y &gt; G + {@link #CROWN_CLEARANCE}); never in a structure, building or landing column.
 * </ul>
 * Columns outside the plan's tile + {@link SitePlan#PAD} (never, for a chunk of the tile) are not checked.
 */
public final class SiteWriter {
    /** Head room kept free above a way's deck G: the two blocks a player walks or rides in (G + 1, G + 2). */
    public static final int HEADROOM = 2;
    /** A tree's crown may hang over a way or water only this far above its deck or bed. */
    public static final int CROWN_CLEARANCE = 5;
    private static final boolean LOG = WriteTrace.ENABLED || Boolean.getBoolean("swissworld.guard.log");
    private static final int LOGGED = 200;

    public enum Stage { WATER, OVERLAY, BUILDING, FURNISH, LOOT, VEGETATION }

    private static final LongAdder[] VIOLATIONS = new LongAdder[Stage.values().length];
    private static final AtomicLong LOG_COUNT = new AtomicLong();

    static {
        for (int i = 0; i < VIOLATIONS.length; i++) VIOLATIONS[i] = new LongAdder();
    }

    private final ChunkAccess chunk;
    private final SitePlan site;
    private final Stage stage;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public SiteWriter(ChunkAccess chunk, SitePlan site, Stage stage) {
        this.chunk = chunk;
        this.site = site;
        this.stage = stage;
    }

    /** The writer of {@code stage} for the context's chunk (unguarded if the context has no site plan). */
    public static SiteWriter of(ch.bissbert.swissworld.gen.GenContext ctx, Stage stage) {
        return new SiteWriter(ctx.chunk, ctx.site(), stage);
    }

    public ChunkAccess chunk() {
        return chunk;
    }

    public SitePlan site() {
        return site;
    }

    /** Sets the block if the site allows it; returns the state replaced, or null if dropped or unchanged. */
    public BlockState set(int x, int y, int z, BlockState s) {
        if (!check(x, y, z)) return null;
        return chunk.setBlockState(pos.set(x, y, z), s, false);
    }

    /** {@link #allowed}, counting (and logging) a violation when not: for writers that set the block themselves. */
    public boolean check(int x, int y, int z) {
        if (allowed(x, y, z)) return true;
        violation(x, y, z);
        return false;
    }

    /** Whether a tree may stand on column (x, z): natural ground, earthwork or bank. */
    public boolean treeGround(int x, int z) {
        if (site == null || !site.covers(x, z)) return true;
        Claim c = site.claim(x, z);
        return c == Claim.FREE || c == Claim.EARTHWORK || c == Claim.BANK;
    }

    /** Whether this stage may write block (x, y, z). */
    public boolean allowed(int x, int y, int z) {
        if (site == null || !site.covers(x, z)) return true;
        return allowed(stage, site.claim(x, z), y, site.ground(x, z));
    }

    public static boolean allowed(Stage stage, Claim c, int y, int g) {
        return switch (stage) {
            case WATER -> switch (c) {
                case WATER, BANK, EARTHWORK, FREE -> true;
                default -> y <= g;
            };
            case OVERLAY -> switch (c) {
                case BUILDING, LANDING -> y < g;
                default -> true;
            };
            case BUILDING, FURNISH, LOOT -> switch (c) {
                case RAIL, ROAD, PATH, STRUCTURE -> y > g + HEADROOM;
                default -> true;
            };
            case VEGETATION -> switch (c) {
                case FREE, EARTHWORK, BANK -> true;
                case WATER, RAIL, ROAD, PATH -> y > g + CROWN_CLEARANCE;
                default -> false;
            };
        };
    }

    /** Sees every dropped write (tests: which rule a stage breaks where). */
    public interface Observer {
        void violation(Stage stage, int x, int y, int z, Claim claim, int ground);
    }

    /** Set by tests only. */
    public static volatile Observer observer;

    private void violation(int x, int y, int z) {
        VIOLATIONS[stage.ordinal()].increment();
        Observer o = observer;
        if (o != null) o.violation(stage, x, y, z, site.claim(x, z), site.ground(x, z));
        if (LOG && LOG_COUNT.incrementAndGet() <= LOGGED) {
            SwissWorld.LOGGER.warn("site guard: {} may not write {},{},{} ({} column, G {})", stage, x, y, z,
                    site.claim(x, z), site.ground(x, z));
        }
    }

    /** Writes dropped so far, per stage. */
    public static long violations(Stage s) {
        return VIOLATIONS[s.ordinal()].sum();
    }

    public static long violations() {
        long n = 0;
        for (LongAdder a : VIOLATIONS) n += a.sum();
        return n;
    }

    public static void reset() {
        for (LongAdder a : VIOLATIONS) a.reset();
        LOG_COUNT.set(0);
    }
}
