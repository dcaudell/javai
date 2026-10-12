package dev.xtrafe.javai.completion;

import java.util.List;

/**
 * A completion stopped because its model went on calling tools past {@link CompletionRequest#maxToolCalls()}, or
 * spent more than {@link CompletionRequest#maxToolLoopTokens()} doing so (OMI-643). Carries the calls it made, so a caller can see what the model was doing.
 */
public class CompletionToolLimitException extends CompletionException {

    private final List<CompletionToolCall> calls;

    public CompletionToolLimitException(int limit, List<CompletionToolCall> calls) {
        this("The model called tools more than " + limit + " times; stopped", calls);
    }

    /** Stopped for a reason of the caller's limits other than the call count -- the tokens spent, say. */
    public CompletionToolLimitException(String message, List<CompletionToolCall> calls) {
        super(message);
        this.calls = List.copyOf(calls);
    }

    /** Stopped because the loop spent more than {@code limit} tokens, input and output summed over its turns. */
    static CompletionToolLimitException tokens(int limit, int spent, List<CompletionToolCall> calls) {
        return new CompletionToolLimitException("The tool loop spent " + spent + " tokens, past its limit of " + limit
                + "; stopped", calls);
    }

    public List<CompletionToolCall> calls() {
        return calls;
    }
}
