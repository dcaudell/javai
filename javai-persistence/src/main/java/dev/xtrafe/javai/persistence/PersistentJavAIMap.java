package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.CollectionVectorSupport;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIMap;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.vector.VectorMath;
import org.hibernate.collection.spi.PersistentMap;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/** The {@link JavAIMap} counterpart of {@link PersistentJavAIList}. Vector behavior is computed over the
 *  map's {@code values()}, exactly as {@code JavAILinkedHashMap} does. */
public class PersistentJavAIMap<K, V> extends PersistentMap<K, V> implements JavAIMap<K, V>, JavAIDirtyTracking {

    // Named exactly like the weaver's synthesized field: JavAIRuntime.stateOf() locates an object's
    // dirty-tracking state reflectively by this name, and runWithSubgraphLockedForPersistence walks every
    // reachable JavAIVectorizable -- including this collection once Hibernate has substituted it into a
    // reloaded entity. A differently-named field makes that walk fail (caught by ArticleFixtureVolumeE2ETest).
    private final DirtyTrackingSupport $javai$state = new DirtyTrackingSupport();

    public PersistentJavAIMap(SharedSessionContractImplementor session) {
        super(session);
    }

    public PersistentJavAIMap(SharedSessionContractImplementor session, Map<K, V> map) {
        super(session, map);
    }

    @Override
    public V put(K key, V value) {
        V previous = super.put(key, value);
        CollectionVectorSupport.onMutated($javai$state, this);
        return previous;
    }

    @Override
    public V remove(Object key) {
        V removed = super.remove(key);
        if (removed != null) {
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
        return CollectionVectorSupport.vector($javai$state, values());
    }

    @Override
    public EmbeddingVector summaryVector() {
        return CollectionVectorSupport.summaryVector($javai$state, values());
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
