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
     *  {@code @Vectorize} fields in a multi-modal future) without changing what this method means.
     *
     *  <p><b>Opt-in (OMI-191).</b> Returns {@link EmbeddingVector#absent()} -- costing nothing, embedding
     *  nothing -- unless this object participates via {@code @Summary(concatenate = true)}, either on its
     *  own type (embed my own fields) or on a field (absorb that child's or collection's text). Before that
     *  flag existed this was computed for every vectorizable that was ever asked, at the price of a real
     *  model call, and stored and searchable nowhere. */
    EmbeddingVector concatenatedTextVector();

    /**
     * The assembled concatenated text behind {@link #concatenatedTextVector()} -- the string that gets
     * embedded, before it is embedded (OMI-191).
     *
     * <p>Public rather than internal for two reasons. Persistence stores this text alongside its vector, so
     * that re-embedding under a different model is a pure re-embed rather than a fresh walk of the object
     * graph; and it is the recursion step itself -- a parent absorbing a child asks the child for exactly
     * this, so each class contributes using its own knowledge of its own {@code @Vectorize} fields.
     *
     * <p><b>{@code null} means there is no text</b> -- this object does not participate, or participates and
     * has nothing to contribute. Deliberately not the empty string: {@code ""} is a <em>value</em>, and a
     * value is something a provider will happily embed. That is the exact mistake OMI-187 removed from the
     * collection path, where embedding {@code ""} cost a live model call per empty collection per read and
     * (because real providers substitute a space for an empty input) injected the embedding of a space into
     * every ancestor's summary vector. {@link EmbeddingVector#absent()} exists so "there is nothing here" is
     * representable rather than approximated; {@code null} is that same distinction one level up, in the
     * text this method returns, and it maps straight onto an absent vector.
     *
     * <p>Callers must therefore treat {@code null} and {@code ""} alike as "contribute nothing" -- every
     * consumer in this runtime already does, and the embed path turns either into an absent vector rather
     * than a request.
     *
     * <p>A {@code default} rather than an abstract method so the hand-written {@code JavAIVectorizable}
     * implementations in this codebase's own tests (and any third party's) keep compiling; the weaver
     * overrides it on every woven class, and the JavAI collections override it to aggregate their members.
     * Returning {@code null} is also the honest default for an implementation that has not been taught to
     * assemble anything -- the same shape {@code JavAIEmbeddingProvider.modelId()} already uses for "cannot
     * say".
     */
    default String concatenatedText() {
        return null;
    }

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
