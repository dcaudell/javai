package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link JavAIKnowledgeGraph}/{@link JavAIKnowledgeSubgraphResult} against doc/spec/
 * vector-collections.md's contract: basic graph construction, {@code vector()}/{@code summaryVector()}
 * over nodes (dependents wiring, same as {@code JavAIArrayList}), {@code nearestSubgraph()}'s hybrid
 * similarity + hop-expansion search, and narrowing a {@code SubgraphResult} again without re-touching the
 * original graph.
 */
class JavAIKnowledgeGraphTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    // Chain: a --relates--> b --relates--> c --relates--> d
    private TestGraphNode a;
    private TestGraphNode b;
    private TestGraphNode c;
    private TestGraphNode d;
    private JavAIKnowledgeGraph<TestGraphNode, TestGraphEdge> graph;

    @BeforeEach
    void buildGraph() {
        a = new TestGraphNode("a", "alpha");
        b = new TestGraphNode("b", "beta");
        c = new TestGraphNode("c", "gamma");
        d = new TestGraphNode("d", "delta");
        graph = new JavAIKnowledgeGraph<>();
        graph.addEdge(a, b, new TestGraphEdge("relates"));
        graph.addEdge(b, c, new TestGraphEdge("relates"));
        graph.addEdge(c, d, new TestGraphEdge("relates"));
    }

    @Test
    void nodesEdgesAndNeighborsReflectWhatWasAdded() {
        assertEquals(Set.of(a, b, c, d), graph.nodes());
        assertTrue(graph.edges(a, b).stream().anyMatch(e -> e.toString().equals("relates")));
        assertEquals(Set.of(b), graph.neighbors(a));
        assertEquals(Set.of(), graph.neighbors(d), "d has no outgoing edges in this chain");
    }

    @Test
    void matchFiltersByPredicate() {
        Predicate<TestGraphNode> isAOrB = node -> node == a || node == b;
        JavAIList<TestGraphNode> matched = graph.match(TestGraphNode.class, isAOrB);

        assertEquals(2, matched.size());
        assertTrue(matched.contains(a));
        assertTrue(matched.contains(b));
        assertFalse(matched.contains(c));
    }

    @Test
    void summaryVectorPropagatesWhenANodeMutates() {
        EmbeddingVector before = graph.summaryVector();
        assertFalse(graph.isSummaryDirty());

        b.setText("beta, mutated");
        assertTrue(graph.isSummaryDirty(), "mutating a node must propagate to the graph that holds it");

        EmbeddingVector after = graph.summaryVector();
        assertNotEquals(before.values(), after.values());
    }

    @Test
    void nearestSubgraphRanksBySimilarityAndExpandsByHops() {
        SubgraphResult<TestGraphNode, TestGraphEdge> result = graph.nearestSubgraph(a.vector(), 1, 2);

        // a is the sole nearest origin (identical to the reference vector), expanded 2 hops along the chain.
        assertEquals(Set.of(a, b, c), result.nodes());
        assertEquals(0, result.hopsFrom(a, a));
        assertEquals(1, result.hopsFrom(b, a));
        assertEquals(2, result.hopsFrom(c, a));
        assertThrows(IllegalArgumentException.class, () -> result.hopsFrom(d, a),
                "d is 3 hops away, outside this subgraph");

        assertEquals(1.0, result.scoreOf(a), 1e-6, "a's score against its own vector must be ~1.0");
    }

    @Test
    void subgraphResultNarrowsAgainWithoutTouchingTheOriginalGraph() {
        SubgraphResult<TestGraphNode, TestGraphEdge> broad = graph.nearestSubgraph(a.vector(), 1, 2);
        assertEquals(Set.of(a, b, c), broad.nodes());

        SubgraphResult<TestGraphNode, TestGraphEdge> narrowed = broad.nearestSubgraph(a.vector(), 1, 1);

        assertEquals(Set.of(a, b), narrowed.nodes(), "narrowing to 1 hop must drop c");
        assertEquals(Set.of(a, b, c, d), graph.nodes(), "narrowing a result must never mutate the original graph");
    }
}
