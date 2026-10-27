package ch.bissbert.swissworld.gen;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * The FEATURES step ({@code ChunkGenerator.applyBiomeDecoration}) with a cheap biome set. Owner: gen-speed.
 *
 * <p>Vanilla collects the biomes of the 3x3 chunks around the one being decorated by reading every one of the
 * 254 sections of each (146k set insertions, 2.8 ms CPU per chunk, more than a third of the whole fill). Swiss biomes do
 * not depend on y ({@link SwissBiomeSource#resolver}), so one section per chunk holds the whole set. Everything else
 * is vanilla's code line for line (NeoForge 21.1.262): same decoration seed, same feature order, same structure
 * handling, so the result is block-identical; {@link #biomesOf} is the only difference and
 * {@code DecoCheck} (admin command {@code /swiss admin decocheck}) compares both paths on generated chunks.
 *
 * <p>A chunk that does not hold a single biome in all sections (none is written by this mod) would give a smaller set
 * here than in vanilla, i.e. fewer features around it; {@code DecoCheck} reports such chunks.
 */
public final class FastDecoration {
    private FastDecoration() {}

    /** Registry the by-step structure table below was built from (registries are frozen once the server runs). */
    private static volatile Registry<Structure> structuresFrom;
    private static volatile Map<Integer, List<Structure>> structuresByStep;

    private static Map<Integer, List<Structure>> structuresByStep(Registry<Structure> registry) {
        Map<Integer, List<Structure>> m = structuresByStep;
        if (m == null || structuresFrom != registry) {
            m = registry.stream().collect(Collectors.groupingBy(s -> s.step().ordinal()));
            structuresByStep = m;
            structuresFrom = registry;
        }
        return m;
    }

    /** The biomes of the 3x3 chunks around {@code center}, from the lowest section of each (y-independent biomes). */
    public static Set<Holder<Biome>> biomesOf(WorldGenLevel level, ChunkPos center) {
        Set<Holder<Biome>> set = new ObjectArraySet<>();
        ChunkPos.rangeClosed(center, 1).forEach(p -> {
            LevelChunkSection[] sections = level.getChunk(p.x, p.z).getSections();
            if (sections.length > 0) sections[0].getBiomes().getAll(set::add);
        });
        return set;
    }

    /** Vanilla's biome set, for the check. */
    public static Set<Holder<Biome>> biomesOfAllSections(WorldGenLevel level, ChunkPos center) {
        Set<Holder<Biome>> set = new ObjectArraySet<>();
        ChunkPos.rangeClosed(center, 1).forEach(p -> {
            for (LevelChunkSection s : level.getChunk(p.x, p.z).getSections()) s.getBiomes().getAll(set::add);
        });
        return set;
    }

    /** {@code ChunkGenerator.applyBiomeDecoration} of NeoForge 21.1.262 with {@link #biomesOf}. */
    public static void apply(ChunkGenerator gen, WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        ChunkPos chunkpos = chunk.getPos();
        if (SharedConstants.debugVoidTerrain(chunkpos)) return;
        SectionPos sectionpos = SectionPos.of(chunkpos, level.getMinSection());
        BlockPos blockpos = sectionpos.origin();
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Map<Integer, List<Structure>> map = structuresByStep(registry);
        List<FeatureSorter.StepFeatureData> list = gen.featuresPerStep.get();
        WorldgenRandom worldgenrandom = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
        long i = worldgenrandom.setDecorationSeed(level.getSeed(), blockpos.getX(), blockpos.getZ());
        Set<Holder<Biome>> set = biomesOf(level, sectionpos.chunk());
        set.retainAll(gen.getBiomeSource().possibleBiomes());
        int j = list.size();

        try {
            Registry<PlacedFeature> registry1 = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
            int i1 = Math.max(GenerationStep.Decoration.values().length, j);

            for (int k = 0; k < i1; k++) {
                int l = 0;
                if (structureManager.shouldGenerateStructures()) {
                    for (Structure structure : map.getOrDefault(k, Collections.emptyList())) {
                        worldgenrandom.setFeatureSeed(i, l, k);
                        Supplier<String> supplier = () -> registry.getResourceKey(structure).map(Object::toString).orElseGet(structure::toString);

                        try {
                            level.setCurrentlyGenerating(supplier);
                            structureManager.startsForStructure(sectionpos, structure)
                                .forEach(start -> start.placeInChunk(level, structureManager, gen, worldgenrandom, writableArea(chunk), chunkpos));
                        } catch (Exception exception) {
                            CrashReport crashreport1 = CrashReport.forThrowable(exception, "Feature placement");
                            crashreport1.addCategory("Feature").setDetail("Description", supplier::get);
                            throw new ReportedException(crashreport1);
                        }

                        l++;
                    }
                }

                if (k < j) {
                    IntSet intset = new IntArraySet();

                    for (Holder<Biome> holder : set) {
                        List<HolderSet<PlacedFeature>> list1 = gen.generationSettingsGetter.apply(holder).features();
                        if (k < list1.size()) {
                            HolderSet<PlacedFeature> holderset = list1.get(k);
                            FeatureSorter.StepFeatureData stepData = list.get(k);
                            holderset.stream().map(Holder::value).forEach(f -> intset.add(stepData.indexMapping().applyAsInt(f)));
                        }
                    }

                    int j1 = intset.size();
                    int[] aint = intset.toIntArray();
                    Arrays.sort(aint);
                    FeatureSorter.StepFeatureData stepData = list.get(k);

                    for (int k1 = 0; k1 < j1; k1++) {
                        int l1 = aint[k1];
                        PlacedFeature placedfeature = stepData.features().get(l1);
                        Supplier<String> supplier1 = () -> registry1.getResourceKey(placedfeature).map(Object::toString).orElseGet(placedfeature::toString);
                        worldgenrandom.setFeatureSeed(i, l1, k);

                        try {
                            level.setCurrentlyGenerating(supplier1);
                            placedfeature.placeWithBiomeCheck(level, gen, worldgenrandom, blockpos);
                        } catch (Exception exception1) {
                            CrashReport crashreport2 = CrashReport.forThrowable(exception1, "Feature placement");
                            crashreport2.addCategory("Feature").setDetail("Description", supplier1::get);
                            throw new ReportedException(crashreport2);
                        }
                    }
                }
            }

            level.setCurrentlyGenerating(null);
        } catch (Exception exception2) {
            CrashReport crashreport = CrashReport.forThrowable(exception2, "Biome decoration");
            crashreport.addCategory("Generation").setDetail("CenterX", chunkpos.x).setDetail("CenterZ", chunkpos.z).setDetail("Decoration Seed", i);
            throw new ReportedException(crashreport);
        }
    }

    private static BoundingBox writableArea(ChunkAccess chunk) {
        ChunkPos chunkpos = chunk.getPos();
        int i = chunkpos.getMinBlockX();
        int j = chunkpos.getMinBlockZ();
        var h = chunk.getHeightAccessorForGeneration();
        return new BoundingBox(i, h.getMinBuildHeight() + 1, j, i + 15, h.getMaxBuildHeight() - 1, j + 15);
    }
}
