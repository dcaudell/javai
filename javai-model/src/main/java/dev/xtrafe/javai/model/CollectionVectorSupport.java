package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.VectorMath;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Shared {@code vector()}/{@code summaryVector()} arithmetic for every concrete JavAI collection
 * ({@link JavAIArrayList}, {@link JavAILinkedHashSet}, {@link JavAILinkedHashMap}, and
 * {@code javai-collections}' {@code KnowledgeGraph}/{@code VectorIndex} implementations) -- each holds one
 * {@link DirtyTrackingSupport} directly (no reflection needed; unlike a woven user class, these are our
 * own code) and calls through to these statics rather than duplicating the same formula repeatedly.
 *
 * <p>Every element in a JavAI collection is implicitly a summary contributor -- no per-element
 * {@code @Summary} annotation is needed, since the collection itself is what a containing object marks
 * {@code @Summary} to opt in.
 *
 * <p>Public because {@code javai-collections} depends on {@code javai-model} and reuses this directly
 * rather than re-deriving the same centroid/decay-weighted-sum logic for {@code KnowledgeGraph}.
 */
public final class CollectionVectorSupport {

    private CollectionVectorSupport() {
    }

    public static EmbeddingVector vector(DirtyTrackingSupport state, Collection<?> elements) {
        // Unlike a plain object, a collection's "own vector" (its centroid) has no @Vectorize fields of
        // its own -- it's entirely derived from elements' own vectors. So it must recompute both when this
        // collection's membership changed and when an element mutated; CentroidDirty is set by each of
        // those events (see DirtyTrackingSupport.centroidDirty) and cleared here, by its only reader.
        //
        // This gate used to read `isFieldDirty() || isSummaryDirty()` directly. Both clauses were
        // necessary -- an element mutation reaches this collection only as SummaryDirty -- but SummaryDirty
        // is simultaneously summaryVector()'s own staleness signal, and only summaryVector() cleared it. A
        // collection read through vector() alone therefore recomputed its centroid on every read forever
        // (OMI-187). Two flags set by the same events, each cleared by the reader that consumes it.
        if (state.cachedVector() == null || state.isCentroidDirty()) {
            state.cacheVector(computeCentroid(elements));
            state.clearCentroidDirty();
            state.clearFieldDirty();
        }
        return state.cachedVector();
    }

    /**
     * A collection's centroid restricted to one embedding model -- the mean of each member's own
     * {@code vector(modelId)} (OMI-290).
     *
     * <p>Uncached, unlike {@link #vector}: that one is gated by {@code CentroidDirty}, a single flag with a
     * single reader, and adding a second reader per model is exactly the shape OMI-187 established does not
     * work -- whichever reader clears the flag starves the others. Recombining already-computed member
     * vectors is cheap enough that the flag is not worth generalising for it.
     */
    public static EmbeddingVector vector(DirtyTrackingSupport state, Collection<?> elements, String modelId) {
        List<EmbeddingVector> vectors = new ArrayList<>(elements.size());
        for (Object element : elements) {
            if (element instanceof JavAIVectorizable vectorizable) {
                vectors.add(vectorizable.vector(modelId));
            }
        }
        // VectorMath skips absent members, so a collection where only some members carry this model
        // averages over exactly those -- and one where none do is itself absent.
        return VectorMath.centroid(vectors);
    }

    /**
     * A collection's decay-weighted summary restricted to one embedding model (OMI-290) -- the same formula
     * as {@link #summaryVector(DirtyTrackingSupport, Collection)} over the same members, admitting only
     * vectors that model produced.
     *
     * <p>This is what makes a container of images summarizable by what its images <em>look</em> like, in a
     * model no text field on any of them was ever embedded under.
     */
    public static EmbeddingVector summaryVector(DirtyTrackingSupport state, Collection<?> elements,
            String modelId) {
        if (!JavAIRuntime.enterSummaryComputation(elements)) {
            return vector(state, elements, modelId); // cycle: treat as a leaf, as the unqualified form does
        }
        try {
            List<VectorMath.WeightedVector> terms = new ArrayList<>();
            terms.add(new VectorMath.WeightedVector(vector(state, elements, modelId), 1.0));
            for (Object element : elements) {
                if (element instanceof JavAIVectorizable child) {
                    terms.add(new VectorMath.WeightedVector(
                            child.summaryVector(modelId), JavAIRuntime.DEFAULT_SUMMARY_DECAY));
                }
            }
            return VectorMath.normalize(VectorMath.weightedSum(terms));
        } finally {
            JavAIRuntime.exitSummaryComputation(elements);
        }
    }

    public static EmbeddingVector summaryVector(DirtyTrackingSupport state, Collection<?> elements) {
        if (state.cachedSummaryVector() == null || state.isSummaryDirty()) {
            if (!JavAIRuntime.enterSummaryComputation(elements)) {
                // Cycle: this collection is already being summarized further up this same call stack.
                // Treat as a leaf -- see JavAIRuntime.summaryVector()'s identical guard for the full
                // rationale (doc/spec/vector-core.md's cycle-safety rule for the recursive formula).
                return vector(state, elements);
            }
            try {
                // The collection's own centroid at full weight, plus each member's summary at the decay
                // factor -- the same formula, and now literally the same code, as an object's own
                // summaryVector(). A collection with nothing to contribute is absent rather than a
                // fabricated vector (OMI-187; see EmbeddingVector.absent()).
                //
                // Two behaviours changed here with OMI-218, both to stop this path disagreeing with the
                // object path over the same question:
                //
                //  - An absent centroid no longer short-circuits the whole summary to absent. It did, on the
                //    reasoning that a collection with no vectorizable content has "no children to sum" --
                //    but a member whose own vector() is absent can still have a non-absent summaryVector()
                //    (its own fields empty, its children not). JavAIRuntime.summaryVector never made that
                //    assumption about an object's own vector; this no longer makes it about a collection's.
                //  - A member whose dimensionality disagrees was silently skipped. It now throws, via
                //    VectorMath. Silently dropping a member from an aggregate is exactly the kind of quietly
                //    wrong answer that is impossible to notice from the outside.
                List<VectorMath.WeightedVector> terms = new ArrayList<>();
                terms.add(new VectorMath.WeightedVector(vector(state, elements), 1.0));
                for (Object element : elements) {
                    if (element instanceof JavAIVectorizable child) {
                        terms.add(new VectorMath.WeightedVector(
                                child.summaryVector(), JavAIRuntime.DEFAULT_SUMMARY_DECAY));
                    }
                }
                EmbeddingVector recomputed = VectorMath.normalize(VectorMath.weightedSum(terms));
                state.cacheSummaryVector(recomputed);
                state.clearSummaryDirty();
            } finally {
                JavAIRuntime.exitSummaryComputation(elements);
            }
        }
        return state.cachedSummaryVector();
    }

    /**
     * A collection's aggregated concatenated text: its members' own text, in iteration order (OMI-191).
     *
     * <p>Note the asymmetry with {@link #summaryVector}, which treats every element as an implicit summary
     * contributor. Here each member must itself opt in via {@code @Summary(concatenate = true)} on its type,
     * because contributing text is a claim about what the text means, not just arithmetic.
     */
    public static String concatenatedText(Object collection, Collection<?> elements) {
        return JavAIRuntime.concatenatedTextOfCollection(collection, elements);
    }

    /** The embedding of {@link #concatenatedText}, cached in the collection's own slot. */
    public static EmbeddingVector concatenatedTextVector(DirtyTrackingSupport state, Object collection,
            Collection<?> elements) {
        return JavAIRuntime.collectionConcatenatedTextVector(state, collection, elements);
    }

    /** Call after any mutation: invalidates this collection's own caches and notifies its dependents. */
    public static void onMutated(DirtyTrackingSupport state, Object owner) {
        state.markFieldDirty();
        state.markSummaryDirty();
        JavAIRuntime.propagateDirty(owner);
    }

    public static double similarityOf(Object element, EmbeddingVector reference) {
        if (element instanceof JavAIVectorizable vectorizable) {
            return VectorMath.cosineSimilarity(vectorizable.vector(), reference);
        }
        return Double.NEGATIVE_INFINITY;
    }

    /**
     * Shared {@code toContext()} body for {@link JavAIList}/{@link JavAISet}/{@link JavAIMap} -- delegates
     * per-element rather than letting GSON reflect the whole collection as one opaque JSON array, so an
     * element's own {@link Contextable} override (if it has one) is respected.
     */
    public static String contextOf(Collection<?> elements, PromptContext prompt) {
        StringBuilder buffer = new StringBuilder();
        boolean first = true;
        for (Object element : elements) {
            String rendered = element instanceof Contextable contextable
                    ? contextable.toContext(prompt)
                    : prompt.defaultMarshall(element);
            if (!first) {
                buffer.append("\n\n");
            }
            buffer.append(rendered);
            first = false;
        }
        return buffer.toString();
    }

    private static EmbeddingVector computeCentroid(Collection<?> elements) {
        List<EmbeddingVector> vectors = new ArrayList<>(elements.size());
        for (Object element : elements) {
            if (element instanceof JavAIVectorizable vectorizable) {
                vectors.add(vectorizable.vector());
            }
        }
        // No vectorizable elements (including the empty-collection case) yields an absent vector, which
        // every arithmetic site skips -- so there is no dims mismatch to pre-empt and nothing to fabricate.
        //
        // This used to call embeddingProvider().embed(""), to guarantee a real, correctly-dimensioned
        // vector. It was the single largest source of wasted embedding calls in the codebase (OMI-187:
        // one live round trip per empty collection, per read, across every graph shape measured), and
        // because real providers substitute a space for an empty input it also injected the embedding of
        // a space into every ancestor's summaryVector(). VectorMath.centroid now returns absent for an
        // empty input directly, so this method has no special case left to write.
        return VectorMath.centroid(vectors);
    }
}
