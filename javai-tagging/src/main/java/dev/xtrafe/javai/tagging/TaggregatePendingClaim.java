package dev.xtrafe.javai.tagging;

import java.util.List;
import java.util.UUID;

/**
 * One claimed batch from {@code javai_taggregate_pending}: the distinct aggregates owed a recompute, and
 * the exact rows that named them -- the same shape as {@code javai-persistence}'s
 * {@code PendingSummaries.Claim}, for the same reason: deletion happens by row id, never by aggregate, so a
 * row enqueued after the claim (describing a mutation the finished recompute may not have seen) survives
 * for the next sweep.
 */
record TaggregatePendingClaim(List<TaggableRef> aggregates, List<UUID> rowIds) {

    static final TaggregatePendingClaim EMPTY = new TaggregatePendingClaim(List.of(), List.of());

    boolean isEmpty() {
        return aggregates.isEmpty();
    }
}
