package dev.xtrafe.javai.vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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

    private static final AtomicLong SEQUENCE_GENERATOR = new AtomicLong();

    private volatile boolean fieldDirty = true;
    private volatile boolean summaryDirty = true;

    /**
     * Staleness of a JavAI <em>collection</em>'s own centroid ({@code CollectionVectorSupport.vector}),
     * tracked separately from {@link #summaryDirty} even though both are set by exactly the same events.
     *
     * <p>Why it needs its own bit (OMI-187): a collection's centroid is stale under two conditions --
     * membership changed ({@code FieldDirty}) or an element mutated ({@code SummaryDirty}, reaching this
     * collection through {@code propagateDirty}'s dependent walk). So the centroid legitimately has to
     * recompute on {@code SummaryDirty}. But {@code SummaryDirty} is *also* the collection's summary
     * vector's staleness signal, and only {@code summaryVector()} clears it -- so a collection read through
     * {@code vector()} alone recomputed its centroid on <b>every single read, forever</b>. For a populated
     * collection that is O(n) wasted arithmetic per read; for an empty one it was a live embedding call per
     * read, which is how this surfaced.
     *
     * <p>One flag cannot serve two independent readers: whichever one clears it starves the other. Two
     * flags set by the same events, each cleared by the reader that consumes it, is the whole fix -- the
     * same shape {@link VectorCacheSlot} already uses to give each {@code @Vectorize} field independent
     * cache validity.
     */
    private volatile boolean centroidDirty = true;
    private volatile EmbeddingVector vector;
    private volatile EmbeddingVector summaryVector;
    private final Set<IdentityWeakReference> dependents = ConcurrentHashMap.newKeySet();

    /**
     * A per-field cache slot, keyed by field name, lazily created on first access -- backs each
     * {@code @Vectorize} field's own {@code fooVector()} accessor. Deliberately holds only
     * {@link VectorCacheSlot} instances (not a boxed value type like {@code VectorizableString}, which
     * lives in {@code javai-model} and can't be referenced from this module) -- the actual text to embed is
     * re-read from the field via reflection each time it's needed, not cached here.
     */
    private final Map<String, VectorCacheSlot> fieldSlots = new ConcurrentHashMap<>();

    /** Backs {@code concatenatedTextVector()} -- the concatenated-text embedding across every
     *  {@code @Vectorize} field, kept as its own slot separate from any individual field's. */
    private final VectorCacheSlot concatenatedTextSlot = new VectorCacheSlot();

    /** Assigned once, at construction, purely to give whole-subgraph lock acquisition (persistence flushes)
     *  a stable, deadlock-free global order -- never used for anything else. */
    private final long sequenceNumber = SEQUENCE_GENERATOR.incrementAndGet();

    /**
     * Serves two purposes, both needing the same per-object mutual exclusion: (1) held for the duration of
     * a persistence flush touching this object -- see {@code JavAIRuntime.runWithSubgraphLockedForPersistence}
     * -- acquired in {@link #sequenceNumber()} order across an entire reachable subgraph, never nested with
     * another object's lock in the reverse order; and (2) under
     * {@code EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY}, held by a getter for the full duration of a
     * blocking recompute (so a concurrent getter or setter genuinely waits rather than racing its own
     * redundant computation) and briefly by every setter, every mode, around its own bookkeeping -- the
     * latter is what makes purpose (1) actually true: without setters ever touching this lock, an ordinary
     * mutation could otherwise proceed completely unobstructed while a flush believes the subgraph is
     * frozen. {@link ReentrantLock} specifically because a single flush thread re-enters it (once already
     * held from (1)) when its own forced-accurate reads recurse through multiple fields.
     */
    private final Lock objectLock = new ReentrantLock();

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

    public VectorCacheSlot fieldSlot(String fieldName) {
        return fieldSlots.computeIfAbsent(fieldName, name -> new VectorCacheSlot());
    }

    /** Which fields have a slot so far -- slots are created lazily, so this is "what has been asked for",
     *  not "every {@code @Vectorize} field". Used to carry already-computed vectors from one instance of a
     *  logical entity to another (see {@code JavAIRuntime.transferComputedVectors}). */
    public Set<String> fieldSlotNames() {
        return Set.copyOf(fieldSlots.keySet());
    }

    public VectorCacheSlot concatenatedTextSlot() {
        return concatenatedTextSlot;
    }

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public Lock objectLock() {
        return objectLock;
    }

    @Override
    public boolean isFieldDirty() {
        return fieldDirty;
    }

    @Override
    public void markFieldDirty() {
        fieldDirty = true;
        // A collection's membership changing invalidates its centroid too -- see centroidDirty's javadoc.
        centroidDirty = true;
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
        // An element mutating reaches a containing collection only as SummaryDirty (propagateDirty marks
        // dependents, never their FieldDirty), and that same event invalidates the centroid.
        centroidDirty = true;
    }

    @Override
    public void clearSummaryDirty() {
        summaryDirty = false;
    }

    /** True when a JavAI collection's own centroid needs recomputing -- see {@link #centroidDirty}'s javadoc. */
    public boolean isCentroidDirty() {
        return centroidDirty;
    }

    /** Cleared only by {@code CollectionVectorSupport.vector}, the sole consumer of this flag. */
    public void clearCentroidDirty() {
        centroidDirty = false;
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
