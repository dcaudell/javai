/**
 * Vector Collections: {@code KnowledgeGraph}/{@code SubgraphResult}, {@code VectorIndex},
 * and the {@link JavAIGraphNode}/{@link JavAIEdge} node/edge contracts.
 *
 * <p><b>Module-placement note:</b> {@code JavAISortable}, {@code JavAIList},
 * {@code JavAISet}, and {@code JavAIMap} live in {@code dev.xtrafe.javai.runtime}
 * (javai-runtime) instead of here, even though doc/spec/vector-collections.md discusses
 * them as part of this extension area's primitive surface. Reason: {@code
 * JavAIVectorizable.query()} returns {@code JavAIList<T>}, and this module depends on
 * javai-runtime, not the reverse -- putting JavAIList here would create a circular
 * module dependency. What belongs here is what depends ON javai-runtime's types:
 * KnowledgeGraph, SubgraphResult, and VectorIndex.
 *
 * <p>Not yet implemented. See doc/spec/vector-collections.md for the full
 * {@code KnowledgeGraph<N, E>} contract, including why
 * {@code SubgraphResult<N, E> extends KnowledgeGraph<N, E>}.
 */
package dev.xtrafe.javai.collections;
