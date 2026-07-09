package dev.xtrafe.javai.completion;

import java.time.Instant;

/**
 * The text result of a {@link Cortex#complete(CompletionRequest)} call, plus enough metadata to be
 * self-explanatory without cross-referencing which Cortex produced it.
 *
 * <p>Structured/schema-typed output (a second, schema-bound variant of this type, mentioned in
 * {@code doc/spec/completion-fabric.md}'s primitives table) is out of scope for this pass -- see this
 * module's README for what's deferred and why.
 */
public record CompletionResult(String text, String providerId, String modelId, Instant completedAt) {

    public CompletionResult {
        if (text == null) {
            throw new IllegalArgumentException("CompletionResult text must not be null");
        }
        if (providerId == null) {
            throw new IllegalArgumentException("CompletionResult providerId must not be null");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("CompletionResult modelId must not be null");
        }
        if (completedAt == null) {
            completedAt = Instant.now();
        }
    }
}
