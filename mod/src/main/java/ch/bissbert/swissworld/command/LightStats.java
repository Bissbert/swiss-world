package ch.bissbert.swissworld.command;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * {@code /swiss admin light [pos]}: how much light data a level holds. Owner: mod-core. Dev diagnostic, by reflection.
 *
 * <p>Reads each layer's {@code visibleSectionData}, the immutable snapshot the light worker publishes, so it is safe
 * from the server thread. Per layer: sections with a DataLayer, how many of those carry a 2 KB array, how many of
 * those arrays are uniform (one value everywhere), and the hash table's capacity (what each light batch clones).
 */
final class LightStats {
    private LightStats() {}

    static String of(ServerLevel level) {
        StringBuilder out = new StringBuilder();
        try {
            LevelLightEngine engine = level.getLightEngine();
            for (String name : new String[] {"skyEngine", "blockEngine"}) {
                Object layerEngine = field(LevelLightEngine.class, name).get(engine);
                if (layerEngine == null) continue;
                Object storage = field(layerEngine.getClass(), "storage").get(layerEngine);
                Object visible = field(storage.getClass(), "visibleSectionData").get(storage);
                @SuppressWarnings("unchecked")
                Long2ObjectOpenHashMap<DataLayer> map = (Long2ObjectOpenHashMap<DataLayer>) field(visible.getClass(), "map").get(visible);
                Field data = field(DataLayer.class, "data");
                long arrays = 0, uniform = 0, minY = Long.MAX_VALUE, maxY = Long.MIN_VALUE;
                for (var e : map.long2ObjectEntrySet()) {
                    int y = SectionPos.y(e.getLongKey());
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    byte[] b = (byte[]) data.get(e.getValue());
                    if (b == null) continue;
                    arrays++;
                    boolean same = true;
                    for (int i = 1; i < b.length && same; i++) same = b[i] == b[0];
                    if (same) uniform++;
                }
                long[] keys = (long[]) field(Long2ObjectOpenHashMap.class, "key").get(map);
                out.append(String.format(Locale.ROOT, "%s: %d sections (y %d..%d), %d arrays (%d MB, %d uniform), table %d; ",
                        name.replace("Engine", ""), map.size(), minY, maxY, arrays, arrays * 2048 >> 20, uniform, keys.length));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            out.append("light stats failed: ").append(e);
        }
        return out.toString();
    }

    /** Sky and block light at one position, and whether its section has light storage in each layer. */
    static String at(ServerLevel level, BlockPos pos) {
        LevelLightEngine engine = level.getLightEngine();
        SectionPos section = SectionPos.of(pos);
        return String.format(Locale.ROOT, "%d %d %d: sky %d (%s), block %d (%s), block state %s",
                pos.getX(), pos.getY(), pos.getZ(),
                level.getBrightness(LightLayer.SKY, pos), stored(engine, LightLayer.SKY, section),
                level.getBrightness(LightLayer.BLOCK, pos), stored(engine, LightLayer.BLOCK, section),
                level.getBlockState(pos));
    }

    private static String stored(LevelLightEngine engine, LightLayer layer, SectionPos section) {
        return engine.getLayerListener(layer).getDataLayerData(section) != null ? "stored" : "not stored";
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }
}
