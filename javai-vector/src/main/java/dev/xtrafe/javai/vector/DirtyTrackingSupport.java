package dev.xtrafe.javai.vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The entire durable per-object state doc/spec/vector-core.md describes -- two independent dirty flags,
 * cached vectors, and a back-edge dependents set -- as one object, not six separate fields. A woven
 * {@code @JavAIVectorizable} class gets exactly one synthesized field of this type
 * ({@link JavAIRuntime#STATE_FIELD}), lazily created on first access; {@link JavAIRuntime}'s static
 * helpers reach it with a single reflective field lookup instead of one per piece of state. The concrete
 * collection types in this package ({@link JavAIArrayList} and friends) hold one directly as an ordinary
 * field -- no reflection needed there, since it's our own code.
 *
 * <p>Public because the weaver (a different module, {@code javai-substrate}) references this type by name
 * when defining the synthesized field. The cache accessors below are also public, for the same
 * cross-module reason: {@code JavAIRuntime} and the concrete collection types that need them now live in
 * {@code javai-model}, a separate module from this one (see that module's own package-info.java for why),
 * not just a separate class in the same package the way they did before that split.
 */
public final class DirtyTrackingSupport implements JavAIDirtyTracking {

    private boolean fieldDirty = true;
    private boolean summaryDirty = true;
    private EmbeddingVector vector;
    private EmbeddingVector summaryVector;
    private final Set<IdentityWeakReference> dependents = new HashSet<>();

    @Override
    public void addDependent(Object dependent) {
        dependents.add(new IdentityWeakReference(dependent));
    }

    @Override
    public Iterable<Object> dependents() {
        List<Object> live = new ArrayList<>(dependents.size());
        Iterator<IdentityWeakReference> iterator = dependents.iterator();
        while (iterator.hasNext()) {
            Object referent = iterator.next().get();
            if (referent == null) {
                iterator.remove();
            } else {
                live.add(referent);
            }
        }
        return live;
    }

    @Override
    public boolean isFieldDirty() {
        return fieldDirty;
    }

    @Override
    public void markFieldDirty() {
        fieldDirty = true;
    }

    @Override
    public void clearFieldDirty() {
        fieldDirty = false;
    }

    @Override
    public boolean isSummaryDirty() {
        return summaryDirty;
    }

    @Override
    public void markSummaryDirty() {
        summaryDirty = true;
    }

    @Override
    public void clearSummaryDirty() {
        summaryDirty = false;
    }

    public EmbeddingVector cachedVector() {
        return vector;
    }

    public void cacheVector(EmbeddingVector value) {
        vector = value;
    }

    public EmbeddingVector cachedSummaryVector() {
        return summaryVector;
    }

    public void cacheSummaryVector(EmbeddingVector value) {
        summaryVector = value;
    }
}
