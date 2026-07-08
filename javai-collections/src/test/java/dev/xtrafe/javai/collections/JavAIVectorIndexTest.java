package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.runtime.JavAIList;
import dev.xtrafe.javai.runtime.JavAIRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavAIVectorIndexTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void nearestNReturnsTopNBySimilarityDescending() {
        TestVectorNode a = new TestVectorNode("alpha");
        TestVectorNode b = new TestVectorNode("beta");
        TestVectorNode c = new TestVectorNode("gamma");
        JavAIVectorIndex<TestVectorNode> index = new JavAIVectorIndex<>();
        index.add(a);
        index.add(b);
        index.add(c);

        JavAIList<TestVectorNode> nearest = index.nearestN(a.vector(), 2);

        assertEquals(2, nearest.size());
        assertEquals(a, nearest.get(0), "the exact match for the reference vector should rank first");
    }

    @Test
    void filterByMinSimilarityExcludesLowScoringElements() {
        TestVectorNode a = new TestVectorNode("alpha");
        TestVectorNode b = new TestVectorNode("beta");
        JavAIVectorIndex<TestVectorNode> index = new JavAIVectorIndex<>();
        index.add(a);
        index.add(b);

        JavAIList<TestVectorNode> onlyExactMatch = index.filterByMinSimilarity(a.vector(), 0.999);

        assertTrue(onlyExactMatch.contains(a));
    }

    @Test
    void removeShrinksTheIndex() {
        TestVectorNode a = new TestVectorNode("alpha");
        JavAIVectorIndex<TestVectorNode> index = new JavAIVectorIndex<>();
        index.add(a);
        assertEquals(1, index.size());

        assertTrue(index.remove(a));
        assertEquals(0, index.size());
    }

    @Test
    void sortByCosineDistanceOrdersEveryElement() {
        TestVectorNode a = new TestVectorNode("alpha");
        TestVectorNode b = new TestVectorNode("beta");
        JavAIVectorIndex<TestVectorNode> index = new JavAIVectorIndex<>();
        index.add(a);
        index.add(b);

        JavAIList<TestVectorNode> sorted = index.sortByCosineDistance(a.vector());

        assertEquals(2, sorted.size());
        assertEquals(a, sorted.get(0));
    }
}
