package dev.xtrafe.javai.persistence;

/**
 * When a write's {@code @Summary} consequences are settled up -- the per-write opt-out from OMI-255.
 *
 * <p>A container's summary vector is <b>derived</b> state: it is a function of the graph beneath it, so it
 * can always be recomputed from what is committed. JavAI exploits that by never writing it inside the
 * caller's transaction at all. The mutation records that the container's summary is owed a recomputation
 * (an insert of a fresh row, which cannot collide with anything), and the recomputation itself happens
 * afterwards, against committed state, under a lock that makes concurrent recomputations of one container
 * serialise instead of overwrite. This enum only chooses <em>when</em> that second step runs.
 *
 * <p><b>The queue is durable, so neither option can lose the recomputation.</b> The difference is how long
 * the summary may lag, never whether it happens.
 *
 * @see JavAIRepository#save(Object, SummaryPolicy)
 */
public enum SummaryPolicy {

    /**
     * The default, and what plain {@link JavAIRepository#save(Object)} does: recompute as soon as the
     * caller's transaction commits, before {@code save} returns.
     *
     * <p>So a caller who saves and then searches by summary vector sees their own write reflected, with no
     * window to reason about. The cost is one short extra transaction per save, which is why the other
     * option exists.
     */
    RECOMPUTE_AFTER_COMMIT,

    /**
     * Record what is owed and return -- leave the recomputation to a later drain.
     *
     * <p>For write-heavy paths where throughput matters more than how current a container's summary is:
     * bulk ingestion, user-generated content arriving in bursts, a seeding loop. The container's stored
     * summary keeps its previous value (it does not become null or wrong-shaped) until something drains the
     * queue -- the next {@code RECOMPUTE_AFTER_COMMIT} save of that container, an explicit
     * {@link JavAIPI#drainPendingSummaries}, or a {@code reindex}.
     *
     * <p>⚠️ Choosing this means accepting that a summary-vector search may not yet reflect this write. That
     * is a real trade, not a free optimisation: if nothing in the deployment ever drains the queue, the
     * summaries stay stale indefinitely and nothing will fail to tell you so.
     */
    QUEUE_ONLY
}
