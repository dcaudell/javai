/**
 * Vector Collections: {@link KnowledgeGraph}/{@link SubgraphResult}, {@link VectorIndex},
 * and the {@link JavAIGraphNode}/{@link JavAIEdge} node/edge contracts.
 *
 * <p><b>Module-placement note:</b> {@code JavAISortable}, {@code JavAIList},
 * {@code JavAISet}, and {@code JavAIMap} live in {@code dev.xtrafe.javai.model}
 * (javai-model) instead of here, even though doc/spec/vector-collections.md discusses
 * them as part of this extension area's primitive surface. Reason: {@code
 * JavAIVectorizable.query()} returns {@code JavAIList<T>}, and this module depends on
 * javai-model, not the reverse -- putting JavAIList here would create a circular
 * module dependency. What belongs here is what depends ON javai-model's (and
 * javai-vector's) types: KnowledgeGraph, SubgraphResult, and VectorIndex.
 *
 * <p><b>{@code @JavAIGraphNode}/{@code @JavAIEdge} are deliberately never woven.</b> Two independent
 * reasons converge on the same answer:
 * <ol>
 *   <li>Both interfaces are empty markers -- zero methods. Weaving exists to save a developer from
 *       hand-writing method bodies ({@code @JavAIVectorizable} implements a dozen-plus real methods); an
 *       empty interface has no bodies to save. {@code class Article implements JavAIGraphNode} costs
 *       exactly as much as {@code @JavAIGraphNode class Article} would, with none of the machinery.
 *   <li>Weaving them would require {@code javai-substrate} to reference this module's types (to call
 *       {@code .implement(JavAIGraphNode.class)}), meaning javai-substrate would need to depend on
 *       javai-collections -- but the documented build order is the other way around (javai-substrate
 *       depends only on javai-annotations + javai-vector + javai-model; javai-collections depends on
 *       javai-vector + javai-model, which depend on javai-substrate's weaving having proven out first).
 *       The only way around that would be relocating these two interfaces into javai-model, mirroring
 *       {@code JavAIList}/{@code Set}/{@code Map}'s own placement precedent above -- pure churn for
 *       annotations that don't need it.
 * </ol>
 * The {@code @JavAIGraphNode}/{@code @JavAIEdge} annotations still exist in {@code javai-annotations} as
 * documentation/intent-signaling (and potentially for a future {@code javaic} compiler or IDE tooling to
 * detect graph participation statically), but carry no runtime behavior. Declare the interfaces directly.
 *
 * <p>{@code KnowledgeGraph}/{@code SubgraphResult}/{@code VectorIndex} are hand-written, not woven either
 * -- concrete, user-instantiated containers (like {@code javai-model}'s {@code JavAIArrayList}), not
 * something applied to arbitrary annotated domain classes. See {@link JavAIKnowledgeGraph}'s and
 * {@link JavAIVectorIndex}'s javadoc.
 */
package dev.xtrafe.javai.collections;
