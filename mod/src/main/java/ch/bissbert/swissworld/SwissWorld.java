package ch.bissbert.swissworld;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(SwissWorld.MOD_ID)
public final class SwissWorld {
    public static final String MOD_ID = "swissworld";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SwissWorld(IEventBus modBus) {
        LOGGER.info("Swiss World loading (data ©swisstopo)");
        SwissRegistries.register(modBus);
    }
}
