package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;

/**
 * The interface the weaver ({@code javai-substrate}) implements on any {@code @JavAIVectorizable}
 * class. Never write {@code implements JavAIVectorizable} by hand -- the annotation
 * alone triggers full codegen. See doc/spec/vector-core.md for the full contract and
 * the object lifecycle state machine this interface's laziness depends on.
 */
public interface JavAIVectorizable {

    EmbeddingVector vector();

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
