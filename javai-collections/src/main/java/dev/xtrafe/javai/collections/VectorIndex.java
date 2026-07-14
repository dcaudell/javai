package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAISortable;

/**
 * Bare similarity-search container "for cases that don't need full graph semantics" (doc/spec/
 * vector-collections.md). Deliberately simpler than {@code JavAIList}/{@code JavAISet}: no
 * {@code JavAIVectorizable} of its own (no {@code vector()}/{@code summaryVector()} -- it isn't meant to
 * be a node in the object graph itself, just a standalone lookup structure) and no dependents/dirty
 * tracking. If you need a collection that participates in {@code summaryVector()} propagation, reach for
 * {@code JavAIArrayList}/{@code JavAILinkedHashSet} in {@code javai-model} instead.
 */
public interface VectorIndex<T> extends JavAISortable<T> {

    void add(T item);

    boolean remove(T item);

    int size();

    JavAIList<T> nearestN(EmbeddingVector reference, int n);

    JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold);
}
