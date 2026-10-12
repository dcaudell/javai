package dev.xtrafe.javai.completion;

import java.util.List;

/**
 * A batch of completions submitted together through {@link Cortex#submitBatch}, answered later at the provider's batch
 * price (OMI-641). A provider takes up to a day; a caller polls {@link #refresh()}, or keeps {@link #id()} and comes
 * back through {@link Cortex#batch(String)}, then reads {@link #results()} once the batch has {@link Status#ENDED}.
 */
public interface CompletionBatch {

    /** Where a batch stands. */
    enum Status {
        /** Still being answered (or validated, or finalized). */
        IN_PROGRESS,
        /** Cancelled by request; requests already answered keep their results. */
        CANCELING,
        /** Done: every request has a result or an error, which {@link #results()} returns. */
        ENDED
    }

    /**
     * One request's outcome, by its position in the submitted list: its result, or why it has none.
     *
     * @param index  the request's position in the list given to {@link Cortex#submitBatch}
     * @param result the completion, with its usage; {@code null} when {@code error} is set
     * @param error  why the request has no result (errored, cancelled, expired); {@code null} when it has one
     */
    record Item(int index, CompletionResult result, String error) {

        public boolean succeeded() {
            return result != null;
        }
    }

    /** The provider's id for the batch: enough to find it again through {@link Cortex#batch(String)}. */
    String id();

    /** The status last read from the provider. */
    Status status();

    /** Reads the batch's status from the provider again, and returns it. */
    Status refresh();

    /** Asks the provider to stop the batch. */
    void cancel();

    /**
     * Every request's outcome, in submitted order, each request present exactly once.
     *
     * @throws IllegalStateException while the batch has not {@link Status#ENDED}
     */
    List<Item> results();
}
