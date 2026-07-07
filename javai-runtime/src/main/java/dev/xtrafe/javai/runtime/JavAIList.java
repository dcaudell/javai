package dev.xtrafe.javai.runtime;

import java.util.List;

/**
 * Strict superset of {@link java.util.List}: usable anywhere existing code expects a
 * {@code List<T>}, not a replacement for it. See doc/spec/vector-collections.md.
 */
public interface JavAIList<T> extends List<T>, JavAISortable<T>, JavAIVectorizable {

    /** Top-N by similarity without materializing a full sort. */
    JavAIList<T> nearestN(EmbeddingVector reference, int n);

    JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold);

    EmbeddingVector centroid();
}
