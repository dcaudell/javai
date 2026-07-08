/**
 * Vector Core: {@link EmbeddingVector}, {@link JavAIVectorizable}, the dirty-state propagation engine
 * ({@link JavAIRuntime}, {@link JavAIDirtyTracking}, {@link DirtyTrackingSupport} -- see the object
 * lifecycle state machine in doc/spec/vector-core.md), the reflection-based {@code query()} graph walk,
 * a CPU cosine-similarity backend ({@link VectorMath}), and a real embedding provider
 * ({@link JavAIEmbeddingProvider}, {@link TextEmbeddingsInferenceProvider} against the {@code docker/}
 * TEI sidecar).
 *
 * <p><b>Module-placement note:</b> {@link JavAISortable}, {@link JavAIList},
 * {@link JavAISet}, and {@link JavAIMap} live here rather than in javai-collections,
 * even though doc/spec/vector-collections.md discusses them as part of the Vector
 * Collections extension area. Reason: {@link JavAIVectorizable#query} returns
 * {@code JavAIList<T>}, and javai-collections depends on javai-runtime, not the
 * reverse -- putting JavAIList in javai-collections would create a circular module
 * dependency. javai-collections holds KnowledgeGraph/SubgraphResult/VectorIndex, which
 * depend on the types here. The area taxonomy in the whitepaper is conceptual; this is
 * the compile-order-correct physical split.
 */
package dev.xtrafe.javai.runtime;
