package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.Map;

/**
 * Strict superset of {@link java.util.Map}. Implements {@code JavAISortable<V>}, not
 * {@code JavAISortable<K>} -- keys are typically identifiers with no embedding of their
 * own, so ranking and {@code centroid()} operate over values. See
 * doc/spec/vector-collections.md.
 */
public interface JavAIMap<K, V> extends Map<K, V>, JavAISortable<V>, JavAIVectorizable, Contextable {

    EmbeddingVector centroid();

    /** Renders each value (not key -- see this interface's own javadoc on why ranking/centroid() are
     *  value-based) via its own {@link Contextable} override where it has one, falling back to
     *  {@link PromptContext#defaultMarshall(Object)} otherwise -- see {@link CollectionVectorSupport#contextOf}. */
    @Override
    default String toContext(PromptContext prompt) {
        return CollectionVectorSupport.contextOf(values(), prompt);
    }
}
