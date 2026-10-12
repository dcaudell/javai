package dev.xtrafe.javai.completion;

/**
 * How {@link Cortex#completeTyped} asks again when a completion does not hold the typed value it was asked for
 * (OMI-640): given the request, what came back, and why it would not read, the one request to try instead.
 *
 * <p>The caller writes it, because the repair is prompt text, and this library writes none of a caller's prompts. A
 * typical repair repeats the request with the failed text and the failure's message appended, asking for the value
 * again.
 */
@FunctionalInterface
public interface CompletionRepair {

    CompletionRequest repair(CompletionRequest original, CompletionResult failed, CompletionResponseException why);
}
