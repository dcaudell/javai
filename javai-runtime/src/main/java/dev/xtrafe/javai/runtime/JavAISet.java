package dev.xtrafe.javai.runtime;

import java.util.Set;

/** Strict superset of {@link java.util.Set}. See doc/spec/vector-collections.md. */
public interface JavAISet<T> extends Set<T>, JavAISortable<T>, JavAIVectorizable {

    JavAIList<T> nearestN(EmbeddingVector reference, int n);

    JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold);

    EmbeddingVector centroid();
}
