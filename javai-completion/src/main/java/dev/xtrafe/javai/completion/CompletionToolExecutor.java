package dev.xtrafe.javai.completion;

/**
 * Runs the tool calls a model makes while it answers (OMI-643): given the call, with its arguments, the text the model
 * reads back as the tool's result. The caller's own code -- a lookup, a dice roll -- so it runs in the caller's
 * transaction, on the caller's thread. An exception it throws ends the completion with a {@link CompletionException}
 * carrying it.
 */
@FunctionalInterface
public interface CompletionToolExecutor {

    /**
     * @param call the call; {@link CompletionToolCall#result()} and {@link CompletionToolCall#error()} are {@code null}
     * @return what the tool returned, as the model will read it
     */
    String execute(CompletionToolCall call);
}
