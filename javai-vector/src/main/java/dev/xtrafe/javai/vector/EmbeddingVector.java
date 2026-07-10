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

    public EmbeddingVector {
        if (values == null || values.length != dims) {
            throw new IllegalArgumentException("dims must match values.length");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required for version comparability");
        }
    }
}
