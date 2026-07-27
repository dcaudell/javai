package dev.xtrafe.javai.vector;

import java.time.Instant;

/**
 * A versioned embedding vector -- deliberately not a bare {@code float[]}, so old and new
 * embeddings can coexist during a model migration (see doc/spec/persistence-bridge.md's
 * expand/contract pattern). {@code modelId} is what {@code EmbeddingVector}-aware code
 * checks before treating two vectors as comparable.
 *
 * @param values          the embedding, dimension order matching {@code modelId}
 * @param modelId         identifies which embedding model produced this vector
 * @param dims            {@code values.length}, kept explicit for quick validation
 * @param computedAt       when this vector was produced
 */
public record EmbeddingVector(float[] values, String modelId, int dims, Instant computedAt) {

    /**
     * {@link #absent()}'s {@code modelId}. Deliberately not a real model name and deliberately not blank
     * (the canonical constructor rejects blank), so an absent vector can never be mistaken for, or compared
     * against, a vector some real model produced.
     */
    private static final String ABSENT_MODEL_ID = "<absent>";

    private static final EmbeddingVector ABSENT =
            new EmbeddingVector(new float[0], ABSENT_MODEL_ID, 0, Instant.EPOCH);

    public EmbeddingVector {
        if (values == null || values.length != dims) {
            throw new IllegalArgumentException("dims must match values.length");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required for version comparability");
        }
    }

    /**
     * The vector of <em>nothing</em>: an object or collection with no embeddable content at all. Zero
     * dimensions -- all dimensions and none -- so it is excluded from every calculation rather than
     * participating in one.
     *
     * <p>Introduced by OMI-187. Previously "no content" was represented by embedding the empty string, and
     * because real providers reject a genuinely empty input, {@code EmbeddingProviderOllama}/{@code OpenAI}
     * substitute a single space -- so every empty collection paid a live model round trip to obtain
     * <em>the embedding of a space</em>, then contributed that arbitrary, content-free direction to every
     * ancestor's {@code summaryVector()} with the usual decay weight. Both the cost and the distortion were
     * invisible in the hermetic test suite, whose fake provider happens to hash the empty string to the
     * zero vector.
     *
     * <p>An absent vector is a value, not a null: nothing has to null-check a return, and
     * {@link #isAbsent()} is the one question callers ask. Arithmetic skips it, similarity ranks it last,
     * and persistence writes no row for it.
     */
    public static EmbeddingVector absent() {
        return ABSENT;
    }

    /**
     * True when this vector represents no embeddable content -- see {@link #absent()}. Zero dimensionality
     * is the definition rather than a sentinel value, so an absent vector cannot collide with any real
     * model's output.
     */
    public boolean isAbsent() {
        return dims == 0;
    }
}
