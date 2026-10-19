package ch.bissbert.swissworld;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(SwissWorld.MOD_ID)
public final class SwissWorld {
    public static final String MOD_ID = "swissworld";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SwissWorld(IEventBus modBus, ModContainer container) {
        LOGGER.info("Swiss World loading (data ©swisstopo)");
        SwissRegistries.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, SwissConfig.SPEC);
        SwissEvents.register();
        modBus.addListener(ch.bissbert.swissworld.world.SwissSpawns::register);
    }
}
