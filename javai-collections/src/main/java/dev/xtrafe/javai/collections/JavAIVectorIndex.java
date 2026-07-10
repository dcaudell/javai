package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.model.CollectionVectorSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIVectorizable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The concrete {@link VectorIndex}. Hand-written, not woven -- a plain, user-instantiated container
 * (like {@code javai-model}'s {@code JavAIArrayList}), so there's nothing here for
 * {@code javai-substrate}'s weaver to do at all. Reuses {@link CollectionVectorSupport#similarityOf} rather
 * than re-deriving the same non-vectorizable-element handling.
 */
public final class JavAIVectorIndex<T> implements VectorIndex<T> {

    private final List<T> items = new ArrayList<>();

    @Override
    public void add(T item) {
        items.add(item);
    }

    @Override
    public boolean remove(T item) {
        return items.remove(item);
    }

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public JavAIList<T> nearestN(EmbeddingVector reference, int n) {
        return items.stream()
                .filter(JavAIVectorizable.class::isInstance)
                .sorted(Comparator.comparingDouble(
                        (T item) -> CollectionVectorSupport.similarityOf(item, reference)).reversed())
                .limit(n)
                .collect(Collectors.toCollection(JavAIArrayList::new));
    }

    @Override
    public JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold) {
        return items.stream()
                .filter(item -> CollectionVectorSupport.similarityOf(item, reference) >= threshold)
                .collect(Collectors.toCollection(JavAIArrayList::new));
    }

    @Override
    public JavAIList<T> sortByCosineDistance(EmbeddingVector reference) {
        return nearestN(reference, items.size());
    }
}
