package dev.xtrafe.javai.collections;

/**
 * What {@code nearestSubgraph()} returns. Because it extends {@link KnowledgeGraph}, every method there --
 * including {@code nearestSubgraph()} itself -- is callable again, directly on the result: a query result
 * can be queried again, narrowing structure and similarity together across multiple hops, without ever
 * dropping to a plain list. {@link JavAIKnowledgeSubgraphResult} is the concrete implementation, and gets
 * this "narrow again" behavior for free by extending {@link JavAIKnowledgeGraph} rather than reimplementing
 * it.
 */
public interface SubgraphResult<N extends JavAIGraphNode, E extends JavAIEdge> extends KnowledgeGraph<N, E> {

    /** This node's cosine similarity to the reference vector {@code nearestSubgraph()} was called with. */
    double scoreOf(N node);

    /** Structural distance (in hops) from {@code origin} to {@code node} within this subgraph. */
    int hopsFrom(N node, N origin);
}
