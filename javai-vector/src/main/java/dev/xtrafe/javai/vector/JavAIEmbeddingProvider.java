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
}
