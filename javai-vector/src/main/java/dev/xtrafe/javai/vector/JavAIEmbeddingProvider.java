package dev.xtrafe.javai.vector;

import dev.xtrafe.javai.annotations.Costly;
import dev.xtrafe.javai.annotations.Nondeterministic;

/**
 * Pluggable, versioned embedding-model provider (doc/spec/vector-core.md). {@link JavAIRuntime} calls
 * this at most once per object per dirty cycle -- never eagerly, never in a loop -- to turn the text
 * derived from an object's {@code @Vectorize} fields into an {@link EmbeddingVector}.
 */
public interface JavAIEmbeddingProvider {

    @Nondeterministic
    @Costly
    EmbeddingVector embed(String text);

    /**
     * Which model this provider embeds with, without performing an embedding to find out -- or {@code null}
     * if it cannot say.
     *
     * <p>Exists so the persistence layer can decide whether an already-stored vector is still valid before
     * spending a model call to recompute one it already has (OMI-187). Vectors are persisted per model, so
     * "is the stored row's model the one we would embed with now?" is the whole validity question, and
     * answering it by embedding something would defeat the purpose.
     *
     * <p>A {@code default} returning {@code null} rather than an abstract method, deliberately: this is a
     * published SPI, and adding an abstract method would break every third-party implementation at both
     * source and binary level. A provider that doesn't override this simply forgoes the optimization --
     * stored vectors are recomputed rather than reused, exactly as before. Every provider bundled with
     * JavAI overrides it.
     */
    default String modelId() {
        return null;
    }

    /**
     * The largest input this provider's model will actually consider, in tokens.
     *
     * <p>Resolved most-authoritative-first: a provider that can ask its own endpoint should do that and
     * cache the answer; otherwise {@link EmbeddingModelLimits}'s best-effort table applies; and an explicit
     * per-provider override always wins over both.
     *
     * <p><b>Why any of this is needed.</b> Providers disagree completely about what an over-long input
     * means, and the disagreement is invisible. Measured against a real Ollama instance (OMI-216): 324,000
     * characters -- roughly 80,000 tokens against {@code qwen3-embedding:0.6b}'s 32,768-token context --
     * returned HTTP 200 and an ordinary 1024-dimension vector. Silently truncated, with nothing in the
     * response saying so. TEI does the same by design ({@code "truncate": true}). OpenAI, by contrast,
     * rejects the request outright. So the same text yields a correct vector, a quietly wrong one, or an
     * exception depending only on which provider is configured.
     *
     * <p><b>This is a token count, and JavAI has no tokenizer.</b> Any enforcement built on it is therefore
     * approximate and must err small -- see {@link EmbeddingInputLimits}. Stated plainly rather than
     * implying a precision this cannot deliver.
     *
     * <p>A {@code default} returning the table's answer, so no existing implementation breaks and a
     * third-party provider gets conservative behaviour for free.
     */
    default int maxInputTokens() {
        return EmbeddingModelLimits.lookup(modelId());
    }

    /**
     * The most inputs this provider will accept in one {@link #embedAll} request.
     *
     * <p>Distinct from {@link #maxInputTokens()}, which bounds one text against the model's context window
     * and is applied <em>per member</em> of a batch. This bounds the batch itself, and the two are genuinely
     * independent: a hundred individually-legal texts are still a request no provider agreed to accept.
     *
     * <p>Concrete, not hypothetical: Text Embeddings Inference defaults to <b>32</b>
     * ({@code --max-client-batch-size}), below the 100 this library chunked at, so a default TEI deployment
     * rejects a full batch outright. OpenAI documents 2048.
     *
     * <p>A {@code default} rather than an abstract method, for the reason given on {@link #modelId()}: this
     * is a published SPI. A provider that doesn't override it gets
     * {@link EmbeddingBatchLimits#DEFAULT_MAX_BATCH_SIZE}, which is the chunk size already in use -- so
     * declaring nothing changes nothing. Non-positive means "no count limit".
     */
    default int maxBatchSize() {
        return EmbeddingBatchLimits.DEFAULT_MAX_BATCH_SIZE;
    }

    /**
     * The most tokens, in total across every input, this provider will accept in one {@link #embedAll}
     * request.
     *
     * <p>The other half of {@link #maxBatchSize()}, and needed separately because the two fail in opposite
     * directions: a batch of eight enormous documents breaks a token ceiling while satisfying any count
     * ceiling, and a batch of two thousand one-word strings does the reverse. TEI defaults to 16,384
     * ({@code --max-batch-tokens}); OpenAI documents 300,000 per embeddings request.
     *
     * <p>Like {@link #maxInputTokens()} this is a token count and JavAI has no tokenizer, so any enforcement
     * built on it is approximate and errs small -- see {@link EmbeddingInputLimits}. Non-positive means "no
     * size limit".
     */
    default int maxBatchTokens() {
        return EmbeddingBatchLimits.DEFAULT_MAX_BATCH_TOKENS;
    }

    /**
     * Embeds several texts in one go, returning one vector per input, in order.
     *
     * <p>The reason this exists is latency, not call count. {@link #embed} is one text per HTTP round trip,
     * and every real embedding API accepts an array -- so seeding 1,400 tags is 1,400 sequential round trips
     * (~14s at the ~10ms per embed measured in OMI-187) versus roughly a dozen batched ones. That is worth
     * considerably more than the 3x constant-factor waste OMI-187 removed, and it is why the ticket asked
     * for a documented bulk-seed path rather than only a fix.
     *
     * <p>The {@code default} loops, so a provider that has not implemented batching stays correct and simply
     * gains nothing; overriding it is a pure latency optimization with no semantic difference. Callers can
     * therefore use this unconditionally rather than branching on provider capability.
     *
     * <p><b>One vector per input, in the order the inputs were given</b> -- the whole contract, and the one
     * an implementation can plausibly get wrong. Some APIs (OpenAI's, and vLLM's copy of it) return their
     * rows carrying an explicit index and do not promise to emit them in request order, so an implementation
     * that trusts array position pairs every text with the wrong vector. That failure is silent: each vector
     * is individually well-formed, and the mistake only ever surfaces as inexplicably poor search results.
     * An implementation must order by whatever the API says the order is, and must refuse a response whose
     * row count doesn't match the request rather than returning a short or padded list.
     *
     * <p><b>This does not split an over-large list</b>, deliberately -- it sends exactly one request for
     * exactly what it is given, so one call is one round trip, which is what makes the concurrency gate's
     * one-permit-per-call accounting true and what lets a test count round trips at all. Respecting
     * {@link #maxBatchSize()}/{@link #maxBatchTokens()} is the caller's job, and
     * {@code JavAIRuntime.precomputeVectors} is the caller that does it (via
     * {@link EmbeddingBatchLimits#split}). A caller handing this method ten thousand texts directly has
     * chosen that request.
     *
     * @param texts the texts to embed, in order
     * @return one vector per input text, same order, same size
     */
    @Nondeterministic
    @Costly
    default java.util.List<EmbeddingVector> embedAll(java.util.List<String> texts) {
        java.util.List<EmbeddingVector> vectors = new java.util.ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }
}
