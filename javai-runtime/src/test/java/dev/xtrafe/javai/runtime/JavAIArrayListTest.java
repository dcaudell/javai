package dev.xtrafe.javai.runtime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavAIArrayListTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void nearestNReturnsTopNBySimilarityDescending() {
        TestNode a = new TestNode("alpha");
        TestNode b = new TestNode("beta");
        TestNode c = new TestNode("gamma");
        JavAIArrayList<TestNode> list = new JavAIArrayList<>();
        list.add(a);
        list.add(b);
        list.add(c);

        EmbeddingVector reference = a.vector();
        JavAIList<TestNode> nearest = list.nearestN(reference, 2);

        assertEquals(2, nearest.size());
        assertEquals(a, nearest.get(0), "the exact match for the reference vector should rank first");
    }

    @Test
    void filterByMinSimilarityExcludesLowScoringElements() {
        TestNode a = new TestNode("alpha");
        TestNode b = new TestNode("beta");
        JavAIArrayList<TestNode> list = new JavAIArrayList<>();
        list.add(a);
        list.add(b);

        JavAIList<TestNode> onlyExactMatch = list.filterByMinSimilarity(a.vector(), 0.999);

        assertTrue(onlyExactMatch.contains(a));
    }

    @Test
    void centroidIsTheMeanOfElementVectors() {
        TestNode a = new TestNode("alpha");
        TestNode b = new TestNode("beta");
        JavAIArrayList<TestNode> list = new JavAIArrayList<>();
        list.add(a);
        list.add(b);

        EmbeddingVector expected = VectorMath.centroid(java.util.List.of(a.vector(), b.vector()));
        assertArrayEquals(expected.values(), list.centroid().values());
    }

    @Test
    void addingAnElementRegistersTheListAsItsDependent() {
        TestNode a = new TestNode("alpha");
        JavAIArrayList<TestNode> list = new JavAIArrayList<>();
        list.add(a);
        list.summaryVector(); // clean after the add itself, to isolate the later mutation's effect below
        assertFalse(list.isSummaryDirty());

        a.setText("mutated");

        assertTrue(list.isSummaryDirty(), "mutating a member must dirty the list that holds it");
    }
}
