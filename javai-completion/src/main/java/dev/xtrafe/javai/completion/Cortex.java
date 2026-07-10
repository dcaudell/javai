package dev.xtrafe.javai.completion;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * The object that's actually connected to an LLM backend and performs completions -- one Cortex per
 * provider connection, local or remote. Renames {@code doc/spec/completion-fabric.md}'s
 * {@code JavAICompletionProvider} to this project's chosen name for the concept.
 *
 * <p>Deliberately <b>not</b> registered/constructed through a central facade the way
 * {@code JavAIPI.repository(...)} is for {@code javai-persistence} -- that pattern exists there
 * specifically because Hibernate's {@code SessionFactory} metadata is immutable once built, so every
 * entity type has to be known up front. Nothing here has an equivalent shared, boot-once resource: each
 * Cortex is an independent object, constructed directly via its own builder
 * ({@code CortexOpenAI.builder()...build()}, etc.), with no registration step and no bootstrap ordering
 * requirement. Constructing several Cortices -- local and remote, side by side -- is just constructing
 * several plain objects; see this module's README for a worked example.
 */
public interface Cortex {

    /** Blocking call -- sync-looking regardless of whether the provider is local or a network round-trip. */
    CompletionResult complete(CompletionRequest request);

    /** Token-streaming variant, backed by {@link Flow}; opt-in, not the default call shape. */
    void completeStreaming(CompletionRequest request, Flow.Subscriber<String> subscriber);

    /** Ergonomic overload for the common case: a simple per-chunk callback instead of a full
     *  {@link Flow.Subscriber}, wrapping {@code onChunk} into a minimal one that requests
     *  {@link Long#MAX_VALUE} items up front and ignores backpressure. */
    default void completeStreaming(CompletionRequest request, Consumer<String> onChunk) {
        completeStreaming(request, new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                onChunk.accept(item);
            }

            @Override
            public void onError(Throwable throwable) {
                if (throwable instanceof RuntimeException re) {
                    throw re;
                }
                throw new CompletionException("Streaming completion failed", throwable);
            }

            @Override
            public void onComplete() {
                // nothing to do -- onChunk already saw every token
            }
        });
    }

    /** {@code "openai"}, {@code "anthropic"}, {@code "groq"}, {@code "vllm"}, {@code "ollama"}, or
     *  {@code "replicate"} -- stable identifier, not a display name. */
    String providerId();

    /** The specific model this Cortex is configured to call, e.g. {@code "gpt-4.1"}, {@code "qwen3:8b"}. */
    String modelId();

    /** This Cortex's configured model's context window, in tokens -- best-effort (see {@link ContextWindows}),
     *  overridable per-Cortex at construction time. {@link CompletionRequest#render(int)} uses this to size
     *  the character budget it hands down to a {@code PromptContext}. */
    int contextWindowTokens();
}
