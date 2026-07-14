package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.vector.VectorMath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Phase 0's concrete {@link JavAIList}: a real {@link ArrayList} subclass, not a woven type -- library
 * code we control directly, so it implements {@link JavAIVectorizable}/{@link JavAIDirtyTracking} by
 * hand rather than through the weaver. {@code vector()}/{@code summaryVector()} are the centroid/
 * decay-weighted-sum over contained elements (see {@link CollectionVectorSupport}); {@code add}/
 * {@code remove}/{@code set}/{@code addAll}/{@code clear} register or invalidate dependents so mutating
 * a contained element propagates dirty up through this list to whatever holds it.
 *
 * <p><b>Known Phase 0 limitation:</b> only the mutators overridden below are instrumented. Mutating
 * through a {@code ListIterator}, {@code sort()}, {@code replaceAll()}, or {@code removeIf()} bypasses
 * dirty-tracking. Use {@code add}/{@code remove}/{@code set}/{@code addAll}/{@code clear} for anything
 * that needs to be reflected in {@code vector()}/{@code summaryVector()}.
 */
public final class JavAIArrayList<T> extends ArrayList<T> implements JavAIList<T>, JavAIDirtyTracking {

    // Named to match JavAIRuntime.STATE_FIELD exactly, not just "state": JavAIRuntime's whole-subgraph
    // persistence lock (runWithSubgraphLockedForPersistence) reflectively looks up this field by that
    // literal name on every JavAIVectorizable node it locks, including collection nodes like this one (see
    // that method's own javadoc) -- it doesn't special-case hand-written vs. woven implementers, so this
    // field has to be named identically to the one the weaver synthesizes on user classes.
    private final DirtyTrackingSupport $javai$state = new DirtyTrackingSupport();

    public JavAIArrayList() {
    }

    public JavAIArrayList(Collection<? extends T> initial) {
        addAll(initial);
    }

    // ---- instrumented mutators ----

    @Override
    public boolean add(T element) {
        boolean changed = super.add(element);
        afterAdd(element);
        return changed;
    }

    @Override
    public void add(int index, T element) {
        super.add(index, element);
        afterAdd(element);
    }

    @Override
    public boolean addAll(Collection<? extends T> elements) {
        boolean changed = super.addAll(elements);
        if (changed) {
            for (T element : elements) {
                JavAIRuntime.registerDependency(this, element);
            }
            CollectionVectorSupport.onMutated($javai$state, this);
        }
        return changed;
    }

    @Override
    public T set(int index, T element) {
        T previous = super.set(index, element);
        afterAdd(element);
        return previous;
    }

    @Override
    public T remove(int index) {
        T removed = super.remove(index);
        CollectionVectorSupport.onMutated($javai$state, this);
        return removed;
    }

    @Override
    public boolean remove(Object element) {
        boolean changed = super.remove(element);
        if (changed) {
            CollectionVectorSupport.onMutated($javai$state, this);
        }
        return changed;
    }

    @Override
    public void clear() {
        super.clear();
        CollectionVectorSupport.onMutated($javai$state, this);
    }

    private void afterAdd(T element) {
        JavAIRuntime.registerDependency(this, element);
        CollectionVectorSupport.onMutated($javai$state, this);
    }

    // ---- JavAIVectorizable ----

    @Override
    public EmbeddingVector vector() {
        return CollectionVectorSupport.vector($javai$state, this);
    }

    @Override
    public EmbeddingVector summaryVector() {
        return CollectionVectorSupport.summaryVector($javai$state, this);
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
        throw new UnsupportedOperationException("JavAIArrayList has no @Vectorize fields of its own");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        throw new UnsupportedOperationException("JavAIArrayList has no @Vectorize fields of its own");
    }

    // ---- JavAISortable / JavAIList ----

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
        $javai$state.addDependent(dependent);
    }

    @Override
    public Iterable<Object> dependents() {
        return $javai$state.dependents();
    }

    @Override
    public boolean isFieldDirty() {
        return $javai$state.isFieldDirty();
    }

    @Override
    public void markFieldDirty() {
        $javai$state.markFieldDirty();
    }

    @Override
    public void clearFieldDirty() {
        $javai$state.clearFieldDirty();
    }

    @Override
    public boolean isSummaryDirty() {
        return $javai$state.isSummaryDirty();
    }

    @Override
    public void markSummaryDirty() {
        $javai$state.markSummaryDirty();
    }

    @Override
    public void clearSummaryDirty() {
        $javai$state.clearSummaryDirty();
    }
}
