package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.CollectionVectorSupport;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.vector.VectorMath;
import org.hibernate.collection.spi.PersistentBag;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * OMI-142: a genuine Hibernate-managed collection that is
 * <em>also</em> a {@link JavAIList}. Extends Hibernate's own {@link PersistentBag} (so all the hard parts --
 * dirty-check snapshots, session attach/detach, lazy initialization -- stay Hibernate's battle-tested code)
 * and adds the JavAI behavior by delegating to {@link CollectionVectorSupport}, exactly as
 * {@link JavAIArrayList} does.
 *
 * <p>This is what answers the objection recorded in {@code RepositoryBackendHibernatePostgres}'s javadoc --
 * that declaring the field by interface "wouldn't help, since Hibernate would silently replace the real
 * instance with its own wrapper." With a {@code UserCollectionType}, the wrapper Hibernate substitutes is
 * <em>this</em>, so the vector/dirty-tracking behavior survives.
 */
public class PersistentJavAIList<E> extends PersistentBag<E> implements JavAIList<E>, JavAIDirtyTracking {

    // Named exactly like the weaver's synthesized field: JavAIRuntime.stateOf() locates an object's
    // dirty-tracking state reflectively by this name, and runWithSubgraphLockedForPersistence walks every
    // reachable JavAIVectorizable -- including this collection once Hibernate has substituted it into a
    // reloaded entity. A differently-named field makes that walk fail (caught by ArticleFixtureVolumeE2ETest).
    private final DirtyTrackingSupport $javai$state = new DirtyTrackingSupport();

    public PersistentJavAIList(SharedSessionContractImplementor session) {
        super(session);
    }

    public PersistentJavAIList(SharedSessionContractImplementor session, Collection<E> collection) {
        super(session, collection);
    }

    // ---- mutation hooks: same contract JavAIArrayList's overrides provide ----

    @Override
    public boolean add(E element) {
        boolean added = super.add(element);
        CollectionVectorSupport.onMutated($javai$state, this);
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
        throw new UnsupportedOperationException("a JavAI collection has no @Vectorize fields of its own");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        throw new UnsupportedOperationException("a JavAI collection has no @Vectorize fields of its own");
    }

    // ---- JavAISortable / JavAIList ----

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

    // ---- JavAIDirtyTracking (delegated exactly as JavAIArrayList delegates to its own state) ----

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
