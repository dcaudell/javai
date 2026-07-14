package dev.xtrafe.javai.model;

/**
 * Global, process-wide switch for how a woven class's vector caches stay current with respect to the
 * fields they're derived from -- configured once via {@link JavAIRuntime#configureConsistencyMode(EmbeddingConsistencyMode)},
 * ideally at startup alongside {@link JavAIRuntime#configureEmbeddingProvider}. Undefined behavior if
 * changed after the provider is already handling real traffic -- this is a plain, unguarded switch, not
 * something individual calls coordinate around mid-flight.
 *
 * <p>Neither mode ever stores a vector that doesn't correspond to some real value its field genuinely
 * held -- the difference is entirely about when a stale cache gets refreshed and whether a caller blocks
 * waiting for that to happen.
 */
public enum EmbeddingConsistencyMode {

    /**
     * Lazy recompute, but fully serialized: a stale read snapshots the field's current value and computes
     * inline, so the caller always gets a vector that was genuinely accurate at the moment of the call, even
     * under concurrent mutation. Mutation itself never triggers computation -- only a read of a dirty cache
     * does. The whole object is locked for the duration of a blocking computation, and every setter (on that
     * same object) briefly takes the same lock around its own bookkeeping -- so no more than one computation
     * for this object is ever in flight at a time, and a setter genuinely waits out an in-progress read
     * rather than racing it. Expect this to be the slowest of the three modes under contention.
     */
    IMMEDIATE_CONSISTENCY,

    /**
     * Eager recompute, non-blocking reads: mutation dispatches a background computation immediately
     * (bounded by {@link JavAIRuntime#configureMaxConcurrentEmbeddingCalls(int)}), and a read simply
     * returns whatever's currently cached without waiting -- except the very first computation ever
     * performed for a given cache, which always blocks, since there is no prior real value to fall back on.
     * A read of an already-computed, still-dirty cache also triggers its own background recompute, but only
     * if one isn't already outstanding for the current generation -- so a burst of concurrent readers of a
     * stale value doesn't each fire a redundant, duplicate {@code embed()} call. The cache is only ever
     * updated by whichever completed computation represents the most recent mutation, so the system
     * converges to the field's final value even though intermediate reads may observe a stale one.
     */
    EVENTUAL_CONSISTENCY,

    /**
     * Eager recompute, exactly like {@link #EVENTUAL_CONSISTENCY} -- mutation dispatches a background
     * computation immediately, and setters never block. The difference is entirely on the read side: a read
     * of a dirty cache blocks until whatever computation is currently outstanding for it completes (joining
     * that shared result rather than starting its own redundant one, and firing a fresh computation to join
     * only if nothing is currently outstanding). Because the field may be mutated again while a reader is
     * waiting, a blocked reader can return a vector for a value the field has since moved on from -- but that
     * vector is always accurate to *some* real value the field genuinely held, never wrong, and reflects
     * whatever was the most recently dispatched computation at the moment the reader started waiting.
     */
    COALESCED_CONSISTENCY
}
