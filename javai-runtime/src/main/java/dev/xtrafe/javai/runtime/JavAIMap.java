package dev.xtrafe.javai.runtime;

import java.util.Map;

/**
 * Strict superset of {@link java.util.Map}. Implements {@code JavAISortable<V>}, not
 * {@code JavAISortable<K>} -- keys are typically identifiers with no embedding of their
 * own, so ranking and {@code centroid()} operate over values. See
 * doc/spec/vector-collections.md.
 */
public interface JavAIMap<K, V> extends Map<K, V>, JavAISortable<V>, JavAIVectorizable {

    EmbeddingVector centroid();
}
