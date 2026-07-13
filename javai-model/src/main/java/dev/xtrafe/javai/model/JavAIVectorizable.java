package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;

/**
 * The interface the weaver ({@code javai-substrate}) implements on any {@code @JavAIVectorizable}
 * class. Never write {@code implements JavAIVectorizable} by hand -- the annotation
 * alone triggers full codegen. See doc/spec/vector-core.md for the full contract and
 * the object lifecycle state machine this interface's laziness depends on.
 */
public interface JavAIVectorizable {

    /** This object's aggregate vector, composed from each {@code @Vectorize} field's own boxed vector
     *  (see {@code dev.xtrafe.javai.model.VectorizableString}) -- not a single embedding of concatenated
     *  text. See {@link #concatenatedTextVector()} for that. */
    EmbeddingVector vector();

    /** A single embedding of this object's {@code @Vectorize} fields concatenated into one text
     *  block -- a holistic representation an embedding model may capture relationships in that combining
     *  separately-embedded fields arithmetically ({@link #vector()}) cannot. Kept as its own explicit,
     *  stable accessor precisely so {@link #vector()} is free to evolve (e.g. incorporating non-text
     *  {@code @Vectorize} fields in a multi-modal future) without changing what this method means. */
    EmbeddingVector concatenatedTextVector();

    EmbeddingVector summaryVector();

    double similarityTo(JavAIVectorizable other);

    double similarityTo(EmbeddingVector reference);

    /**
     * Search this object's reachable graph for instances of {@code type}, ranked by
     * similarity to {@code reference}. Unbounded-but-cycle-safe: descends until a loop
     * or a leaf.
     */
    <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type);

    /** Same, with an explicit traversal depth limit. */
    <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth);

    /** Dynamic counterpart to the per-field {@code fieldNameVector()}-style accessors. */
    EmbeddingVector fieldVector(String fieldName);
}
