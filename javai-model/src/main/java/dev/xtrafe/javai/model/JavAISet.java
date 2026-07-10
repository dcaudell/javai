package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.Set;

/** Strict superset of {@link java.util.Set}. See doc/spec/vector-collections.md. */
public interface JavAISet<T> extends Set<T>, JavAISortable<T>, JavAIVectorizable, Contextable {

    JavAIList<T> nearestN(EmbeddingVector reference, int n);

    JavAIList<T> filterByMinSimilarity(EmbeddingVector reference, double threshold);

    EmbeddingVector centroid();

    /** Renders each element via its own {@link Contextable} override where it has one, falling back to
     *  {@link PromptContext#defaultMarshall(Object)} otherwise -- see {@link CollectionVectorSupport#contextOf}. */
    @Override
    default String toContext(PromptContext prompt) {
        return CollectionVectorSupport.contextOf(this, prompt);
    }
}
