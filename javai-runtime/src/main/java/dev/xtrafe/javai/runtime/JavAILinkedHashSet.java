package dev.xtrafe.javai.runtime;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Phase 0's concrete {@link JavAISet}. See {@link JavAIArrayList} for the shared design rationale
 * (real JDK-collection subclass, hand-implemented {@link JavAIVectorizable}/{@link JavAIDirtyTracking},
 * same "only the overridden mutators below are instrumented" limitation).
 */
public final class JavAILinkedHashSet<T> extends LinkedHashSet<T> implements JavAISet<T>, JavAIDirtyTracking {

    private final DirtyTrackingSupport state = new DirtyTrackingSupport();

    public JavAILinkedHashSet() {
    }

    public JavAILinkedHashSet(Collection<? extends T> initial) {
        addAll(initial);
    }

    @Override
    public boolean add(T element) {
        boolean changed = super.add(element);
        if (changed) {
            JavAIRuntime.registerDependency(this, element);
            CollectionVectorSupport.onMutated(state, this);
        }
        return changed;
    }

    @Override
    public boolean addAll(Collection<? extends T> elements) {
        boolean changed = super.addAll(elements);
        if (changed) {
            for (T element : elements) {
                JavAIRuntime.registerDependency(this, element);
            }
            CollectionVectorSupport.onMutated(state, this);
        }
        return changed;
    }

    @Override
    public boolean remove(Object element) {
        boolean changed = super.remove(element);
        if (changed) {
            CollectionVectorSupport.onMutated(state, this);
        }
        return changed;
    }

    @Override
    public void clear() {
        super.clear();
        CollectionVectorSupport.onMutated(state, this);
    }

    // ---- JavAIVectorizable ----

    @Override
    public EmbeddingVector vector() {
        return CollectionVectorSupport.vector(state, this);
    }

    @Override
    public EmbeddingVector summaryVector() {
        return CollectionVectorSupport.summaryVector(state, this);
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return VectorMath.cosineSimilarity(vector(), other.vector());
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return VectorMath.cosineSimilarity(vector(), reference);
    }

    @Override
    public <R> JavAIList<R> query(EmbeddingVector reference, Class<R> type) {
        return JavAIRuntime.query(this, reference, type, Integer.MAX_VALUE);
    }

    @Override
    public <R> JavAIList<R> query(EmbeddingVector reference, Class<R> type, int maxDepth) {
        return JavAIRuntime.query(this, reference, type, maxDepth);
    }

    @Override
    public EmbeddingVector fieldVector(String fieldName) {
        throw new UnsupportedOperationException("JavAILinkedHashSet has no @Vectorize fields of its own");
    }

    // ---- JavAISortable / JavAISet ----

    @Override
    public JavAIList<T> sortByCosineDistance(EmbeddingVector reference) {
        return nearestN(reference, size());
    }

    @Override
    public JavAIList<T> nearestN(EmbeddingVector reference, int n) {
        return this.stream()
                .filter(JavAIVectorizable.class::isInstance)
                .sorted(Comparator.comparingDouble(
                        (T element) -> CollectionVectorSupport.similarityOf(element, reference)).reversed())
                .limit(n)
                .collect(Collectors.toCollection(JavAIArrayList::new));
    }

    @Override
    public JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold) {
        return this.stream()
                .filter(element -> CollectionVectorSupport.similarityOf(element, reference) >= threshold)
                .collect(Collectors.toCollection(JavAIArrayList::new));
    }

    @Override
    public EmbeddingVector centroid() {
        return vector();
    }

    // ---- JavAIDirtyTracking ----

    @Override
    public void addDependent(Object dependent) {
        state.addDependent(dependent);
    }

    @Override
    public Iterable<Object> dependents() {
        return state.dependents();
    }

    @Override
    public boolean isFieldDirty() {
        return state.isFieldDirty();
    }

    @Override
    public void markFieldDirty() {
        state.markFieldDirty();
    }

    @Override
    public void clearFieldDirty() {
        state.clearFieldDirty();
    }

    @Override
    public boolean isSummaryDirty() {
        return state.isSummaryDirty();
    }

    @Override
    public void markSummaryDirty() {
        state.markSummaryDirty();
    }

    @Override
    public void clearSummaryDirty() {
        state.clearSummaryDirty();
    }
}
