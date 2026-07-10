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
        // its own -- it's entirely derived from elements' own vectors. So it must also recompute when
        // SummaryDirty (an element changed), not just when this collection's own membership changed
        // (FieldDirty), or the centroid would go stale the moment an element mutates without itself
        // being added/removed.
        if (state.cachedVector() == null || state.isFieldDirty() || state.isSummaryDirty()) {
            state.cacheVector(computeCentroid(elements));
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
                float[] sum = own.values().clone();
                for (Object element : elements) {
                    if (element instanceof JavAIVectorizable child) {
                        EmbeddingVector childSummary = child.summaryVector();
                        if (childSummary.dims() == sum.length) {
                            VectorMath.addWeighted(sum, childSummary.values(), JavAIRuntime.DEFAULT_SUMMARY_DECAY);
                        }
                    }
                }
                EmbeddingVector recomputed =
                        new EmbeddingVector(VectorMath.normalize(sum), own.modelId(), sum.length, Instant.now());
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
        if (vectors.isEmpty()) {
            // No vectorizable elements (including the empty-collection case) -- still return a real,
            // correctly-dimensioned vector from the current model rather than a fabricated zero vector,
            // so combining it arithmetically with a parent's summaryVector() never hits a dims mismatch.
            return JavAIRuntime.embeddingProvider().embed("");
        }
        return VectorMath.centroid(vectors);
    }
}
