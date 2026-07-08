package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.runtime.CollectionVectorSupport;
import dev.xtrafe.javai.runtime.EmbeddingVector;

import java.util.Map;
import java.util.Set;

/**
 * What {@link JavAIKnowledgeGraph#nearestSubgraph} returns. Extends {@link JavAIKnowledgeGraph} rather
 * than reimplementing it -- {@code nodes()}/{@code edges()}/{@code match()}/{@code nearestSubgraph()}
 * (narrowing again, scoped to just this subgraph's own nodes/edges) all come for free through inheritance,
 * exactly matching doc/spec/vector-collections.md's "narrow again without re-touching the graph it came
 * from" example.
 */
final class JavAIKnowledgeSubgraphResult<N extends JavAIGraphNode, E extends JavAIEdge>
        extends JavAIKnowledgeGraph<N, E> implements SubgraphResult<N, E> {

    private final EmbeddingVector reference;
    private final Map<N, Map<N, Integer>> hopsByNodeThenOrigin;

    JavAIKnowledgeSubgraphResult(Set<N> nodes, Map<N, Map<N, Set<E>>> adjacency, EmbeddingVector reference,
            Map<N, Map<N, Integer>> hopsByNodeThenOrigin) {
        for (N node : nodes) {
            addNode(node);
        }
        for (Map.Entry<N, Map<N, Set<E>>> fromEntry : adjacency.entrySet()) {
            for (Map.Entry<N, Set<E>> toEntry : fromEntry.getValue().entrySet()) {
                for (E edge : toEntry.getValue()) {
                    addEdge(fromEntry.getKey(), toEntry.getKey(), edge);
                }
            }
        }
        this.reference = reference;
        this.hopsByNodeThenOrigin = hopsByNodeThenOrigin;
    }

    @Override
    public double scoreOf(N node) {
        if (!nodes.contains(node)) {
            throw new IllegalArgumentException(node + " is not part of this subgraph");
        }
        return CollectionVectorSupport.similarityOf(node, reference);
    }

    @Override
    public int hopsFrom(N node, N origin) {
        Map<N, Integer> hopsForNode = hopsByNodeThenOrigin.get(node);
        if (hopsForNode == null || !hopsForNode.containsKey(origin)) {
            throw new IllegalArgumentException(node + " is not reachable from " + origin + " within this subgraph");
        }
        return hopsForNode.get(origin);
    }
}
