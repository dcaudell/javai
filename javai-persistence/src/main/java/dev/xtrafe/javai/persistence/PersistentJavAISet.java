package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.CollectionVectorSupport;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAISet;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.vector.VectorMath;
import org.hibernate.collection.spi.PersistentSet;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/** The {@link JavAISet} counterpart of {@link PersistentJavAIList} -- see that class for the rationale. */
public class PersistentJavAISet<E> extends PersistentSet<E> implements JavAISet<E>, JavAIDirtyTracking {

    // Named exactly like the weaver's synthesized field: JavAIRuntime.stateOf() locates an object's
    // dirty-tracking state reflectively by this name, and runWithSubgraphLockedForPersistence walks every
    // reachable JavAIVectorizable -- including this collection once Hibernate has substituted it into a
    // reloaded entity. A differently-named field makes that walk fail (caught by ArticleFixtureVolumeE2ETest).
    private final DirtyTrackingSupport $javai$state = new DirtyTrackingSupport();

    public PersistentJavAISet(SharedSessionContractImplementor session) {
        super(session);
    }

    public PersistentJavAISet(SharedSessionContractImplementor session, Set<E> set) {
        super(session, set);
    }

    @Override
    public boolean add(E element) {
        boolean added = super.add(element);
        if (added) {
            CollectionVectorSupport.onMutated($javai$state, this);
        }
        return added;
    }

    @Override
    public boolean remove(Object element) {
        boolean removed = super.remove(element);
        if (removed) {
            CollectionVectorSupport.onMutated($javai$state, this);
        }
        return removed;
    }

    @Override
    public void clear() {
        super.clear();
        CollectionVectorSupport.onMutated($javai$state, this);
    }

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
        throw new UnsupportedOperationException("a JavAI collection has no @Vectorize fields of its own");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        throw new UnsupportedOperationException("a JavAI collection has no @Vectorize fields of its own");
    }

    @Override
    public JavAIList<E> sortByCosineDistance(EmbeddingVector reference) {
        return nearestN(reference, size());
    }

    @Override
    public JavAIList<E> nearestN(EmbeddingVector reference, int n) {
        return this.stream()
                .filter(JavAIVectorizable.class::isInstance)
                .sorted(Comparator.comparingDouble(
                        (E element) -> CollectionVectorSupport.similarityOf(element, reference)).reversed())
                .limit(n)
                .collect(Collectors.toCollection(JavAIArrayList::new));
    }

    @Override
    public JavAIList<E> filterByMinSimilarity(EmbeddingVector reference, double threshold) {
        return this.stream()
                .filter(element -> CollectionVectorSupport.similarityOf(element, reference) >= threshold)
                .collect(Collectors.toCollection(JavAIArrayList::new));
    }

    @Override
    public EmbeddingVector centroid() {
        return vector();
    }

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
