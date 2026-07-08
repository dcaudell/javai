package dev.xtrafe.javai.collections;

/**
 * Native graph edge contract for {@link KnowledgeGraph} participants. Declare it directly --
 * {@code class RelatesTo implements JavAIEdge} -- rather than relying on {@code @JavAIEdge} to weave it
 * in; see this package's package-info for why weaving buys nothing here (this interface has no methods,
 * so there's no boilerplate to save). See doc/spec/vector-collections.md for the full contract.
 */
public interface JavAIEdge {
}
