package ch.bissbert.swissworld.compat;

import java.lang.reflect.Method;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Soft dependency on Distant Horizons (reflection, so no DH jar is needed to build and nothing loads without it): while
 * a {@code /swiss} arrival is pending, distant world generation is switched off through DH's public API
 * ({@code DhApi.Delayed.configs.worldGenerator().enableDistantWorldGeneration()}), because DH would otherwise spend the
 * CPU the arrival needs on LODs of the vanilla overworld. {@code setValue} there is an API override that does not touch
 * {@code DistantHorizons.toml}; {@link #set(boolean) set(false)} puts the previous API value back (or clears the
 * override). Everything is a no-op when DH is absent or its API is not ready, and failures are logged once and never
 * propagate.
 */
public final class DistantHorizonsPause {
    private static final Logger LOG = LogUtils.getLogger();
    private static boolean paused;
    private static boolean broken;
    private static Object value; // the IDhApiConfigValue we overrode
    private static Object previousApi; // its API override before ours (null: none)

    private DistantHorizonsPause() {}

    /** Called every server tick with whether an arrival is pending: pauses on the first true, resumes on the first false after. */
    public static void set(boolean pause) {
        if (pause == paused || broken) return;
        try {
            if (pause) pause();
            else resume();
        } catch (Throwable t) {
            broken = true;
            LOG.warn("Distant Horizons pause failed, giving up on it: {}", t.toString());
        }
    }

    private static void pause() throws Exception {
        ModList list = ModList.get();
        if (list == null || !list.isLoaded("distanthorizons")) {
            broken = true; // not there: never look again
            return;
        }
        Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
        Object configs = delayed.getField("configs").get(null);
        if (configs == null) return; // DH not initialised yet: try again next tick
        String api = "com.seibel.distanthorizons.api.interfaces.config.";
        Object gen = Class.forName(api + "IDhApiConfig").getMethod("worldGenerator").invoke(configs);
        Object v = Class.forName(api + "both.IDhApiWorldGenerationConfig").getMethod("enableDistantWorldGeneration").invoke(gen);
        Class<?> cv = Class.forName(api + "IDhApiConfigValue"); // the public interface: the implementation class may not be
        Method get = cv.getMethod("getApiValue");
        Method set = cv.getMethod("setValue", Object.class);
        Object prev = get.invoke(v);
        if (Boolean.TRUE.equals(set.invoke(v, Boolean.FALSE))) {
            value = v;
            previousApi = prev;
            paused = true;
            LOG.info("Distant Horizons distant generation paused while a /swiss arrival is pending");
        }
    }

    private static void resume() throws Exception {
        Object v = value;
        paused = false;
        value = null;
        if (v == null) return;
        if (previousApi != null) {
            Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue")
                    .getMethod("setValue", Object.class).invoke(v, previousApi);
        } else {
            Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue").getMethod("clearValue").invoke(v);
        }
        previousApi = null;
        LOG.info("Distant Horizons distant generation restored");
    }
}
