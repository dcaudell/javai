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
 *
 * <p>{@link Contextable} and {@link PromptContext} (Completion Fabric's own RAG-integration primitives)
 * live here for the identical reason: {@code Contextable.toContext(PromptContext)} references
 * {@code PromptContext}, and {@link JavAIList}/{@link JavAISet}/{@link JavAIMap} all implement
 * {@code Contextable} -- so both types must live wherever those three collection interfaces do, or
 * javai-runtime would need an illegal reverse dependency on javai-completion. javai-collections'
 * KnowledgeGraph/SubgraphResult/VectorIndex do not yet implement {@code Contextable} -- deferred
 * pending a cycle-safe design, since GSON's default reflection (unlike this package's own
 * {@code enterSummaryComputation}/{@code exitSummaryComputation} guard) has no protection against the
 * cycles a graph-shaped type can legitimately contain. See {@code PromptContext}'s own javadoc.
 */
package dev.xtrafe.javai.runtime;
