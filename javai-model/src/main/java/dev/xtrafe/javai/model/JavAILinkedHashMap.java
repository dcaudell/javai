package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.vector.VectorMath;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 0's concrete {@link JavAIMap}. Ranking and {@code centroid()} operate over <em>values</em>, not
 * keys or entries -- keys are typically identifiers with no embedding of their own (see
 * doc/spec/vector-collections.md). See {@link JavAIArrayList} for the shared design rationale and the
 * "only the overridden mutators below are instrumented" limitation.
 */
public final class JavAILinkedHashMap<K, V> extends LinkedHashMap<K, V> implements JavAIMap<K, V>, JavAIDirtyTracking {

    private final DirtyTrackingSupport state = new DirtyTrackingSupport();

    public JavAILinkedHashMap() {
    }

    public JavAILinkedHashMap(Map<? extends K, ? extends V> initial) {
        putAll(initial);
    }

    @Override
    public V put(K key, V value) {
        V previous = super.put(key, value);
        JavAIRuntime.registerDependency(this, value);
        CollectionVectorSupport.onMutated(state, this);
        return previous;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> entries) {
        super.putAll(entries);
        for (V value : entries.values()) {
            JavAIRuntime.registerDependency(this, value);
        }
        CollectionVectorSupport.onMutated(state, this);
    }

    @Override
    public V remove(Object key) {
        V removed = super.remove(key);
        CollectionVectorSupport.onMutated(state, this);
        return removed;
    }

    @Override
    public void clear() {
        super.clear();
        CollectionVectorSupport.onMutated(state, this);
    }

    // ---- JavAIVectorizable ----

    @Override
    public EmbeddingVector vector() {
        return CollectionVectorSupport.vector(state, values());
    }

    @Override
    public EmbeddingVector summaryVector() {
        return CollectionVectorSupport.summaryVector(state, values());
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
        throw new UnsupportedOperationException("JavAILinkedHashMap has no @Vectorize fields of its own");
    }

    // ---- JavAISortable<V> / JavAIMap ----

    @Override
    public JavAIList<V> sortByCosineDistance(EmbeddingVector reference) {
        return values().stream()
                .filter(JavAIVectorizable.class::isInstance)
                .sorted(Comparator.comparingDouble(
                        (V value) -> CollectionVectorSupport.similarityOf(value, reference)).reversed())
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
