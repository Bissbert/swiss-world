package ch.bissbert.swissworld.loot;

import ch.bissbert.swissworld.SwissWorld;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Registers {@code swissworld:auto_mix} and drops the auto-mixed pools when tags change. Owner: mod-content. */
@EventBusSubscriber(modid = SwissWorld.MOD_ID)
public final class LootEvents {
    private LootEvents() {
    }

    @SubscribeEvent
    static void register(RegisterEvent e) {
        e.register(Registries.LOOT_POOL_ENTRY_TYPE, h -> h.register(
                ResourceLocation.fromNamespaceAndPath(SwissWorld.MOD_ID, "auto_mix"), AutoMixEntry.TYPE));
    }

    @SubscribeEvent
    static void tags(TagsUpdatedEvent e) {
        LootPools.invalidate();
    }
}
