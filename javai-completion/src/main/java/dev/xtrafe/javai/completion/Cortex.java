package dev.xtrafe.javai.completion;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.Function;

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

    /**
     * Answers a request that offers the model {@link CompletionRequest#tools() tools} (OMI-643): each call the model
     * makes is run by {@code executor} and its result handed back, until the model answers or has made
     * {@link CompletionRequest#toolCallLimit()} calls ({@link CompletionToolLimitException}). The result's
     * {@link CompletionResult#toolCalls()} lists every call. An exception from {@code executor} ends the completion.
     *
     * @throws UnsupportedOperationException for a Cortex that cannot offer tools -- {@link CortexReplicate}
     */
    default CompletionResult complete(CompletionRequest request, CompletionToolExecutor executor) {
        throw new UnsupportedOperationException(providerId() + " cannot offer a model tools in this library");
    }

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

    /**
     * Completes {@code request} and reads its typed value with {@code reader} -- {@code r -> r.as(Foo.class)}, or
     * {@code asList}/{@code asSet} -- asking again once, through {@code repair}, when it does not hold one (OMI-640).
     *
     * <p>A completion with no value at all counts as a failure here ({@link CompletionResponseException.Reason#NO_VALUE}),
     * since a caller that asks for a typed value means to get one. Every other failure is the reader's own. With a
     * {@code null} {@code repair} the first failure is thrown; otherwise {@code repair} writes the one request to try
     * instead, and a second failure is thrown with the first as its cause. Each thrown failure carries the text it was
     * about. A failure to complete at all (the provider's error) is not repaired.
     */
    default <V> V completeTyped(CompletionRequest request, Function<CompletionResult, Optional<V>> reader,
            CompletionRepair repair) {
        CompletionResult first = complete(request);
        CompletionResponseException why;
        try {
            return valueOf(first, reader);
        } catch (CompletionResponseException failed) {
            why = failed.about(first.text());
        }
        if (repair == null) {
            throw why;
        }
        CompletionResult second = complete(repair.repair(request, first, why));
        try {
            return valueOf(second, reader);
        } catch (CompletionResponseException failed) {
            throw new CompletionResponseException(failed.reason(), "Still no readable value after one repair: "
                    + failed.getMessage(), second.text(), why);
        }
    }

    private static <V> V valueOf(CompletionResult result, Function<CompletionResult, Optional<V>> reader) {
        return reader.apply(result).orElseThrow(() -> new CompletionResponseException(
                CompletionResponseException.Reason.NO_VALUE, "The response holds no value", result.text(), null));
    }

    /**
     * Submits {@code requests} as one batch, answered later at the provider's batch price (OMI-641); read the outcome
     * through the returned {@link CompletionBatch}. Each request is sent as {@link #complete} would send it.
     *
     * @throws UnsupportedOperationException for a Cortex with no batch API -- every one but {@link CortexAnthropic}
     *                                       and {@link CortexOpenAI}
     */
    default CompletionBatch submitBatch(List<CompletionRequest> requests) {
        throw new UnsupportedOperationException(providerId() + " has no batch API in this library");
    }

    /**
     * The batch {@link #submitBatch} returned under {@code id}, found again (after a restart, say).
     *
     * @throws UnsupportedOperationException for a Cortex with no batch API
     */
    default CompletionBatch batch(String id) {
        throw new UnsupportedOperationException(providerId() + " has no batch API in this library");
    }

    /** {@code "openai"}, {@code "anthropic"}, {@code "groq"}, {@code "mistral"}, {@code "vllm"},
     *  {@code "ollama"}, or {@code "replicate"} -- stable identifier, not a display name. */
    String providerId();

    /** The specific model this Cortex is configured to call, e.g. {@code "gpt-4.1"}, {@code "qwen3:8b"}. */
    String modelId();

    /** This Cortex's configured model's context window, in tokens -- best-effort (see {@link ContextWindows}),
     *  overridable per-Cortex at construction time. {@link CompletionRequest#render(int)} uses this to size
     *  the character budget it hands down to a {@code PromptContext}. */
    int contextWindowTokens();
}
