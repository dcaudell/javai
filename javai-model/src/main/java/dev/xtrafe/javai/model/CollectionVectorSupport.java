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

    public static EmbeddingVector summaryVector(DirtyTrackingSupport state, Collection<?> elements) {
        if (state.cachedSummaryVector() == null || state.isSummaryDirty()) {
            if (!JavAIRuntime.enterSummaryComputation(elements)) {
                // Cycle: this collection is already being summarized further up this same call stack.
                // Treat as a leaf -- see JavAIRuntime.summaryVector()'s identical guard for the full
                // rationale (doc/spec/vector-core.md's cycle-safety rule for the recursive formula).
                return vector(state, elements);
            }
            try {
                EmbeddingVector own = vector(state, elements);
                // A collection holding nothing vectorizable has no content of its own and no children to
                // sum, so its summary is absent -- it contributes nothing to whatever contains it, rather
                // than contributing the embedding of a space (OMI-187; see EmbeddingVector.absent()).
                EmbeddingVector recomputed;
                if (own.isAbsent()) {
                    recomputed = EmbeddingVector.absent();
                } else {
                    float[] sum = own.values().clone();
                    for (Object element : elements) {
                        if (element instanceof JavAIVectorizable child) {
                            EmbeddingVector childSummary = child.summaryVector();
                            if (!childSummary.isAbsent() && childSummary.dims() == sum.length) {
                                VectorMath.addWeighted(sum, childSummary.values(), JavAIRuntime.DEFAULT_SUMMARY_DECAY);
                            }
                        }
                    }
                    recomputed = new EmbeddingVector(
                            VectorMath.normalize(sum), own.modelId(), sum.length, Instant.now());
                }
                state.cacheSummaryVector(recomputed);
                state.clearSummaryDirty();
            } finally {
                JavAIRuntime.exitSummaryComputation(elements);
            }
        }
        return state.cachedSummaryVector();
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
