package ch.bissbert.swissworld.gen;

/** One step of the per-chunk pipeline in {@link SwissChunkGenerator#PIPELINE}. */
@FunctionalInterface
public interface GenStage {
    void apply(GenContext ctx);
}
