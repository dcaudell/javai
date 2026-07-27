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
